package com.chronos.navigator.estimate;

import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.DayType;
import com.chronos.navigator.model.Param;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * How each learned value got where it is. The estimate layer is a pure
 * function of the priors and the log up to a date, so its past is replayed
 * exactly: run as of every day since the first log, each value gives a point
 * (with its 10–90% band), and the difference between consecutive days is what
 * that day's log did to it — the waterfall from your prior to today, with the
 * reason for every step read from the sessions themselves.
 */
public final class History {
    private History() {}

    /** One day's value and its 10–90% band, in the value's own unit. */
    public record Point(LocalDate day, double value, double low, double high) {}

    /** What one day's log did to the value, and what that log was. */
    public record Step(LocalDate day, double delta, String why) {}

    /**
     * @param key   the posterior's key (post.bias.X, post.adherence.X, post.capacity.T)
     * @param kind  speed | adherence | capacity
     * @param unit  "h/unit name", "×", "%", "h"
     * @param prior the value before any log
     */
    public record Series(String key, String kind, String subject, String unit, double prior,
                         List<Point> points, List<Step> steps) {
        public double now() { return points.isEmpty() ? prior : points.get(points.size() - 1).value(); }
    }

    private static final double Z90 = 1.2815515655446004;

    public static List<Series> build(Graph g, Inputs in, LocalDate today) {
        LocalDate first = null;
        for (Inputs.Session s : in.sessions) if (first == null || s.date().isBefore(first)) first = s.date();
        if (first == null) return List.of();
        if (ChronoUnit.DAYS.between(first, today) > 120) first = today.minusDays(120);

        // key -> day -> {value, low, high}
        Map<String, TreeMap<LocalDate, double[]>> values = new LinkedHashMap<>();
        Map<String, String[]> meta = new LinkedHashMap<>();      // key -> {kind, subject, unit}
        Map<String, Double> priors = new LinkedHashMap<>();
        for (LocalDate d = first.minusDays(1); !d.isAfter(today); d = d.plusDays(1)) {
            Map<String, Param> post = new LinkedHashMap<>();
            for (Param p : Estimator.run(g, in, d, 0).posteriors()) post.put(p.key, p);
            for (Param p : post.values()) {
                double[] v = read(p, in);
                if (v == null) continue;
                String[] m = describe(p.key, in);
                if (m == null) continue;
                meta.putIfAbsent(p.key, m);
                if (d.equals(first.minusDays(1)) || !priors.containsKey(p.key)) priors.putIfAbsent(p.key, v[0]);
                values.computeIfAbsent(p.key, k -> new TreeMap<>()).put(d, v);
            }
        }

        List<Series> out = new ArrayList<>();
        for (Map.Entry<String, TreeMap<LocalDate, double[]>> e : values.entrySet()) {
            String key = e.getKey();
            String[] m = meta.get(key);
            List<Point> points = new ArrayList<>();
            List<Step> steps = new ArrayList<>();
            double[] prev = null;
            LocalDate prevDay = null;
            for (Map.Entry<LocalDate, double[]> d : e.getValue().entrySet()) {
                double[] v = d.getValue();
                if (!d.getKey().isBefore(first)) points.add(new Point(d.getKey(), v[0], v[1], v[2]));
                if (prev != null && Math.abs(v[0] - prev[0]) > 1e-9) {
                    // Adherence and capacity take in a day once it is over; speed takes it in the same day.
                    LocalDate source = m[0].equals("speed") ? d.getKey() : prevDay;
                    steps.add(new Step(source, v[0] - prev[0], why(m[0], m[1], in, source)));
                }
                prev = v;
                prevDay = d.getKey();
            }
            // A value nothing has moved yet has no history worth showing.
            if (steps.isEmpty()) continue;
            out.add(new Series(key, m[0], m[1], m[2], priors.getOrDefault(key, points.get(0).value()), points, steps));
        }
        return out;
    }

