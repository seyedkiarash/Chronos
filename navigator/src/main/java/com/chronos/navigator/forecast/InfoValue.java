package com.chronos.navigator.forecast;

import com.chronos.navigator.estimate.Estimator;
import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.DayType;
import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Kinds.NodeType;
import com.chronos.navigator.model.Kinds.Source;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Overrides;
import com.chronos.navigator.model.Param;
import com.chronos.navigator.model.Regime;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Onboarding (spec, stage 6): which of the numbers you gave would change the
 * forecast most if you knew them better — the questions with the highest
 * information value.
 *
 * Each uncertain input is set to the low and the high end of what it is now
 * believed to be (its 25th and 75th percentile, held there firmly), and the
 * whole graph is forecast both ways with the same seed. The difference in
 *
 *   J = Σₘ wₘ · Pₘ(deadline),  wₘ = owner authority × value  (as the allocation weighs deadlines)
 *
 * is how much an answer could matter. Inputs: the effort of a task, the odds
 * of a gate or a milestone, and the mix of days in a regime still held loosely.
 */
public final class InfoValue {
    private InfoValue() {}

    /** z of the 75th percentile. */
    static final double Z75 = 0.6744897501960817;

    /** What could be asked, and the two ends it is forecast at. */
    public record Candidate(String key, String kind, String subject, String text, Param low, Param high) {}

    public record Ranked(String key, String kind, String subject, String text, double impact) {}

    public static List<Candidate> candidates(Graph g, LocalDate today) {
        List<Candidate> out = new ArrayList<>();
        for (Node n : g.nodes.values()) {
            if (done(n) || !n.plannable() || n.expanded) continue;
            if ((n.type == NodeType.TASK || n.type == NodeType.SKILL) && n.effortP50 != null && n.effortP90 != null
                    && n.perPointP50 == null && n.perUnitPrior == null && n.effortP90 > n.effortP50) {
                double sigma = Math.log(n.effortP90 / n.effortP50) / Rand.Z90;
                if (sigma < 0.1) continue;
                String key = Overrides.nodeKey(n.id, "effort");
                out.add(new Candidate(key, "effort", n.id, String.format(Locale.ROOT,
                    "How many hours will \"%s\" take? Now %s–%s h (median to 90th percentile).",
                    n.title, trim(n.effortP50), trim(n.effortP90)),
                    effort(key, n.effortP50 * Math.exp(-Z75 * sigma)), effort(key, n.effortP50 * Math.exp(Z75 * sigma))));
            }
            if ((n.type == NodeType.GATE || n.type == NodeType.MILESTONE) && n.successA != null && n.successB != null) {
                double k = n.successA + n.successB;
                if (k >= 30) continue;
                double mu = n.successA / k, sd = Math.sqrt(mu * (1 - mu) / (k + 1));
                String key = Overrides.nodeKey(n.id, "success");
                out.add(new Candidate(key, "success", n.id, String.format(Locale.ROOT,
                    "How likely is \"%s\"? Now about %.0f%%, held as loosely as %s cases.", n.title, 100 * mu, trim(k)),
                    success(key, Math.max(0.02, mu - Z75 * sd)), success(key, Math.min(0.98, mu + Z75 * sd))));
            }
        }
        double defaultStrength = g.params.get("daytype.prior_strength") == null ? 10
            : g.params.get("daytype.prior_strength").get("value");
        for (Regime r : g.regimes) {
            if (r.isOverlay() || r.mix == null || r.to == null || r.to.isBefore(today)) continue;
            if (g.params.containsKey(Estimator.POST + "daytype." + r.id)) continue;   // the log already speaks
            double strength = r.strength != null ? r.strength : defaultStrength;
            if (strength > 5) continue;
            String key = Overrides.regimeKey(r.id, "mix");
            out.add(new Candidate(key, "mix", r.id, String.format(Locale.ROOT,
                "What will days be like in %s (%s to %s)? Now %.0f%% ideal, %.0f%% busy, %.0f%% stressful, held loosely.",
                r.id, r.from, r.to, 100 * r.mix.getOrDefault(DayType.IDEAL, 0.0), 100 * r.mix.getOrDefault(DayType.BUSY, 0.0),
                100 * r.mix.getOrDefault(DayType.STRESS, 0.0)),
                mix(key, r, DayType.STRESS), mix(key, r, DayType.IDEAL)));
        }
        return out;
    }

