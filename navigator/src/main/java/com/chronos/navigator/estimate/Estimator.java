package com.chronos.navigator.estimate;

import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.DayType;
import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Kinds.NodeType;
import com.chronos.navigator.model.Kinds.Source;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Param;
import com.chronos.navigator.model.Regime;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The estimate layer (spec, "Estimate layer"): every night, without the
 * network, the log updates the Bayesian parameters. All updates are closed
 * form. Each observation carries its regime's data weight (0.3 in the
 * transition week). Recent days count most: adherence by a half-life,
 * speed through a Kalman filter that also follows its trend, day types and
 * capacity by the weekly forgetting factor — so the model follows habits
 * that change.
 *
 * Priors are never overwritten: the posteriors are recomputed from the priors
 * and the whole log on every run and stored under their own keys ("post.…",
 * source DATA). Re-importing the graph or re-running therefore never counts an
 * observation twice.
 */
public final class Estimator {
    private Estimator() {}

    public static final String POST = "post.";

    /** Everything the run learned, as parameters ready to store. */
    public record Result(List<Param> posteriors, Map<String, String> notes) {}

    public static Result run(Graph g, Inputs in, LocalDate today, long now) {
        List<Param> out = new ArrayList<>();
        Map<String, String> notes = new LinkedHashMap<>();
        double lambda = value(g, "forgetting.lambda");

        adherence(g, in, today, now, out);
        bias(g, in, today, now, out);
        dayTypes(g, in, today, now, out);
        capacity(g, in, today, lambda, now, out);
        energy(g, in, now, out);
        nodeHours(g, in, today, now, out);
        return new Result(out, notes);
    }

    // ---------------------------------------------------------------- weights

    static double value(Graph g, String key) {
        Param p = g.params.get(key);
        if (p == null || p.get("value") == null) throw new IllegalStateException("Parameter " + key + " has no value");
        return p.get("value");
    }

    /** The data weight of the calendar regime a date falls in, or the stated default. */
    static double regimeWeight(Graph g, LocalDate d) {
        Regime r = g.calendarRegime(d);
        if (r != null && r.dataWeight != null) return r.dataWeight;
        return value(g, "data_weight.default");
    }

    static long weeksAgo(LocalDate d, LocalDate today) {
        return Math.max(0, ChronoUnit.DAYS.between(d, today) / 7);
    }

    private static Param post(String key, Dist dist, String note, long now, Object... kv) {
        Param p = new Param(POST + key, dist, Source.DATA, note, kv);
        p.updatedAt = now;
        return p;
    }

    // -------------------------------------------------------------- adherence

    /** Weight of something {@code days} old: halves every half-life (recent days count more). */
    static double recency(double days, double halfLife) {
        return Math.pow(0.5, Math.max(0, days) / halfLife);
    }