    /** The value and its band, in the unit shown: hours per unit for unit work, else the multiplier; a share; hours. */
    static double[] read(Param p, Inputs in) {
        if (p.key.startsWith(Estimator.POST + "bias.")) {
            Double mu = p.get("mu"), tau = p.get("tau");
            if (mu == null || tau == null) return null;
            String act = p.key.substring((Estimator.POST + "bias.").length());
            Inputs.UnitGoal ug = in.unitGoals.get(act);
            double scale = ug != null && ug.hoursPerUnit() > 0 ? ug.hoursPerUnit() : 1;
            return new double[]{scale * Math.exp(mu), scale * Math.exp(mu - Z90 * tau), scale * Math.exp(mu + Z90 * tau)};
        }
        if (p.key.startsWith(Estimator.POST + "adherence")) {
            Double a = p.get("a"), b = p.get("b");
            if (a == null || b == null) return null;
            double mean = a / (a + b), sd = Math.sqrt(mean * (1 - mean) / (a + b + 1));
            return new double[]{100 * mean, 100 * Math.max(0, mean - Z90 * sd), 100 * Math.min(1, mean + Z90 * sd)};
        }
        if (p.key.startsWith(Estimator.POST + "capacity.")) {
            Double mean = p.get("mean"), sd = p.get("sd");
            if (mean == null || sd == null) return null;
            return new double[]{mean, Math.max(0, mean - Z90 * sd), mean + Z90 * sd};
        }
        return null;
    }

    /** {kind, subject, unit}, or null for what is not shown. */
    static String[] describe(String key, Inputs in) {
        if (key.startsWith(Estimator.POST + "bias.")) {
            String act = key.substring((Estimator.POST + "bias.").length());
            Inputs.UnitGoal ug = in.unitGoals.get(act);
            return new String[]{"speed", act, ug != null ? "h/unit" : "×"};
        }
        if (key.equals(Estimator.POST + "adherence")) return new String[]{"adherence", "", "%"};
        if (key.startsWith(Estimator.POST + "adherence.")) return new String[]{"adherence", key.substring((Estimator.POST + "adherence.").length()), "%"};
        if (key.startsWith(Estimator.POST + "capacity.")) {
            String t = key.substring((Estimator.POST + "capacity.").length());
            try { DayType.valueOf(t); } catch (IllegalArgumentException e) { return null; }
            return new String[]{"capacity", t, "h"};
        }
        return null;
    }

    /** What the log of one day said about a value, in words. */
    static String why(String kind, String subject, Inputs in, LocalDate day) {
        double planned = 0, worked = 0, units = 0;
        int sessions = 0, skipped = 0;
        for (Inputs.Session s : in.sessions) {
            if (!s.date().equals(day) || !s.goalWork()) continue;
            if (!subject.isEmpty() && !kind.equals("capacity") && !s.activity().equals(subject)) continue;
            if (s.planned() && s.plannedMin() != null) planned += s.plannedMin();
            if (s.skipped()) { skipped++; continue; }
            if (s.actualMin() != null) worked += s.actualMin();
            if (s.units() != null) units += s.units();
            sessions++;
        }
        String older = " (older days count a little less each day)";
        return switch (kind) {
            case "speed" -> sessions == 0 ? "no session that day: the trend carried the estimate on"
                : units > 0
                ? String.format(Locale.ROOT, "%d session(s): %.2f units in %.0f min, %.2f h each", sessions, units, worked, worked / 60 / units)
                : String.format(Locale.ROOT, "%d session(s): %.0f min worked against %.0f planned", sessions, worked, planned);
            case "adherence" -> planned > 0
                ? String.format(Locale.ROOT, "worked %.0f of %.0f planned min (%.0f%%)%s", Math.min(worked, planned), planned,
                    100 * Math.min(1, worked / planned), skipped > 0 ? ", " + skipped + " block(s) skipped" : "")
                : "nothing planned that day" + older;
            default -> String.format(Locale.ROOT, "%.1f h of goal work that day", worked / 60);
        };
    }
}
