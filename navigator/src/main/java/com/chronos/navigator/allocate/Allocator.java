package com.chronos.navigator.allocate;

import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.forecast.Probe;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Param;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The weekly allocation layer (spec, "Weekly allocation"): how many hours each
 * node gets in the coming week. Greedy: each 2-hour chunk goes to the node
 * where it raises the objective most,
 *
 *   J = Σₘ wₘ·Pₘ(deadline) + ε·Σᵢ vᵢ·Δprogressᵢ,   ε = 0.1·(6 − risk)/5,
 *
 * over the deadlines within 26 weeks, wₘ = owner authority × value, vᵢ the
 * standalone value. Each candidate is scored by a small Monte Carlo with
 * common random numbers, so differences are not drowned in noise.
 *
 *  1. the week's capacity: expected goal hours of its days × mean adherence;
 *  2. what cannot move comes first — the floors of streams and skills, and
 *     the hours an overlay (a sprint) planned for its nodes;
 *  3. the rest in chunks, to the best gain each time, within each node's
 *     daily cap and the work it has left, paused support work excluded;
 *  4. when the best gain falls under a small threshold the rest stays free:
 *     rest is part of the answer, not a failure of it.
 */
public final class Allocator {
    private Allocator() {}

    /** One node's hours and why, in numbers. */
    public record Line(String nodeId, String title, double hours, String reason, boolean fixed) {}

    /** @param overH how far the overlay's own plan goes past the week's expected hours; 0 when it fits */
    public record Result(LocalDate weekStart, double capacityH, double freeH, double overH, List<Line> lines,
                         double objective, long millis) {}

