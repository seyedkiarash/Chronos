package com.chronos.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether today should be a rest day.
 *
 * Built from what the morning check-in says, what last night's said, how heavy
 * the week has been against a normal one, and whether the last few mornings
 * have been sliding. Each contributes points and a sentence saying why, so the
 * verdict is never a bare number. Seven or more points is a clear case; four
 * to six is worth considering; below that the plan stands.
 *
 * The thresholds are deliberately conservative: a rest day costs a day of goal
 * work, so one bad sign alone never triggers it, but a truly bad morning — no
 * energy and feeling unwell or wrung out — does on its own.
 */
public final class RestAdvisor {
    private RestAdvisor() {}

    /** Signs that weigh heavily: each of these alone is close to a reason to stop. */
    public static final List<String> SEVERE = List.of(
        "Woke up exhausted", "Feeling unwell", "Emotionally drained", "No motivation at all",
        "Something happened");

    /** Signs that count, but need company. */
    public static final List<String> MILD = List.of(
        "Slept badly", "Body aches or headache", "Low mood", "Anxious or stressed");

    /** What the evening check-in can say. */
    public static final List<String> EVENING_SIGNS = List.of(
        "Overwhelmed", "Emotionally drained", "Mentally exhausted", "Anxious or stressed", "Feeling unwell");

    public static final int CLEAR = 7, CONSIDER = 4;

    public enum Level { NORMAL, CONSIDER, REST }

    public record Reason(int points, String why) {}

    public record Verdict(Level level, int points, List<Reason> reasons) {}

    /**
     * @param morningEnergy  1..5 from this morning, or null if not answered
     * @param morningSigns   ticked signs this morning
     * @param eveningEnergy  1..5 from last night, or null
     * @param eveningHeavy   how heavy yesterday felt, 1..5, or null
     * @param eveningSigns   ticked signs last night
     * @param weekRatio      this week's load against a normal week, or null
     * @param recentMornings morning energies of the days before today, newest first
     */
    public static Verdict assess(Integer morningEnergy, List<String> morningSigns,
                                 Integer eveningEnergy, Integer eveningHeavy, List<String> eveningSigns,
                                 Double weekRatio, List<Integer> recentMornings) {
        List<Reason> reasons = new ArrayList<>();
        if (morningEnergy != null) {
            if (morningEnergy <= 1) reasons.add(new Reason(4, "you woke with almost no energy"));
            else if (morningEnergy == 2) reasons.add(new Reason(2, "your energy this morning is low"));
        }
        for (String s : morningSigns) {
            if (SEVERE.contains(s)) reasons.add(new Reason(3, "this morning: " + s));
            else if (MILD.contains(s)) reasons.add(new Reason(1, "this morning: " + s));
        }
        if (eveningEnergy != null && eveningEnergy <= 2)
            reasons.add(new Reason(1, "you ended yesterday with little energy left"));
        if (eveningHeavy != null && eveningHeavy >= 4)
            reasons.add(new Reason(1, "yesterday felt heavy"));
        int eveningSevere = 0;
        for (String s : eveningSigns) if (eveningSevere < 2) { eveningSevere++; reasons.add(new Reason(1, "last night: " + s)); }
        if (weekRatio != null) {
            if (weekRatio >= 1.5)
                reasons.add(new Reason(3, String.format("this week's load is %.0f%% of a normal week", weekRatio * 100)));
            else if (weekRatio >= 1.3)
                reasons.add(new Reason(2, String.format("this week's load is %.0f%% of a normal week", weekRatio * 100)));
        }
        if (recentMornings.size() >= 3) {
            double avg = 0;
            for (int i = 0; i < 3; i++) avg += recentMornings.get(i);
            if (avg / 3 <= 2.34) reasons.add(new Reason(2, "your mornings have been low for three days running"));
        }
        int points = 0;
        for (Reason r : reasons) points += r.points();
        Level level = points >= CLEAR ? Level.REST : points >= CONSIDER ? Level.CONSIDER : Level.NORMAL;
        return new Verdict(level, points, reasons);
    }

    /**
     * How much of a normal day's capacity the days after a rest get, gentlest
     * first: a day at about 60%, one at 80%, then back to full. The planner
     * tries these in order and keeps the gentlest that costs no deadline.
     */
    public static final double[][] RAMPS = {{0.6, 0.8}, {0.8, 0.9}, {1.0, 1.0}};
}
