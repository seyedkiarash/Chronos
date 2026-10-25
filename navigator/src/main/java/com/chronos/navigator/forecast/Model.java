package com.chronos.navigator.forecast;

import com.chronos.navigator.estimate.Estimator;
import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.model.Edge;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.DayType;
import com.chronos.navigator.model.Kinds.EdgeKind;
import com.chronos.navigator.model.Kinds.NodeType;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Param;
import com.chronos.navigator.model.Regime;
import com.chronos.navigator.model.Requirements;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The graph, its parameters and today's progress, flattened into arrays the
 * simulation can run over thousands of times without allocating. Built once
 * per forecast (and once per allocation, reused for every candidate).
 *
 * Every number comes from a parameter: the posterior where the estimate layer
 * has written one, else the prior the user, the spec or the LLM gave.
 */
final class Model {

    /** How a node's work is sized. */
    enum Effort { NONE, TOTAL, UNITS, SKILL }

    final Graph graph;
    final LocalDate today;
    final int horizonDays;
    final int n;
    final Node[] nodes;
    final Map<String, Integer> index = new HashMap<>();

    final NodeType[] type;
    final int[] deadline, earliest, decideBy;          // day offsets, or NONE
    final double[] weight, value, floorPerDay, capPerDay;
    /** An approved minimum: hours per day between two day offsets (node.ID.min_weekly). */
    final double[] minPerDay;
    final int[] minFrom, minTo;
    final boolean[] stream, doneAtStart, hasStreamDep;
    final int[][] and, children;
    final int[][][] or;
    final Effort[] effort;
    final double[] p50, p90, hoursDone, unitsLeft, hoursPerUnit, levelsLeft;
    final double[] biasMu, biasTau;                    // per node: its class's posterior on log(actual/planned)
    final double[] successA, successB;
    /** For each node, the day offsets it is paused on (overlay regimes), as [from, to] pairs. */
    final int[][] paused;

    // calendar
    final int[] regimeOfDay;                            // index into regimes, or -1
    final List<double[]> dayTypeAlpha = new ArrayList<>(); // per regime: Dirichlet alpha (IDEAL, BUSY, STRESS)
    final List<Regime> calendar = new ArrayList<>();
    final double[] capMean = new double[3], capSd = new double[3], capSigma = new double[3];
    /** Overlay planned hours: per day offset, node index -> hours that day. */
    final Map<Integer, Map<Integer, Double>> planned = new HashMap<>();
    /** Hours Chronos has planned for a node: per day offset, node index -> hours. Wins over the overlay. */
    final Map<Integer, Map<Integer, Double>> chronos = new HashMap<>();
    /**
     * Per node whose deadline falls inside Chronos's plan: the hours Chronos still plans for it from each
     * day offset to its deadline (null otherwise). Chronos re-plans every night, so work left behind comes
     * back on the days before the deadline; see {@link Forecaster.Sim#split}.
     */
    final Map<Integer, double[]> chronosAhead = new HashMap<>();

    /** Per node: the Beta of the share of planned time worked on it (its activity's, else the pooled one). */
    final double[] adhA, adhB, adhMean;
    /** The pooled share, for what is not one node's: the week's expected capacity. */
    final double adhPooled;
    final double noiseSigma;
    final int dailyDays;

    static final int NONE = Integer.MIN_VALUE;

