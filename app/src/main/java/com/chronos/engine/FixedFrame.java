package com.chronos.engine;

import com.chronos.model.Activity;
import com.chronos.model.Block;
import com.chronos.model.Commitment;
import com.chronos.model.Config;
import com.chronos.model.Enums.CommitmentKind;
import com.chronos.model.Enums.LegMode;
import com.chronos.model.Params;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.chronos.engine.Slots.PER_DAY;

/**
 * Sleep plus the commitments whose times are already decided. Travel is
 * deliberately left out: where it can sit depends on what else ends up away
 * from home that day, which is not known until the sequencer has run.
 */
public final class FixedFrame {
    private FixedFrame() {}

    public record Frame(String[] occupant, List<Block> items) {
        public int freeSlots() {
            int n = 0;
            for (String s : occupant) if (s == null) n++;
            return n;
        }
    }

    public static Frame of(Params p, List<Commitment> commitments, int dow) {
        String[] occ = new String[PER_DAY];
        List<Block> items = new ArrayList<>();
        int s0 = Slots.parse(p.sleepStart), s1 = Slots.parse(p.sleepEnd);
        for (int t = 0; t < PER_DAY; t++) if (Energy.isAsleep(t, s0, s1)) occ[t] = "Sleep";

        for (Commitment c : commitments) {
            if (c.kind != CommitmentKind.FIXED) continue;
            for (Commitment.Session s : c.sessions) {
                if (s.day != dow) continue;
                int a = Slots.parse(s.start), b = Slots.parse(s.end);
                if (b <= a) continue;
                for (int t = a; t < b && t < PER_DAY; t++) occ[t] = c.name;
                Block blk = new Block();
                blk.id = UUID.randomUUID().toString();
                blk.name = c.name;
                blk.start = a;
                blk.dur = b - a;
                blk.load = s.loadOr(c.load);
                blk.recovery = s.recoveryOr(c.recovery);
                blk.physLoad = s.physLoadOr(c.physLoad);
                blk.physRecovery = s.physRecoveryOr(c.physRecovery);
                blk.fixed = true;
                blk.away = c.away;
                items.add(blk);
            }
        }
        return new Frame(occ, items);
    }

    /** Planned one-way travel time in slots, taken at the chosen quantile. */
    public static int tripSlots(Commitment c) {
        double minutes = logNormalQuantile(c.medianMin, c.sigma, c.quantile);
        return Math.max(1, (int) Math.round(minutes / Slots.SLOT_MIN));
    }

    public static boolean hasAwayWork(Config cfg, int dow) {
        for (Commitment c : cfg.commitments)
            if (c.kind == CommitmentKind.FIXED && c.away)
                for (Commitment.Session s : c.sessions) if (s.day == dow) return true;
        for (Activity a : cfg.activities) if (a.away && a.days.contains(dow)) return true;
        return false;
    }

    /** Minutes of travel this weekday costs, for the capacity model. */
    public static int travelMinutes(Config cfg, int dow) {
        if (!hasAwayWork(cfg, dow)) return 0;
        int total = 0;
        for (Commitment c : cfg.commitments) {
            if (c.kind != CommitmentKind.VARIABLE) continue;
            int legs = 0;
            if (c.outbound != null && c.outbound.mode != LegMode.NONE) legs++;
            if (c.inbound != null && c.inbound.mode != LegMode.NONE) legs++;
            total += legs * tripSlots(c) * Slots.SLOT_MIN;
        }
        return total;
    }

    /** Deterministic equivalent of a chance constraint on arriving on time. */
    public static double logNormalQuantile(double median, double sigma, double q) {
        double z = Math.sqrt(2) * inverseErf(2 * Slots.clamp(q, 0.51, 0.99) - 1);
        return median * Math.exp(sigma * z);
    }

    private static double inverseErf(double x) {
        double a = 0.147;
        double ln = Math.log(1 - x * x);
        double t1 = 2 / (Math.PI * a) + ln / 2;
        return Math.signum(x) * Math.sqrt(Math.sqrt(t1 * t1 - ln / a) - t1);
    }
}