    /**
     * How much of the goal time planned for a day was actually worked, per
     * activity and per day: f = min(1, minutes worked ÷ minutes planned). A
     * skipped block made up by a longer one later that day is not lost, and a
     * block half done counts half. Each day is one trial with a fractional
     * outcome, weighted by recency (half-life adherence.half_life_days):
     *
     *   a = a₀·r₀ + Σ wᵈ·fᵈ,   b = b₀·r₀ + Σ wᵈ·(1 − fᵈ)
     *
     * where r₀ fades your prior at the same rate from the first logged day.
     * Each activity then gets its own Beta, shrunk toward this pooled one as if
     * by adherence.activity_strength days, so a new activity starts from your
     * overall habit and moves to its own as its days add up. Today is left out
     * until it is over.
     */
    static void adherence(Graph g, Inputs in, LocalDate today, long now, List<Param> out) {
        Param prior = g.params.get("adherence");
        double halfLife = value(g, "adherence.half_life_days");
        double k = value(g, "adherence.activity_strength");

        // activity -> day -> {planned minutes, worked minutes}
        Map<String, Map<LocalDate, double[]>> byAct = new TreeMap<>();
        for (Inputs.Session s : in.sessions) {
            if (!s.goalWork() || !s.date().isBefore(today)) continue;
            double[] d = byAct.computeIfAbsent(s.activity(), x -> new TreeMap<>()).computeIfAbsent(s.date(), x -> new double[2]);
            if (s.planned() && s.plannedMin() != null) d[0] += s.plannedMin();
            if (!s.skipped() && s.actualMin() != null) d[1] += Math.max(0, s.actualMin());
        }
        LocalDate first = null;
        double done = 0, missed = 0;
        int days = 0;
        Map<String, double[]> perAct = new TreeMap<>();     // activity -> {done, missed, days}
        for (Map.Entry<String, Map<LocalDate, double[]>> e : byAct.entrySet())
            for (Map.Entry<LocalDate, double[]> d : e.getValue().entrySet()) {
                if (d.getValue()[0] <= 0) continue;          // nothing planned: nothing to keep or miss
                double f = Math.min(1, d.getValue()[1] / d.getValue()[0]);
                double w = regimeWeight(g, d.getKey()) * recency(ChronoUnit.DAYS.between(d.getKey(), today), halfLife);
                done += w * f; missed += w * (1 - f); days++;
                double[] a = perAct.computeIfAbsent(e.getKey(), x -> new double[3]);
                a[0] += w * f; a[1] += w * (1 - f); a[2]++;
                if (first == null || d.getKey().isBefore(first)) first = d.getKey();
            }
        double fade = first == null ? 1 : recency(ChronoUnit.DAYS.between(first, today), halfLife);
        double a = prior.get("a") * fade + done, b = prior.get("b") * fade + missed;
        double mean = a / (a + b);
        out.add(post("adherence", Dist.BETA, "Share of planned goal time worked, all activities: your prior Beta("
                + fmt(prior.get("a")) + ", " + fmt(prior.get("b")) + ") fading as " + days + " planned days add up, "
                + "recent days counting most.", now,
            "a", a, "b", b, "mean", mean, "days", days));
        for (Map.Entry<String, double[]> e : perAct.entrySet()) {
            double[] x = e.getValue();
            double aa = k * mean + x[0], bb = k * (1 - mean) + x[1];
            out.add(post("adherence." + e.getKey(), Dist.BETA, "Share of the time planned for \"" + e.getKey()
                    + "\" actually worked, over " + fmt(x[2]) + " planned days (recent days counting most), "
                    + "shrunk toward your overall habit as if by " + fmt(k) + " days.", now,
                "a", aa, "b", bb, "mean", aa / (aa + bb), "days", x[2]));
        }
    }

    // ------------------------------------------------------------------- bias

    /** Chronos activities that belong to a unit task, with that task's per-unit prior. */
    static Map<String, Node> unitPriors(Graph g, Inputs in) {
        Map<String, Node> out = new HashMap<>();
        for (Node n : g.nodes.values()) {
            if (n.perUnitPrior == null) continue;
            for (String a : in.activitiesOf(n.id)) out.put(a, n);
        }
        return out;
    }