    Model(Graph g, Inputs in, LocalDate today, int horizonWeeks) {
        this.graph = g;
        this.today = today;
        int full = (int) ChronoUnit.DAYS.between(today, g.horizonEnd) + 1;
        this.horizonDays = horizonWeeks > 0 ? Math.min(full, horizonWeeks * 7) : full;
        this.n = g.nodes.size();
        nodes = g.nodes.values().toArray(new Node[0]);
        for (int i = 0; i < n; i++) index.put(nodes[i].id, i);

        type = new NodeType[n];
        deadline = new int[n]; earliest = new int[n]; decideBy = new int[n];
        weight = new double[n]; value = new double[n]; floorPerDay = new double[n]; capPerDay = new double[n];
        minPerDay = new double[n]; minFrom = new int[n]; minTo = new int[n];
        stream = new boolean[n]; doneAtStart = new boolean[n]; hasStreamDep = new boolean[n];
        and = new int[n][]; children = new int[n][]; or = new int[n][][];
        effort = new Effort[n];
        p50 = new double[n]; p90 = new double[n]; hoursDone = new double[n]; unitsLeft = new double[n];
        hoursPerUnit = new double[n]; levelsLeft = new double[n];
        biasMu = new double[n]; biasTau = new double[n];
        successA = new double[n]; successB = new double[n];
        paused = new int[n][];

        Param def = g.params.get("bias.default");
        double defaultValue = value(g, "allocate.default_value");
        for (int i = 0; i < n; i++) {
            Node nd = nodes[i];
            type[i] = nd.type;
            deadline[i] = offset(nd.deadline);
            earliest[i] = offset(nd.earliest);
            decideBy[i] = offset(nd.decideBy);
            stream[i] = nd.type == NodeType.STREAM;
            doneAtStart[i] = isDone(nd);
            value[i] = nd.standaloneValue != null ? nd.standaloneValue : goalWeight(in, nd, defaultValue);
            weight[i] = (nd.owner == null ? 1 : Math.max(1, nd.owner.authority)) * value[i];
            floorPerDay[i] = nd.weeklyFloorH == null ? 0 : nd.weeklyFloorH / 7.0;
            capPerDay[i] = nd.maxHPerDay == null ? 24 : nd.maxHPerDay;
            if (nd.minWeeklyH != null && nd.minWeeklyFrom != null && nd.minWeeklyUntil != null) {
                minPerDay[i] = nd.minWeeklyH / 7.0;
                minFrom[i] = offset(nd.minWeeklyFrom);
                minTo[i] = offset(nd.minWeeklyUntil);
            }

            List<String> acts = in.activitiesOf(nd.id);
            Param hours = g.params.get(Estimator.POST + "hours." + nd.id);
            hoursDone[i] = hours == null || hours.get("hours") == null ? 0 : hours.get("hours");

            // the bias of the node's own kind of work, else the default
            biasMu[i] = Math.log(def.get("median"));
            biasTau[i] = def.get("sigma");
            for (String a : acts) {
                Param b = g.params.get(Estimator.POST + "bias." + a);
                if (b != null) { biasMu[i] = b.get("mu"); biasTau[i] = b.get("tau"); break; }
            }

            effort[i] = Effort.NONE;
            Inputs.UnitGoal ug = null;
            if (nd.expanded) acts = List.of();          // its children carry the work now
            for (String a : acts) if (in.unitGoals.get(a) != null) { ug = in.unitGoals.get(a); break; }
            if (nd.expanded) {
                // no effort of its own: done when what it now depends on — its children — is done
            } else if (nd.type == NodeType.TASK && (ug != null || nd.unitsTotal != null) && nd.perUnitPrior != null) {
                effort[i] = Effort.UNITS;
                if (ug != null && ug.totalUnits() != null) {
                    unitsLeft[i] = Math.max(0, ug.totalUnits() - ug.doneUnits());
                    hoursPerUnit[i] = ug.hoursPerUnit();
                    boolean learned = false;
                    for (String a : acts) learned |= g.params.containsKey(Estimator.POST + "bias." + a);
                    if (!learned && ug.hoursPerUnit() > 0) {
                        // Not estimated yet: the node's own prior per unit, against Chronos's hours per unit.
                        biasMu[i] = Math.log(nd.perUnitPrior / ug.hoursPerUnit());
                        biasTau[i] = nd.perUnitSigma != null ? nd.perUnitSigma : def.get("sigma");
                    }
                } else {
                    unitsLeft[i] = nd.remainingUnits();
                    hoursPerUnit[i] = 1;
                    biasMu[i] = Math.log(nd.perUnitPrior);
                    biasTau[i] = nd.perUnitSigma != null ? nd.perUnitSigma : def.get("sigma");
                }
                if (unitsLeft[i] <= 0) doneAtStart[i] = true;
            } else if (nd.type == NodeType.SKILL && nd.levelFrom != null && nd.levelTo != null) {
                effort[i] = Effort.SKILL;
                Double pp = Estimator.perPointP50(nd);
                double ratio = nd.perPointP90 != null && nd.perPointP50 != null ? nd.perPointP90 / nd.perPointP50
                    : nd.effortP90 != null && nd.effortP50 != null ? nd.effortP90 / nd.effortP50 : 1;
                p50[i] = pp == null ? 0 : pp;
                p90[i] = p50[i] * ratio;
                levelsLeft[i] = nd.levelTo - nd.levelFrom;
            } else if (nd.takesHours() && nd.effortP50 != null && nd.effortP90 != null) {
                effort[i] = Effort.TOTAL;
                p50[i] = nd.effortP50;
                p90[i] = nd.effortP90;
            }
            if (nd.successA != null && nd.successB != null) { successA[i] = nd.successA; successB[i] = nd.successB; }

            List<int[]> pz = new ArrayList<>();
            for (String r : nd.pausedDuring) {
                Regime reg = g.regime(r);
                if (reg != null && reg.from != null && reg.to != null) pz.add(new int[]{offset(reg.from), offset(reg.to)});
            }
            for (LocalDate[] span : nd.pausedSpans) pz.add(new int[]{offset(span[0]), offset(span[1])});
            paused[i] = flatten(pz);
        }

        for (int i = 0; i < n; i++) {
            List<Integer> a = new ArrayList<>(), ch = new ArrayList<>();
            Map<Integer, List<Integer>> groups = new HashMap<>();
            for (Edge e : g.into(nodes[i].id)) {
                Integer from = index.get(e.from());
                if (from == null) continue;
                switch (e.kind()) {
                    case AND -> { a.add(from); if (stream[from]) hasStreamDep[i] = true; }
                    case OR_GROUP -> groups.computeIfAbsent(e.group(), k -> new ArrayList<>()).add(from);
                    case CHILD -> ch.add(from);
                    default -> { }
                }
            }
            and[i] = a.stream().mapToInt(Integer::intValue).toArray();
            children[i] = ch.stream().mapToInt(Integer::intValue).toArray();
            or[i] = groups.values().stream().map(l -> l.stream().mapToInt(Integer::intValue).toArray()).toArray(int[][]::new);
        }

        // calendar: the regime of each day, its posterior day mix, overlay plans
        regimeOfDay = new int[horizonDays];
        for (int d = 0; d < horizonDays; d++) {
            Regime r = g.calendarRegime(today.plusDays(d));
            if (r == null) { regimeOfDay[d] = -1; continue; }
            int k = calendar.indexOf(r);
            if (k < 0) {
                calendar.add(r);
                k = calendar.size() - 1;
                Param post = g.params.get(Estimator.POST + "daytype." + r.id);
                double strength = r.strength != null ? r.strength : value(g, "daytype.prior_strength");
                double[] alpha = new double[3];
                for (DayType t : DayType.values())
                    alpha[t.ordinal()] = post != null && post.get(t.name()) != null ? post.get(t.name())
                        : strength * (r.mix == null ? 1.0 / 3 : r.mix.getOrDefault(t, 0.0));
                for (int t = 0; t < 3; t++) alpha[t] = Math.max(alpha[t], 1e-3);
                dayTypeAlpha.add(alpha);
            }
            regimeOfDay[d] = k;
            for (Regime o : g.overlays(today.plusDays(d)))
                for (Map.Entry<String, Double> e : o.plannedHPerDay.entrySet()) {
                    Integer node = index.get(e.getKey());
                    if (node != null) planned.computeIfAbsent(d, x -> new HashMap<>()).put(node, e.getValue());
                }
        }
        for (Map.Entry<String, Map<LocalDate, Double>> e : in.chronosPlan.entrySet()) {
            Integer node = index.get(e.getKey());
            if (node == null) continue;
            for (Map.Entry<LocalDate, Double> d : e.getValue().entrySet()) {
                int off = offset(d.getKey());
                if (off >= 0 && off < horizonDays) chronos.computeIfAbsent(off, x -> new HashMap<>()).put(node, d.getValue());
            }
            int last = -1;
            for (LocalDate d : e.getValue().keySet()) last = Math.max(last, offset(d));
            int due = deadline[node];
            if (due == NONE || due < 0 || due > last || due >= horizonDays) continue;
            double[] ahead = new double[due + 2];
            for (int d = due; d >= 0; d--) {
                Map<Integer, Double> day = chronos.get(d);
                ahead[d] = ahead[d + 1] + (day == null ? 0 : day.getOrDefault(node, 0.0));
            }
            chronosAhead.put(node, ahead);
        }
        for (DayType t : DayType.values()) {
            Param post = g.params.get(Estimator.POST + "capacity." + t.name());
            Param prior = g.params.get("capacity." + t.name());
            capMean[t.ordinal()] = post != null ? post.get("mean") : prior.get("mean");
            capSd[t.ordinal()] = post != null ? post.get("sd") : prior.get("tau0");
            capSigma[t.ordinal()] = prior.get("sigma");
        }

        Param pooled = g.params.get(Estimator.POST + "adherence");
        if (pooled == null) pooled = g.params.get("adherence");
        adhPooled = pooled.get("a") / (pooled.get("a") + pooled.get("b"));
        adhA = new double[n]; adhB = new double[n]; adhMean = new double[n];
        for (int i = 0; i < n; i++) {
            Param own = null;
            for (String a : in.activitiesOf(nodes[i].id)) {
                own = g.params.get(Estimator.POST + "adherence." + a);
                if (own != null) break;
            }
            Param p = own != null ? own : pooled;
            adhA[i] = p.get("a"); adhB[i] = p.get("b");
            adhMean[i] = adhA[i] / (adhA[i] + adhB[i]);
        }
        noiseSigma = value(g, "forecast.noise_sigma");
        dailyDays = (int) Math.round(value(g, "forecast.daily_weeks") * 7);
    }