    /**
     * Ranks the candidates by how far their two ends move J, as many as fit the
     * time budget (nearest deadlines' inputs first); the rest are left out.
     *
     * @param with the graph as it would be with these parameters added (the stored graph, rebuilt)
     */
    public static List<Ranked> rank(Graph g, Function<List<Param>, Graph> with, Inputs in, LocalDate today,
                                    int runs, long seed, double budgetSeconds) {
        long t0 = System.nanoTime();
        Model base = new Model(g, in, today, 0);
        Map<String, Double> weight = new HashMap<>();
        for (int i = 0; i < base.n; i++)
            if (base.deadline[i] != Model.NONE && base.deadline[i] >= 0 && !base.doneAtStart[i] && base.nodes[i].plannable())
                weight.put(base.nodes[i].id, base.weight[i]);

        List<Candidate> cands = candidates(g, today);
        cands.sort(Comparator.comparing(c -> soonest(g, c)));
        List<Ranked> out = new ArrayList<>();
        for (Candidate c : cands) {
            if ((System.nanoTime() - t0) / 1e9 > budgetSeconds) break;
            double lo = objective(Forecaster.run(with.apply(List.of(c.low())), in, today, runs, seed), weight);
            double hi = objective(Forecaster.run(with.apply(List.of(c.high())), in, today, runs, seed), weight);
            double impact = Math.abs(hi - lo);
            if (impact > 1e-3) out.add(new Ranked(c.key(), c.kind(), c.subject(), c.text(), impact));
        }
        out.sort(Comparator.comparingDouble(r -> -r.impact()));
        return out;
    }

    static double objective(Forecaster.Result r, Map<String, Double> weight) {
        double j = 0;
        for (Map.Entry<String, Double> e : weight.entrySet()) {
            Forecaster.NodeResult n = r.nodes().get(e.getKey());
            if (n != null && n.pByDeadline() != null) j += e.getValue() * n.pByDeadline();
        }
        return j;
    }

    /** The nearest date the input bears on, to try the most pressing ones first. */
    private static LocalDate soonest(Graph g, Candidate c) {
        if (c.kind().equals("mix")) { Regime r = g.regime(c.subject()); return r == null ? LocalDate.MAX : r.from; }
        Node n = g.node(c.subject());
        return n == null || n.deadline == null ? LocalDate.MAX : n.deadline;
    }

    private static Param effort(String key, double p50) {
        return new Param(key, Dist.LOGNORMAL, Source.USER, "variant", "p50", p50, "p90", p50 * 1.05, "override", 1);
    }

    private static Param success(String key, double p) {
        return new Param(key, Dist.BETA, Source.USER, "variant", "a", p * 200, "b", (1 - p) * 200, "override", 1);
    }

    /** The regime's mix moved 30% of the way toward all-of-one-type, held firmly. */
    private static Param mix(String key, Regime r, DayType toward) {
        Param p = new Param(key, Dist.DIRICHLET, Source.USER, "variant");
        for (DayType t : DayType.values())
            p.values.put(t.name(), 0.7 * r.mix.getOrDefault(t, 0.0) + (t == toward ? 0.3 : 0));
        p.values.put("strength", 200.0);
        p.values.put("override", 1.0);
        return p;
    }

    private static boolean done(Node n) {
        if (n.status == null) return false;
        String s = n.status.toLowerCase(Locale.ROOT);
        return s.startsWith("done") || s.startsWith("reached") || s.startsWith("dropped");
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
