package com.chronos.navigator.estimate;

import com.chronos.navigator.model.Kinds.DayType;

import java.util.List;
import java.util.Set;

/**
 * The suggestion shown ticked at the evening check-in, which the user
 * confirms or changes with one tap. A plain rule, so it can be argued with:
 *
 *   STRESS — the day felt heavy (4 or 5 of 5), or a sign of strain was noted;
 *   BUSY   — the day felt middling (3 of 5), or four hours or more were fixed
 *            (classes, work) before anything of your own;
 *   IDEAL  — otherwise.
 */
public final class DayTypes {
    private DayTypes() {}

    /** Evening and morning signs that mark a strained day. */
    public static final Set<String> STRAIN = Set.of("Overwhelmed", "Emotionally drained", "Mentally exhausted",
        "Anxious or stressed", "Feeling unwell", "Stressed", "Unwell", "Anxious", "Upset or angry", "Bad news");

    public static final double BUSY_FIXED_HOURS = 4;

    /** @param heavy how heavy the day felt, 1..5, or null if not answered */
    public static DayType suggest(Integer heavy, List<String> signs, double fixedHours) {
        boolean strained = signs != null && signs.stream().anyMatch(STRAIN::contains);
        if ((heavy != null && heavy >= 4) || strained) return DayType.STRESS;
        if ((heavy != null && heavy == 3) || fixedHours >= BUSY_FIXED_HOURS) return DayType.BUSY;
        return DayType.IDEAL;
    }
}
