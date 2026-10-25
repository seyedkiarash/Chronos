package com.chronos.engine;

import com.chronos.model.Activity;

/**
 * Output within a single block: slow to start, then productive, then decaying.
 * The instantaneous rate is normalised so its peak is one, which makes
 * G(d) / d readable as the fraction of the block that was actually productive.
 *
 * Because the sequencer works on whole slots, this S shape never has to be
 * convex. It is only ever read as a lookup table.
 */
public final class YieldCurve {
    private YieldCurve() {}

    public static double[] table(Activity a) {
        int max = a.maxBlock == null ? 60 : a.maxBlock;
        double peak = 1e-6;
        for (int u = 1; u <= max; u++) peak = Math.max(peak, rate(a, u));
        double[] tab = new double[max + 1];
        double acc = 0;
        for (int u = 1; u <= max; u++) { acc += rate(a, u) / peak; tab[u] = acc; }
        return tab;
    }

    private static double rate(Activity a, int minute) {
        double warmUp = 1.0 / (1.0 + Math.exp(-a.k * (minute - a.u0)));
        double decay = Math.exp(-minute / a.theta);
        return warmUp * decay;
    }

    /** Effective minutes earned by a block of the given length in slots. */
    public static double at(double[] tab, int slots) {
        int minutes = Math.min(slots * Slots.SLOT_MIN, tab.length - 1);
        return tab[Math.max(0, minutes)];
    }
}
