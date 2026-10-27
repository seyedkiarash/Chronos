package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.chronos.ui.common.Lang;
import com.chronos.ui.common.StringsKt;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The translator, exercised on the shapes it has to handle: a plain sentence, a
 * sentence whose numbers vary, and one built around words the user typed.
 *
 * The point of the last case is that the user's own words come through
 * untouched — a goal called "Thesis" stays "Thesis" inside a Persian sentence.
 */
public class TranslationTest {

    private static String fa(String english) {
        return StringsKt.translate(Lang.Persian, english);
    }

    @Test
    public void englishIsReturnedUnchanged() {
        String s = "Close out this day";
        assertEquals(s, StringsKt.translate(Lang.English, s));
    }

    @Test
    public void aPlainSentenceIsTranslated() {
        assertTranslated("Close out this day");
        assertTranslated("Every deadline in the horizon is reachable");
        assertTranslated("The slack reserve is untouched.");
    }

    @Test
    public void numbersAreCarriedThrough() {
        assertEquals("روز ۳".length() > 0, true);
        String one = fa("Day 1"), twelve = fa("Day 12");
        System.out.println("  Day 1  -> " + one);
        System.out.println("  Day 12 -> " + twelve);
        assertTrue("one entry must cover both", !one.equals("Day 1"));
        assertTrue(one.contains("1") && twelve.contains("12"));
        assertTrue("and they must differ", !one.equals(twelve));
    }

    @Test
    public void theUsersOwnWordsSurviveATemplate() {
        String out = fa("Thesis literature review: deadline is not set");
        System.out.println("  " + out);
        assertTrue("the goal's name is not translated", out.contains("Thesis literature review"));
        assertTrue("the rest is", !out.contains("deadline is not set"));
    }

    @Test
    public void templatesWithSeveralHolesFillInOrder() {
        String out = fa("Move the deadline 3 days later, to 2026-10-01");
        System.out.println("  " + out);
        assertTrue(out.contains("3"));
        assertTrue(out.contains("2026-10-01"));
        assertTrue("and it really was translated", !out.contains("Move the deadline"));
    }

    @Test
    public void anUnknownSentenceFallsBackToEnglish() {
        String s = "Something nobody has written a translation for yet";
        assertEquals(s, fa(s));
    }

    /**
     * Persian digits in Persian text. The translator returns the substituted
     * sentence; the Text composable is what converts the digits, so that is
     * checked directly here.
     */
    @Test
    public void persianTextGetsPersianDigits() {
        assertEquals("۱۴۰۵", StringsKt.localiseDigits(Lang.Persian, "1405"));
        assertEquals("1405", StringsKt.localiseDigits(Lang.English, "1405"));
        assertEquals("شنبه", StringsKt.weekday(Lang.Persian, 0));
        assertEquals("Sat", StringsKt.weekday(Lang.English, 0));
    }

    private static void assertTranslated(String english) {
        String out = fa(english);
        assertTrue("no Persian for: " + english, !out.equals(english));
    }

    /** Every sentence the screens draw, so a missing translation is visible here. */
    @Test
    public void everyKnownScreenStringHasPersian() {
        List<String> missing = new ArrayList<>();
        for (String s : SCREEN_STRINGS) if (fa(s).equals(s)) missing.add(s);
        for (String s : missing) System.out.println("  MISSING: " + s);
        assertTrue(missing.size() + " strings have no Persian", missing.isEmpty());
    }