    /**
     * How much longer than planned each kind of work takes, and where that is
     * heading. y = log(actual / planned); for unit work, log(minutes per unit ÷
     * Chronos's minutes per unit). One observation per day and activity: the
     * day's minutes over the day's units (or planned minutes), with spread
     * σ²/units — a day of three videos says more than a day of half of one.
     *
     * A Kalman filter with a local linear trend follows it: the state is the
     * level L (today's log ratio) and its slope S (change per day),
     *
     *   L' = L + S·Δ + ηL,   S' = S + ηS,   Var ηL = qL²·Δ,  Var ηS = qS²·Δ,
     *
     * so old days fade by themselves (the level may have moved since) and a
     * speed that keeps changing shows as a slope. The level is carried to today;
     * its variance never shrinks to nothing, which keeps the forecast honest
     * about how sure it can be. Your stated prior starts the level.
     */
    static void bias(Graph g, Inputs in, LocalDate today, long now, List<Param> out) {
        double sigma = value(g, "bias.obs_sigma");
        double qL = value(g, "kalman.level_q"), qS = value(g, "kalman.slope_q"), s0 = value(g, "kalman.slope_prior_sd");
        Param def = g.params.get("bias.default");
        Map<String, Node> units = unitPriors(g, in);

        // activity -> day -> {minutes, units or planned minutes}
        Map<String, Map<LocalDate, double[]>> days = new TreeMap<>();
        for (Inputs.Session s : in.sessions) {
            if (!s.goalWork() || s.skipped() || s.actualMin() == null || s.actualMin() <= 0) continue;
            if (s.date().isAfter(today)) continue;
            Inputs.UnitGoal ug = in.unitGoals.get(s.activity());
            double denom;
            if (ug != null) {
                // Minutes per unit do not depend on whether the session was planned.
                if (s.units() == null || s.units() <= 0 || ug.hoursPerUnit() <= 0) continue;
                denom = s.units();
            } else if (s.planned() && s.plannedMin() != null && s.plannedMin() > 0) {
                denom = s.plannedMin();
            } else continue;
            double[] d = days.computeIfAbsent(s.activity(), k -> new TreeMap<>()).computeIfAbsent(s.date(), k -> new double[2]);
            d[0] += s.actualMin();
            d[1] += denom;
        }
        Set<String> classes = new java.util.TreeSet<>(days.keySet());
        classes.addAll(units.keySet());
        for (String cls : classes) {
            double mu0, tau0;
            String from;
            Node unitNode = units.get(cls);
            Inputs.UnitGoal ug = in.unitGoals.get(cls);
            Param stated = g.params.get("bias." + cls);
            if (unitNode != null && ug != null && ug.hoursPerUnit() > 0) {
                mu0 = Math.log(unitNode.perUnitPrior / ug.hoursPerUnit());
                tau0 = unitNode.perUnitSigma != null ? unitNode.perUnitSigma : def.get("sigma");
                from = unitNode.id + "'s prior of " + fmt(unitNode.perUnitPrior) + " h per unit";
            } else if (stated != null && stated.source != Source.DATA && stated.get("median") != null) {
                mu0 = Math.log(stated.get("median"));
                tau0 = stated.get("sigma") != null ? stated.get("sigma") : def.get("sigma");
                from = "your stated bias for it";
            } else {
                mu0 = Math.log(def.get("median"));
                tau0 = def.get("sigma");
                from = "the default prior";
            }
            Kalman kf = new Kalman(mu0, tau0 * tau0, 0, s0 * s0, qL * qL, qS * qS);
            Map<LocalDate, double[]> obs = days.getOrDefault(cls, Map.of());
            LocalDate at = null;
            for (Map.Entry<LocalDate, double[]> d : obs.entrySet()) {
                if (at != null) kf.predict(ChronoUnit.DAYS.between(at, d.getKey()));
                at = d.getKey();
                double[] v = d.getValue();
                double y = ug != null ? Math.log((v[0] / v[1]) / (ug.hoursPerUnit() * 60)) : Math.log(v[0] / v[1]);
                // more units (or planned hours) in a day: a steadier reading; a light-weight regime: a looser one
                double n = ug != null ? Math.max(0.5, v[1]) : Math.max(0.5, v[1] / 60.0);
                kf.update(y, sigma * sigma / n / regimeWeight(g, d.getKey()));
            }
            if (at != null) kf.predict(ChronoUnit.DAYS.between(at, today));
            out.add(post("bias." + cls, Dist.LOGNORMAL, "Actual ÷ planned for \"" + cls + "\": " + from
                    + ", followed through " + obs.size() + " days by a Kalman filter (level and trend).", now,
                "mu", kf.level, "tau", Math.sqrt(kf.pLL), "median", Math.exp(kf.level),
                "slope", kf.slope, "slope_sd", Math.sqrt(kf.pSS), "n", obs.size()));
        }
    }

