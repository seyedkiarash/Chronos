package com.chronos.engine;

import com.chronos.model.Block;
import com.chronos.model.Params;

import java.util.List;

import static com.chronos.engine.Slots.PER_DAY;
import static com.chronos.engine.Slots.SLOT_MIN;
import static com.chronos.engine.Slots.clamp;

/**
 * The two-process sleep model plus two reservoirs for the fatigue that what
 * you do creates: mental (M) and physical (P). Capacity — what the scheduler
 * is really spending on goal work — falls with mental fatigue, and with
 * physical fatigue once the body passes a threshold:
 *
 *   capacity = a0 + aC·circadian − aH·sleep pressure − aF·M − aP·max(0, P − θ)
 *
 * Each block loads and restores the two separately (a workout tires the body
 * and rests the mind; studying the reverse), and a tired mind makes physical
 * effort cost more: physical load × (1 + kMP·M).
 */
public final class Energy {
    private Energy() {}

    /** The fatigue and capacity traces of one day. */
    /**
     * @param wastedRecoveryH restoration offered by a placed block that the
     *                        reservoir could not take, because it was already
     *                        empty. An hour of rest before any work has been
     *                        done relieves nothing — there is no fatigue there
     *                        to relieve — and this is how much of it was spent
     *                        that way. Idle time is not counted: doing nothing
     *                        when there is nothing to recover from costs
     *                        nothing, whereas a scheduled block costs the hour.
     */
    /** @param dopamine stimulation, slot by slot: what leisure added and has not yet faded */
    public record Trace(double[] fatigue, double[] physical, double[] capacity, double wastedRecoveryH,
                        double[] dopamine) {
        public Trace(double[] fatigue, double[] physical, double[] capacity, double wastedRecoveryH) {
            this(fatigue, physical, capacity, wastedRecoveryH, new double[fatigue.length]);
        }
        /** The highest stimulation the day reaches. */
        public double peakDopamine() {
            double m = 0;
            for (double d : dopamine) m = Math.max(m, d);
            return m;
        }
    }

    private static double nz(Double v, double fallback) { return v == null ? fallback : v; }

    /** Capacity for goal work at a slot, given sleep pressure and the two fatigues. */
    public static double capacity(Params p, int slot, double h, double mental, double physical) {
        return capacity(p, slot, h, mental, physical, 0);
    }

    /**
     * With stimulation: what leisure left behind dulls goal work while it lasts — the after-effect
     * the log asks about ("how hard was it to get into the next thing").
     */
    public static double capacity(Params p, int slot, double h, double mental, double physical, double dopamine) {
        double over = Math.max(0, physical - nz(p.pTheta, 1));
        return clamp(p.a0 + p.aC * circadian(slot, p.phi) - p.aH * h - p.aF * mental - nz(p.aP, 0) * over
                     - p.aDOr() * dopamine, 0, 1);
    }

    /**
     * Stimulation after one slot: leisure adds its rate, and it fades with a half-life — twice as
     * fast while goal work uses it up, and gone by morning.
     */
    public static double nextDopamine(Params p, double d, double rate, boolean goalWork, boolean asleep) {
        if (asleep) return d * Math.pow(0.5, SLOT_MIN / 60.0 / 0.5);
        double half = p.dopHalfLifeOr() * (goalWork ? 0.5 : 1.0);
        return d * Math.pow(0.5, SLOT_MIN / 60.0 / half) + rate * SLOT_MIN / 60.0;
    }

    /** Circadian drive, normalised to roughly [-1, 1]. The second harmonic
     *  gives the curve a shoulder rather than a pure cosine. */
    public static double circadian(int slot, int phiMinutes) {
        double m = slot * SLOT_MIN;
        return (Math.cos(2 * Math.PI * (m - phiMinutes) / 1440.0)
              + 0.2 * Math.cos(4 * Math.PI * (m - phiMinutes) / 1440.0)) / 1.2;
    }

