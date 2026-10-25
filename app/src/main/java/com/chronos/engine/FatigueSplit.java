package com.chronos.engine;

import com.chronos.model.Activity;
import com.chronos.model.Commitment;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.CommitmentKind;
import com.chronos.model.Params;

import java.util.List;
import java.util.Locale;

/**
 * Fatigue used to be one number per activity. Now it is two — mental and
 * physical — and everything set up before has to be split. This proposes a
 * split from what the activity is (its name and kind), keeping the old number
 * where it belongs; the proposal is used as it is until you confirm or change
 * it in Setup ({@link Activity#splitConfirmed}).
 *
 * The same proposal starts a new activity, so a new workout does not begin as
 * if it were reading.
 */
public final class FatigueSplit {
    private FatigueSplit() {}

    /** What an activity mostly is, read from its name. */
    public enum Sort { PHYSICAL, WALK, CHORES, MEAL_OR_PRAYER, NAP, DESK }

    private static final List<String> PHYSICAL = List.of("workout", "exercise", "gym", "sport", "run", "jog", "swim",
        "bike", "cycl", "football", "volleyball", "basketball", "climb", "ورزش", "باشگاه", "دویدن", "شنا", "دوچرخه", "فوتبال");
    private static final List<String> WALK = List.of("walk", "stroll", "hike", "پیاده", "قدم");
    private static final List<String> CHORES = List.of("chore", "house", "clean", "cook", "laundry", "shop", "errand",
        "dish", "کار خانه", "نظافت", "آشپز", "خرید", "شستن");
    private static final List<String> MEAL = List.of("meal", "breakfast", "lunch", "dinner", "eat", "snack", "pray",
        "reward", "break", "rest", "نماز", "صبحانه", "ناهار", "شام", "غذا", "استراحت", "جایزه");
    private static final List<String> NAP = List.of("nap", "sleep", "چرت", "خواب");

    public static Sort sortOf(String name, boolean nap) {
        if (nap) return Sort.NAP;
        String s = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (has(s, NAP)) return Sort.NAP;
        if (has(s, PHYSICAL)) return Sort.PHYSICAL;
        if (has(s, WALK)) return Sort.WALK;
        if (has(s, CHORES)) return Sort.CHORES;
        if (has(s, MEAL)) return Sort.MEAL_OR_PRAYER;
        return Sort.DESK;
    }

    private static boolean has(String s, List<String> words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    /** {mental load, mental recovery, physical load, physical recovery}, from the one load and recovery there were. */
    public static double[] suggest(String name, boolean nap, ActivityKind kind, Double load, Double recovery) {
        double l = load == null ? (kind == ActivityKind.GOAL ? 0.4 : 0.1) : load;
        double r = recovery == null ? (kind == ActivityKind.GOAL ? 0.0 : 0.3) : recovery;
        return switch (sortOf(name, nap)) {
            case PHYSICAL -> new double[]{0.05, Math.max(r, 0.25), Math.max(0.3, l * 0.9), 0.0};
            case WALK -> new double[]{0.02, Math.max(r, 0.3), Math.max(0.15, l * 0.6), 0.0};
            case CHORES -> new double[]{l * 0.4, r, Math.max(0.2, l * 0.7), 0.0};
            case MEAL_OR_PRAYER -> new double[]{l * 0.5, r, l * 0.2, r * 0.6};
            case NAP -> new double[]{0.0, Math.max(r, 0.5), 0.0, Math.max(r, 0.4)};
            case DESK -> new double[]{l, r, kind == ActivityKind.GOAL ? 0.05 : 0.08, kind == ActivityKind.GOAL ? 0.0 : r * 0.5};
        };
    }

    /** Fills a missing split with the proposal. Returns whether anything changed. */
    public static boolean fill(Activity a) {
        if (a.physLoad != null && a.physRecovery != null) return false;
        double[] s = suggest(a.name, a.nap, a.kind, a.load, a.recovery);
        a.load = s[0]; a.recovery = s[1]; a.physLoad = s[2]; a.physRecovery = s[3];
        a.splitConfirmed = false;
        return true;
    }

    /**
     * A trip tires both: some of its old load goes to the body. A class is
     * sitting and listening: mostly mental, a little physical.
     */
    public static boolean fill(Commitment c) {
        boolean changed = false;
        if (c.kind == CommitmentKind.VARIABLE && (c.physLoad == null || c.physRecovery == null)) {
            double l = c.load == null ? 0.3 : c.load, r = c.recovery == null ? 0 : c.recovery;
            c.load = l * 0.6; c.physLoad = l * 0.7; c.physRecovery = r * 0.5;
            changed = true;
        }
        for (Commitment.Session s : c.sessions) {
            if (s.load == null || s.physLoad != null) continue;
            s.physLoad = 0.08;
            s.physRecovery = 0.0;
            changed = true;
        }
        return changed;
    }

    /**
     * The new parameters, from the ones you set for mental fatigue. Stated
     * here, shown in Setup and refitted from the log like the rest.
     */
    public static boolean fill(Params p) {
        boolean changed = false;
        if (p.aP == null) { p.aP = p.aF == null ? 0.15 : p.aF; changed = true; }
        if (p.pTheta == null) { p.pTheta = 0.5; changed = true; }
        if (p.fSafeP == null) { p.fSafeP = p.fSafe; changed = true; }
        if (p.idleRecoveryP == null) { p.idleRecoveryP = p.idleRecovery; changed = true; }
        if (p.kMP == null) { p.kMP = 0.3; changed = true; }
        return changed;
    }

    /** The share of an activity's strain that is physical, for splitting a rating given as one number. */
    public static double physicalShare(Double load, Double physLoad) {
        double m = load == null ? 0 : load, ph = physLoad == null ? 0 : physLoad;
        return m + ph <= 1e-9 ? 0.5 : ph / (m + ph);
    }
}