    /** A local-linear-trend Kalman filter on one number: level and slope per day. */
    static final class Kalman {
        double level, slope, pLL, pLS, pSS;
        final double qL, qS;

        Kalman(double level, double varLevel, double slope, double varSlope, double qL, double qS) {
            this.level = level; this.slope = slope; this.pLL = varLevel; this.pSS = varSlope; this.qL = qL; this.qS = qS;
        }

        /** Carries the state dt days ahead: x = F x, P = F P Fᵀ + Q, F = [[1, dt], [0, 1]]. */
        void predict(double dt) {
            if (dt <= 0) return;
            level += slope * dt;
            double ll = pLL + 2 * dt * pLS + dt * dt * pSS + qL * dt;
            double ls = pLS + dt * pSS;
            double ss = pSS + qS * dt;
            pLL = ll; pLS = ls; pSS = ss;
        }

        /** Takes in a reading of the level with variance r: H = [1, 0]. */
        void update(double y, double r) {
            double s = pLL + r;
            double kL = pLL / s, kS = pLS / s;
            double innovation = y - level;
            level += kL * innovation;
            slope += kS * innovation;
            double ll = (1 - kL) * pLL, ls = (1 - kL) * pLS, ss = pSS - kS * pLS;
            pLL = ll; pLS = ls; pSS = ss;
        }
    }

    // -------------------------------------------------------------- day types

    /** Per calendar regime, Dirichlet(strength × stated mix + the days you ticked). */
    static void dayTypes(Graph g, Inputs in, LocalDate today, long now, List<Param> out) {
        double defaultStrength = value(g, "daytype.prior_strength");
        Map<String, EnumMap<DayType, Integer>> counts = new HashMap<>();
        for (Inputs.DayRecord d : in.days) {
            if (d.anomaly() || d.type() == null || d.date().isAfter(today)) continue;
            Regime r = g.calendarRegime(d.date());
            if (r == null) continue;
            counts.computeIfAbsent(r.id, k -> new EnumMap<>(DayType.class)).merge(d.type(), 1, Integer::sum);
        }
        for (Regime r : g.regimes) {
            if (r.isOverlay() || r.mix == null || r.to == null || r.to.isBefore(today.minusDays(1))) continue;
            double strength = r.strength != null ? r.strength : defaultStrength;
            EnumMap<DayType, Integer> c = counts.getOrDefault(r.id, new EnumMap<>(DayType.class));
            Param p = post("daytype." + r.id, Dist.DIRICHLET, "Day types in " + r.id + ": your stated mix worth "
                    + fmt(strength) + " days, plus " + c.values().stream().mapToInt(Integer::intValue).sum()
                    + " days you ticked.", now);
            for (DayType t : DayType.values())
                p.values.put(t.name(), strength * r.mix.getOrDefault(t, 0.0) + c.getOrDefault(t, 0));
            out.add(p);
        }
    }

    // --------------------------------------------------------------- capacity

    /** Goal hours actually worked on each day: every goal session that was done. */
    static Map<LocalDate, Double> goalHoursByDay(Inputs in) {
        Map<LocalDate, Double> out = new HashMap<>();
        for (Inputs.Session s : in.sessions)
            if (s.goalWork() && !s.skipped() && s.actualMin() != null && s.actualMin() > 0)
                out.merge(s.date(), s.actualMin() / 60.0, Double::sum);
        return out;
    }