    public static Result run(Graph g, Inputs in, LocalDate today, LocalDate weekStart, long seed) {
        long t0 = System.nanoTime();
        int runs = (int) value(g, "allocate.runs");
        double chunk = value(g, "allocate.chunk_h");
        double minGain = value(g, "allocate.min_gain");
        int weeks = (int) value(g, "allocate.horizon_weeks");
        double risk = g.riskTolerance == null ? value(g, "risk_tolerance") : g.riskTolerance;
        double eps = 0.1 * (6 - risk) / 5;

        Probe probe = new Probe(g, in, today, weekStart, weeks, runs, seed);
        int n = probe.size();
        double[] alloc = new double[n];
        String[] why = new String[n];
        boolean[] fixed = new boolean[n];

        double capacity = probe.expectedCapacity();
        double left = capacity;

        // 2. what cannot move: first an overlay's own plan, which you set and which is kept whole …
        double over = 0;
        for (int i = 0; i < n; i++) {
            if (!probe.eligible(i)) continue;
            double planned = probe.overlayHours(i);
            if (planned <= 0) continue;
            alloc[i] = planned;
            fixed[i] = true;
            why[i] = "planned during " + probe.overlayName(i);
            left -= planned;
        }
        if (left < 0) { over = -left; left = 0; }
        // … then the floors of the days the overlay does not plan, cut to fit when the week is short.
        double floors = 0;
        double[] floor = new double[n];
        for (int i = 0; i < n; i++) {
            if (!probe.eligible(i)) continue;
            floor[i] = Math.max(0, Math.min(probe.floorHours(i), probe.cap(i) - alloc[i]));
            floors += floor[i];
        }
        double shrink = floors > left && floors > 0 ? left / floors : 1;
        for (int i = 0; i < n; i++) {
            if (floor[i] <= 0) continue;
            alloc[i] += floor[i] * shrink;
            fixed[i] = true;
            String f = shrink < 1 ? "weekly floor, cut to fit the week" : "weekly floor";
            why[i] = why[i] == null ? f : why[i] + "; " + f;
        }
        left -= floors * shrink;

        // 3. the rest, chunk by chunk
        double[] base = probe.score(alloc);
        double objective = objective(probe, base, alloc, eps);
        Map<Integer, double[]> lastGain = new LinkedHashMap<>();
        while (left >= chunk / 2) {
            // Each node is tried with one chunk, and — because a deadline's odds move in steps, not
            // smoothly — also with the hours a week that would finish it by its deadline. The best
            // gain per hour wins.
            int best = -1;
            double bestRate = 0, bestGain = 0, bestGive = 0;
            double[] bestScore = null;
            for (int i = 0; i < n; i++) {
                if (!probe.eligible(i) || probe.pausedAllWeek(i)) continue;
                double room = Math.min(left, probe.cap(i) - alloc[i]);
                if (probe.finite(i)) room = Math.min(room, probe.workLeft(i) - alloc[i]);
                if (room < chunk / 4) continue;
                java.util.TreeSet<Double> sizes = new java.util.TreeSet<>();
                sizes.add(Math.min(chunk, room));
                double toGo = probe.weeksToDeadline(i);
                if (probe.finite(i) && toGo > 0) {
                    double pace = Math.ceil((probe.workLeft(i) / toGo - alloc[i]) / chunk) * chunk;
                    if (pace > chunk) sizes.add(Math.min(pace, room));
                }
                for (double give : sizes) {
                    alloc[i] += give;
                    double[] sc = probe.score(alloc);
                    double gain = objective(probe, sc, alloc, eps) - objective;
                    alloc[i] -= give;
                    double rate = gain / give;
                    if (gain >= minGain * Math.max(1, give / chunk) && rate > bestRate) {
                        bestRate = rate; bestGain = gain; best = i; bestScore = sc; bestGive = give;
                    }
                }
            }
            if (best < 0) break;
            alloc[best] += bestGive;
            lastGain.merge(best, diff(bestScore, base), Allocator::add);
            base = bestScore;
            objective += bestGain;
            left -= bestGive;
        }

        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (alloc[i] <= 0.01) continue;
            String reason = why[i];
            double[] gain = lastGain.get(i);
            if (gain != null) {
                int m = argmax(gain);
                String more = m < 0 || gain[m] <= 0 ? "progress on its own value"
                    : String.format(java.util.Locale.ROOT, "+%.0f%% to P(%s)", 100 * gain[m], probe.title(probe.target(m)));
                reason = reason == null ? more : reason + "; " + more;
            }
            Node nd = probe.node(i);
            lines.add(new Line(nd.id, nd.title, Math.round(alloc[i] * 4) / 4.0, reason, fixed[i] && gain == null));
        }
        lines.sort((a, b) -> Double.compare(b.hours(), a.hours()));
        return new Result(weekStart, capacity, Math.max(0, left), over, lines, objective, (System.nanoTime() - t0) / 1_000_000);
    }

    /** J: weighted deadline probabilities plus a little for progress worth having in itself. */
    static double objective(Probe probe, double[] p, double[] alloc, double eps) {
        double j = 0;
        for (int k = 0; k < p.length; k++) j += probe.targetWeight(k) * p[k];
        double progress = 0;
        for (int i = 0; i < alloc.length; i++)
            if (alloc[i] > 0 && probe.workLeft(i) > 0 && probe.finite(i))
                progress += probe.value(i) * Math.min(1, alloc[i] / probe.workLeft(i));
        return j + eps * progress;
    }

    private static double[] diff(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int k = 0; k < a.length; k++) out[k] = a[k] - b[k];
        return out;
    }

    private static double[] add(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int k = 0; k < a.length; k++) out[k] = a[k] + b[k];
        return out;
    }

    private static int argmax(double[] a) {
        int best = -1;
        for (int k = 0; k < a.length; k++) if (best < 0 || a[k] > a[best]) best = k;
        return best;
    }

    static double value(Graph g, String key) {
        Param p = g.params.get(key);
        if (p == null || p.get("value") == null) throw new IllegalStateException("Parameter " + key + " has no value");
        return p.get("value");
    }

    /** The week the allocation is for: from the coming Saturday when run on Thursday or Friday, else from tomorrow. */
    public static LocalDate weekStart(LocalDate today) {
        java.time.DayOfWeek d = today.getDayOfWeek();
        if (d == java.time.DayOfWeek.THURSDAY) return today.plusDays(2);
        if (d == java.time.DayOfWeek.FRIDAY) return today.plusDays(1);
        return today.plusDays(1);
    }
}