    public static boolean isAsleep(int slot, int sleepStart, int sleepEnd) {
        return sleepStart > sleepEnd
            ? (slot >= sleepStart || slot < sleepEnd)
            : (slot >= sleepStart && slot < sleepEnd);
    }

    /** Sleep pressure over a day, iterated to a periodic steady state. */
    public static double[] homeostatic(Params p) {
        int s0 = Slots.parse(p.sleepStart), s1 = Slots.parse(p.sleepEnd);
        double[] out = new double[PER_DAY];
        double h = 0.5;
        for (int cycle = 0; cycle < 5; cycle++) {
            for (int t = 0; t < PER_DAY; t++) {
                out[t] = h;
                h = isAsleep(t, s0, s1)
                    ? h * Math.exp(-SLOT_MIN / (p.tauD * 60))
                    : 1 - (1 - h) * Math.exp(-SLOT_MIN / (p.tauR * 60));
                h = clamp(h, 0, 1);
            }
        }
        return out;
    }

    /**
     * Walk the day forward. Unoccupied waking time counts as passive rest:
     * without that the fatigue integral only ever grows, and the objective
     * quietly pushes every block toward bedtime to shorten the window over
     * which it is charged.
     */
    public static Trace simulate(List<Block> blocks, Params p, double[] h) {
        int s0 = Slots.parse(p.sleepStart), s1 = Slots.parse(p.sleepEnd);
        double[] load = new double[PER_DAY], rec = new double[PER_DAY];
        double[] pLoad = new double[PER_DAY], pRec = new double[PER_DAY];
        boolean[] busy = new boolean[PER_DAY], goal = new boolean[PER_DAY];
        double[] dRate = new double[PER_DAY];
        boolean[] fun = new boolean[PER_DAY];
        for (Block b : blocks) {
            if (b.start < 0) continue;
            for (int t = b.start; t < b.start + b.dur; t++) {
                int i = Slots.wrap(t);
                load[i] = b.load; rec[i] = b.recovery; busy[i] = true;
                pLoad[i] = b.physLoad; pRec[i] = b.physRecovery;
                goal[i] = b.goalId != null;
                dRate[i] = b.leisure ? b.dopamine : 0;
                fun[i] = b.leisure;
            }
        }
        double idleP = nz(p.idleRecoveryP, p.idleRecovery), kMP = nz(p.kMP, 0);
        double[] fatigue = new double[PER_DAY], physical = new double[PER_DAY], capacity = new double[PER_DAY];
        double[] dopamine = new double[PER_DAY];
        double f = 0.15, ph = 0.10, d = 0;
        double wasted = 0;
        for (int t = 0; t < PER_DAY; t++) {
            boolean asleep = isAsleep(t, s0, s1);
            fatigue[t] = f;
            physical[t] = ph;
            dopamine[t] = d;
            capacity[t] = asleep ? 0 : capacity(p, t, h[t], f, ph, d);
            d = nextDopamine(p, d, dRate[t], goal[t], asleep);
            double restore = asleep ? 1.0 : (busy[t] ? rec[t] : p.idleRecovery);
            double raw = f + (SLOT_MIN / 60.0) * (load[t] - restore);
            double next = clamp(raw, 0, p.fMax);
            double pRestore = asleep ? 1.0 : (busy[t] ? pRec[t] : idleP);
            double pRaw = ph + (SLOT_MIN / 60.0) * (pLoad[t] * (1 + kMP * f) - pRestore);
            double pNext = clamp(pRaw, 0, p.fMax);
            // Everything the clamp at zero threw away, while a block was
            // running, is restoration the day did not need.
            // Leisure is not there to relieve fatigue; an evening film on a calm day wastes nothing.
            if (!asleep && busy[t] && !fun[t] && raw < 0) wasted += next - raw;
            if (!asleep && busy[t] && !fun[t] && pRaw < 0) wasted += pNext - pRaw;
            f = next;
            ph = pNext;
        }
        return new Trace(fatigue, physical, capacity, wasted, dopamine);
    }
}