    /** Goal hours per day type: normal–normal from your stated mean, on the days you ticked. */
    static void capacity(Graph g, Inputs in, LocalDate today, double lambda, long now, List<Param> out) {
        Map<LocalDate, Double> hours = goalHoursByDay(in);
        for (DayType t : DayType.values()) {
            Param prior = g.params.get("capacity." + t.name());
            double mu0 = prior.get("mean"), tau0 = prior.get("tau0"), sigma = prior.get("sigma");
            double precision = 1 / (tau0 * tau0), weighted = mu0 / (tau0 * tau0);
            int n = 0;
            for (Inputs.DayRecord d : in.days) {
                if (d.type() != t || d.anomaly() || !d.date().isBefore(today)) continue;
                double w = regimeWeight(g, d.date()) * Math.pow(lambda, weeksAgo(d.date(), today));
                double y = hours.getOrDefault(d.date(), 0.0);
                precision += w / (sigma * sigma);
                weighted += w * y / (sigma * sigma);
                n++;
            }
            out.add(post("capacity." + t.name(), Dist.NORMAL, "Goal hours on a " + t.name() + " day: your prior of "
                    + fmt(mu0) + " h, corrected by " + n + " ticked days.", now,
                "mean", weighted / precision, "sd", Math.sqrt(1 / precision), "sigma", sigma, "n", n));
        }
    }

    // ----------------------------------------------------------------- energy

    /** Mean energy by hour, pulled toward the overall mean: m_h = (n_h ȳ_h + k ȳ) / (n_h + k). */
    static void energy(Graph g, Inputs in, long now, List<Param> out) {
        if (in.ratings.isEmpty()) return;
        double k = value(g, "energy.shrink_k");
        double sum = 0;
        double[] s = new double[24];
        int[] n = new int[24];
        for (Inputs.Rating r : in.ratings) {
            if (r.hour() < 0 || r.hour() > 23) continue;
            s[r.hour()] += r.energy(); n[r.hour()]++; sum += r.energy();
        }
        double overall = sum / in.ratings.size();
        Param p = post("energy", Dist.POINT, "Mean energy by hour (1–5) from " + in.ratings.size()
            + " ratings, each hour pulled toward your overall mean as if by " + fmt(k) + " extra ratings.", now,
            "overall", overall);
        for (int h = 0; h < 24; h++)
            if (n[h] > 0) p.values.put("h" + h, (s[h] + k * overall) / (n[h] + k));
        out.add(p);
    }

    // -------------------------------------------------------------- progress

    /**
     * Hours logged on each node's activities since the graph was made, and
     * for a skill the level they come to: hours ÷ hours per level point.
     */
    static void nodeHours(Graph g, Inputs in, LocalDate today, long now, List<Param> out) {
        LocalDate since = g.created != null ? g.created : LocalDate.MIN;
        for (Node n : g.nodes.values()) {
            List<String> acts = in.activitiesOf(n.id);
            if (acts.isEmpty()) continue;
            double h = 0;
            for (Inputs.Session s : in.sessions)
                if (acts.contains(s.activity()) && !s.skipped() && s.actualMin() != null && !s.date().isBefore(since)
                        && !s.date().isAfter(today))
                    h += s.actualMin() / 60.0;
            if (n.type == NodeType.SKILL && n.levelFrom != null) {
                Double perPoint = perPointP50(n);
                double level = perPoint == null ? n.levelFrom : Math.min(n.levelTo, n.levelFrom + h / perPoint);
                out.add(post("hours." + n.id, Dist.POINT, n.id + ": " + fmt(h) + " h logged since " + since
                    + (perPoint == null ? "" : ", level about " + fmt(level)), now, "hours", h, "level", level));
            } else {
                out.add(post("hours." + n.id, Dist.POINT, n.id + ": " + fmt(h) + " h logged since " + since, now, "hours", h));
            }
        }
    }

    /** Hours per level point at the median: stated per point, or the total spread over the levels to climb. */
    public static Double perPointP50(Node n) {
        if (n.perPointP50 != null) return n.perPointP50;
        if (n.effortP50 != null && n.levelFrom != null && n.levelTo != null && n.levelTo > n.levelFrom)
            return n.effortP50 / (n.levelTo - n.levelFrom);
        return null;
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
