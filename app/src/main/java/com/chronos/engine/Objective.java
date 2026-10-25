package com.chronos.engine;

import com.chronos.model.Block;
import com.chronos.model.Params;

import java.util.List;
import java.util.Map;

/**
 * What a day is worth. Value bought by goal work, minus the fatigue carried
 * above the safe line, minus the cost of starting each separate session.
 */
public final class Objective {
    private Objective() {}

    /** @param fatigue mental fatigue, slot by slot; @param physical physical fatigue */
    public record Score(double z, double value, double excess, int switches,
                        double[] fatigue, double[] physical, double[] capacity, double wastedRecoveryH,
                        double[] dopamine) {
        public Score(double z, double value, double excess, int switches,
                     double[] fatigue, double[] physical, double[] capacity, double wastedRecoveryH) {
            this(z, value, excess, switches, fatigue, physical, capacity, wastedRecoveryH, new double[fatigue.length]);
        }
    }

    /**
     * What an hour of restoration spent on an empty reservoir costs, in the
     * minutes the value term is measured in.
     *
     * Rest is worth something because it raises the capacity of the hours that
     * follow. Rest taken before anything has been done raises nothing: the
     * reservoir is already empty and the offer is discarded. Without this the
     * objective is indifferent about where a restorative block goes, so a walk
     * or a long breakfast lands in the morning — the best hours of the day —
     * and buys nothing with them. The charge is deliberately modest: it is
     * enough to break the tie, not enough to outrank the fatigue penalty and
     * start pushing meals around a day that genuinely needs them.
     */
    private static final double WASTED_RECOVERY_PENALTY = 20;

    public static Score score(List<Block> blocks, Params p, double[] h, Map<String, Double> weights) {
        Energy.Trace trace = Energy.simulate(blocks, p, h);
        double value = 0;
        int switches = 0;
        for (Block b : blocks) {
            if (b.goalId == null || b.start < 0) continue;
            double e = 0;
            for (int t = b.start; t < b.start + b.dur; t++) e += trace.capacity()[Slots.wrap(t)];
            b.meanEta = e / b.dur;
            b.yieldMin = YieldCurve.at(b.tab, b.dur) * b.meanEta * b.qPlan;
            value += weights.getOrDefault(b.goalId, 0.1) * b.yieldMin;
            switches++;
        }
        // Fatigue carried above each safe line, mental and physical alike.
        double excess = 0;
        for (double f : trace.fatigue()) excess += Math.max(0, f - p.fSafe);
        double safeP = p.fSafeP == null ? p.fSafe : p.fSafeP;
        for (double f : trace.physical()) excess += Math.max(0, f - safeP);
        excess *= Slots.SLOT_MIN / 60.0;

        double z = value - p.fatiguePenalty * 60 * excess - p.switchPenalty * 60 * switches
                 - WASTED_RECOVERY_PENALTY * trace.wastedRecoveryH();
        return new Score(z, value, excess, switches, trace.fatigue(), trace.physical(), trace.capacity(),
                         trace.wastedRecoveryH(), trace.dopamine());
    }
}