    /** A sample across every screen, including the runtime-composed shapes. */
    private static final String[] SCREEN_STRINGS = {
        "Horizon", "Day", "Priorities", "Setup", "Log", "Navigator",
        "Goal graph", "Import goal graph", "Missing data", "Answer this", "Parameters", "Phase",
        "Set", "Cancel", "Choose", "Not set", "Save", "Back", "Next", "Previous",
        "Body clock", "Scheduler", "Goals", "Activities", "Commitments", "Calendar",
        "Add goal", "New goal", "Remove this goal", "Hours per unit", "Ceiling per day (hours)",
        "Add activity", "New activity", "Remove this activity", "Goal work", "Upkeep",
        "Days it may happen", "Allowed hours", "Add a window", "Pin a day",
        "Add commitment", "New commitment", "Fixed sessions", "Travel around a session",
        "Add a session", "Load for this session", "Recovery for this session",
        "Getting there", "Coming back", "Pinned to the session", "Flexible inside a window",
        "Does not happen", "Your body clock", "Sleep starts", "Sleep ends", "Your sharpest hour",
        "Baseline capacity", "How the scheduler behaves", "Safe fatigue line", "Pessimism",
        "Slack kept free", "Planning horizon (days)", "If the plans feel wrong, start here",
        "Google Calendar", "Allow calendar access", "Phone-only calendar", "Send seven days",
        "Remove mine", "Reminder, minutes before", "Include meals, travel and upkeep",
        "Close out this day", "Rebuild", "No plan yet", "Past their deadline",
        "These deadlines cannot all be met", "Every deadline in the horizon is reachable",
        "Deadline check", "These goals do not finish", "Short by", "Can take",
        "Room per weekday", "What would fix it", "Same work", "Costs fatigue",
        "Changes the goal", "clears it", "Progress", "Where the days go", "Unallocated",
        "Capacity", "Fatigue", "Fixed", "Open", "Effective work", "Above the safe line",
        "unit-hours of overload", "Timetable", "About this gap", "Why this block is here",
        "Slot", "Reason", "Mean capacity", "Effective output", "Placement",
        "Relative priorities", "Not enough goals to compare", "Derived weights",
        "Use these weights", "Consistency", "Equally valuable per hour",
        "A session", "Energy check-in", "Travel time", "What did you do", "Time",
        "Planned minutes", "Actual minutes", "Target reached (%)", "Focus during the session",
        "How drained afterwards", "How restored afterwards", "How sharp do you feel right now",
        "Which trip", "Door to door (min)", "Recalibrate", "What changed", "History",
        "Saved.", "Something went wrong.", "Plan rebuilt.", "Choose a calendar first.",
        "Language",
        // composed at runtime
        "Day 4",
        "3 things still missing",
        "Thesis: hours per unit is not set",
        "Thesis: no weekday is selected",
        "Thesis has no activity that works on it",
        "Raise the daily ceiling for this goal from 6h to 7h",
        "Allow \"reading\" on Fri as well",
        "Shorten the shortest useful block of \"reading\" from 90 to 45 min",
        "Move the deadline 7 days later, to 2026-10-05",
        // the day sequencer's own words
        "2.5h",
        "Transition", "Open buffer",
        "commute (back) had to go outside its usual window.",
        "2.5h of reading did not fit today; those hours roll into the horizon.",
        "These goals do not finish in the arranged days", "Actually placed",
        "Planning horizon must be between 7 and 90 days",
        "Checked against the arranged days, not only against the hours that exist.",
        "Changeover before it (min)", "Changeover",
        "Held free to get into workout, which needs 15 minutes to change into.",
        "no free window was long enough for it plus the 15 minutes of changeover before it",
        "Only 30 minutes, shorter than the smallest block you allow (45 min), so nothing can go here.",
        "Held back deliberately: this is the slack reserve that absorbs overruns.",
        "no free window inside the allowed hours was long enough",
        "The calendar app fires these itself, so the phone needs no alarm permission. "
            + "Leave it empty for no reminder.",
        "free 12.5h · upkeep 3.5h · travel 2.5h · usable 5.9h",
        // the log's tags and the calibration report
        "Poor sleep", "Unwell", "Stressed", "Skipped a meal", "Noisy or interrupted",
        "Caffeine", "After exercise", "Late start",
        "three a day, spread out, is plenty",
        "needs at least 6 clean energy ratings, has 2. Still using your own estimates.",
        "quality 1.00 to 0.87, fatigue load 0.60 to 0.71 (4 sessions)",
        "recovery 0.30 to 0.42 (3 sessions)",
        "typical 45 to 52 min, variability 0.20 to 0.26 (5 trips)",
        "fitted on 14 rated moments; your sharpest hour moved to 09:30",
        "fitted on 14 rated moments; your sharpest hour moved to 09:30, ignoring 3 affected by "
            + "an outside cause",
        // already formatted by the time Text sees them
        "45 of 90 minutes",
        "load 0.60, recovery 0.10 per hour",
        "3 of 30 number of videos",
        // what gives way for deadlines
        "Cut it first", "Cut it as a last resort", "left out", "60 → 30 min",
        "reward is shortened to 30 min today to make room for a deadline.",
        "workout is left out today to make room for a deadline.",
        "class: a session's fatigue load is not set",
        "Empty or 0 drops it on that day. A workout that can shrink to a short run says 30.",
        // the day's log and the nightly close-out
        "Done 50 min · 80% of target · focus 4/5 · drained 3/5",
        "Plan rebuilt from the next day. Calendar updated: 12 blocks.",
        "progress 6.0 to 7.5 number of videos",
        "Planned: 75 min from 15:00. Count only the time you were actually on it.",
        // past days, what was done instead, sleep and windows
        "3 entries for days that have already closed are not in the model yet.",
        "Skipped · instead:", "What did you do instead?", "Add something you did",
        "80% of target · focus 4/5 · drained 3/5", "restored 4/5",
        "reward: the window 14:00–12:00 ends before it starts, so it is ignored until you fix it",
        "This window ends before it starts, so it is ignored until you fix it. To run until midnight, "
            + "end it at 00:00.",
        "Afternoon nap", "Sleep", "Put sleep in the calendar",
        // the trip and the export
        "What did you do on the way?", "commute — 60 min, drained 3/5",
        "fatigue load 0.10 to 0.14 (3 trips)", "Save everything to a file",
    };
}