    static double value(Graph g, String key) {
        Param p = g.params.get(key);
        if (p == null || p.get("value") == null) throw new IllegalStateException("Parameter " + key + " has no value");
        return p.get("value");
    }

    private static double goalWeight(Inputs in, Node nd, double fallback) {
        for (String a : in.activitiesOf(nd.id)) {
            Double w = in.goalWeights.get(a);
            if (w != null && w > 0) return w;
        }
        return fallback;
    }

    static boolean isDone(Node n) {
        if (n.status == null) return false;
        String s = n.status.toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("done") || s.startsWith("reached");
    }

    private int offset(LocalDate d) {
        return d == null ? NONE : (int) ChronoUnit.DAYS.between(today, d);
    }

    private static int[] flatten(List<int[]> pairs) {
        int[] out = new int[pairs.size() * 2];
        for (int i = 0; i < pairs.size(); i++) { out[2 * i] = pairs.get(i)[0]; out[2 * i + 1] = pairs.get(i)[1]; }
        return out;
    }

    boolean pausedOn(int node, int day) {
        int[] p = paused[node];
        for (int k = 0; k + 1 < p.length; k += 2) if (day >= p[k] && day <= p[k + 1]) return true;
        return false;
    }

    boolean takesHours(int i) {
        return type[i] == NodeType.STREAM || type[i] == NodeType.SKILL || type[i] == NodeType.TASK;
    }

    /** Whether the node is one the allocation and the forecast give hours to at all. */
    boolean schedulable(int i) {
        return takesHours(i) && Requirements.schedulable(type[i]) && nodes[i].plannable() && !nodes[i].expanded;
    }

    /** The hours a day the node must get on a day: its weekly floor, or an approved minimum if higher. */
    double floorOn(int i, int day) {
        double f = floorPerDay[i];
        if (minPerDay[i] > 0 && day >= minFrom[i] && day <= minTo[i]) f = Math.max(f, minPerDay[i]);
        return f;
    }
}
