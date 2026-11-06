package com.chronos.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * How much of a heavy day to keep (your idea of 7 Nov 2026): not all of it and not nothing. You say how
 * heavy the day is; what the log shows moves that a little; and what is due in the next days keeps its
 * time unless you say the day is very heavy. The plan then fills the smaller day with deadlines first,
 * so what gives way is the work with no date. The app suggests; you choose.
 */
public final class LightDay {
    private LightDay() {}

    public static final int A_LITTLE = 1, HEAVY = 2, VERY = 3;

    /**
     * @param keepPercent what is suggested, in tens
     * @param firmPercent what the day's near deadlines alone take
     * @param reasons     why, as sentences to show
     */
    public record Suggestion(int keepPercent, int firmPercent, List<String> reasons) {}

    /**
     * @param morningEnergy this morning's 1..5, or null
     * @param sleptMin      last night's minutes asleep, or null
     * @param usualSleepMin the usual night, or null
     * @param energyToday   today's mean hourly rating so far, or null
     * @param energyUsual   the usual mean rating, or null
     * @param restPoints    how strong the rest advisor's case is today
     * @param plannedH      goal hours planned for today
     * @param firmH         of those, hours for what is due within a few days
     */
    public static Suggestion suggest(int level, Integer morningEnergy, Integer sleptMin, Integer usualSleepMin,
                                     Double energyToday, Double energyUsual, int restPoints, double plannedH, double firmH) {
        int keep = level == A_LITTLE ? 80 : level == HEAVY ? 60 : 40;
        List<String> why = new ArrayList<>();
        if (sleptMin != null && usualSleepMin != null && sleptMin < usualSleepMin - 60) {
            keep -= 10; why.add("Last night was shorter than usual.");
        }
        if (morningEnergy != null && morningEnergy <= 2) {
            keep -= 10; why.add("Your energy was low this morning.");
        }
        if (energyToday != null && energyUsual != null && energyToday < energyUsual - 0.7) {
            keep -= 10; why.add("Your hours today are rated below your usual.");
        }
        if (restPoints >= RestAdvisor.CONSIDER) {
            keep -= 10; why.add("The last days already make a case for rest.");
        }
        keep = Math.max(20, Math.min(90, keep));
        int firm = plannedH <= 0 ? 0 : (int) Math.min(100, Math.ceil(firmH / plannedH * 100));
        if (level != VERY && firm > keep) {
            keep = Math.min(100, (int) Math.ceil(firm / 10.0) * 10);
            why.add("What is due in the next days needs this much.");
        } else if (level == VERY && firm > keep) {
            why.add("Even so, this leaves less than what is due in the next days needs.");
        }
        return new Suggestion(keep, firm, why);
    }
}
