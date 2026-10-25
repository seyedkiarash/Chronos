package com.chronos.navigator.forecast;

import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.model.Graph;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * How sure the forecast is of its own odds. The plain forecast mixes two kinds
 * of not knowing: what the log has not yet pinned down about you (how fast you
 * work, how much of the plan you keep, how many goal hours a kind of day
 * holds, how a stretch's days will fall) and what no log could tell (how big a
 * task turns out, how a given day goes). Here the first kind is drawn once per
 * "world" and the second many times inside it, so each world gives the odds of
 * a deadline if the learned values are what that world says. The spread of
 * those odds across worlds is the band: with few logs it is wide, and it
 * narrows as the log fills.
 *
 * The per-world odds come from a limited number of futures, whose own chance
 * variation would widen the band by itself; that part of the variance is
 * taken out (it is known: p(1 − p) / futures) before the band is read off a
 * normal curve. Same graph, inputs and seed: same band (principle 5).
 */
public final class Bands {
    private Bands() {}

    /** z for the 10th and 90th percentiles: the 80% band. */
    static final double Z80 = 1.2815515655446004;

    /**
     * @param p    the odds of meeting the deadline, all worlds together (the plain forecast's figure)
     * @param low  the 10th percentile of the odds across worlds
     * @param high the 90th percentile
     */
    public record Band(String id, double p, double low, double high) {
        public double width() { return high - low; }
    }

    public record Result(LocalDate asOf, long seed, int worlds, int futures, Map<String, Band> nodes) {}

    public static Result run(Graph g, Inputs in, LocalDate today, int worlds, int futures, long seed) {
        Model m = new Model(g, in, today, 0);
        Forecaster.Sim sim = new Forecaster.Sim(m);
        int[][] ok = new int[m.n][worlds];
        for (int w = 0; w < worlds; w++) {
            SplittableRandom world = new SplittableRandom(seed * 7_919L + w);
            long worldSeed = world.nextLong();
            for (int r = 0; r < futures; r++) {
                // The world's draws are replayed for every future inside it.
                SplittableRandom again = new SplittableRandom(worldSeed);
                SplittableRandom rnd = new SplittableRandom(seed * 1_000_003L + (long) w * futures + r + 1);
                double[] f = sim.run(again, rnd, null);
                for (int i = 0; i < m.n; i++)
                    if (m.deadline[i] != Model.NONE && f[i] <= m.deadline[i] + 1) ok[i][w]++;
            }
        }
        Map<String, Band> out = new LinkedHashMap<>();
        for (int i = 0; i < m.n; i++) {
            if (m.deadline[i] == Model.NONE) continue;
            out.put(m.nodes[i].id, band(m.nodes[i].id, ok[i], futures));
        }
        return new Result(today, seed, worlds, futures, out);
    }

    /** The band from each world's count of futures that met the deadline. */
    static Band band(String id, int[] ok, int futures) {
        int worlds = ok.length;
        double mean = 0;
        double[] p = new double[worlds];
        for (int w = 0; w < worlds; w++) { p[w] = ok[w] / (double) futures; mean += p[w]; }
        mean /= worlds;
        double var = 0, chance = 0;
        for (int w = 0; w < worlds; w++) {
            var += (p[w] - mean) * (p[w] - mean);
            chance += p[w] * (1 - p[w]) / futures;
        }
        var = worlds > 1 ? var / (worlds - 1) : 0;
        chance /= worlds;
        double sd = Math.sqrt(Math.max(0, var - chance));
        double low = Math.max(0, mean - Z80 * sd), high = Math.min(1, mean + Z80 * sd);
        return new Band(id, mean, low, high);
    }
}
