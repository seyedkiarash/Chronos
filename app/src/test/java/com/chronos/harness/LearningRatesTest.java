package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.Calibrator;
import com.chronos.engine.GoalCap;
import com.chronos.engine.HorizonPlanner;
import com.chronos.engine.Similar;
import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.LogEntry;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Learning that slows as it learns, a new activity's start from its likes, and a plan no bigger than you (8 Nov 2026). */
public class LearningRatesTest {

    // ------------------------------------------------------------ item 4

    @Test
    public void aNewQualityMovesFastAndASettledOneSlowly() {
        double[] fresh = Calibrator.qStep(1.0, 0.35, 0.6, 1);
        double[] settled = Calibrator.qStep(1.0, 0.08, 0.95, 1);
        double freshStep = (1.0 - fresh[0]) / 0.4, settledStep = (1.0 - settled[0]) / 0.05;
        assertTrue("fresh moves " + freshStep, freshStep > 0.4);
        assertTrue("settled moves " + settledStep, settledStep < 0.1);
        assertTrue("each reading makes it surer", fresh[1] < 0.35);
    }

    @Test
    public void aSurpriseWidensTheUncertaintySoAChangedHabitIsFollowed() {
        double[] usual = Calibrator.qStep(1.0, 0.08, 0.95, 1);
        double[] surprise = Calibrator.qStep(1.0, 0.08, 0.4, 1);
        double usualStep = (1.0 - usual[0]) / 0.05, surpriseStep = (1.0 - surprise[0]) / 0.6;
        assertTrue("a day far off moves it further, proportionally: " + surpriseStep + " vs " + usualStep,
            surpriseStep > 2 * usualStep);
        assertTrue(surprise[1] > usual[1]);
    }

    @Test
    public void moreDaysOfRatingsMeanSmallerSteps() {
        assertEquals(1 / 3.0, Calibrator.gain(Map.of(), "a:x"), 1e-9);
        assertEquals(0.125, Calibrator.gain(Map.of("a:x", 5), "a:x"), 1e-9);
        assertEquals(0.08, Calibrator.gain(Map.of("a:x", 50), "a:x"), 1e-9);
    }

    @Test
    public void ratedDaysAreCountedOncePerDay() {
        LocalDate d = LocalDate.of(2026, 11, 1);
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LogEntry e = session("papers", d.plusDays(i / 2), 60, 60);   // two on the first day, one on the next
            e.mentalAfter = 3;
            logs.add(e);
        }
        assertEquals(2, (int) Calibrator.seen(logs).get("a:papers"));
    }

    // ------------------------------------------------------------ item 5

    @Test
    public void aNewCourseStartsFromTheCoursesLikeIt() {
        Config cfg = ParityHarness.build(LocalDate.of(2026, 11, 3));
        Activity papers = cfg.activities.stream().filter(a -> a.id.equals("papers")).findFirst().orElseThrow();
        papers.name = "Reading course papers"; papers.q = 0.6; papers.qSigma = 0.1;
        Activity other = cfg.activities.stream().filter(a -> a.id.equals("english")).findFirst().orElseThrow();
        other.q = 1.4; other.qSigma = 0.1;
        Activity fresh = new Activity();
        fresh.id = "new"; fresh.name = "Reading course notes"; fresh.kind = ActivityKind.GOAL;
        cfg.activities.add(fresh);
        List<String> from = Similar.seed(fresh, cfg);
        assertTrue(from.contains("Reading course papers"));
        assertTrue("closer to the reading it shares words with: " + fresh.q, fresh.q < 1.0);
        assertTrue("still unsure: " + fresh.qSigma, fresh.qSigma >= 0.15);
        assertNotNull("a fatigue figure not given starts at theirs", fresh.load);
    }

    @Test
    public void withNothingLearnedTheDefaultsStay() {
        Config cfg = ParityHarness.build(LocalDate.of(2026, 11, 3));
        Activity fresh = new Activity();
        fresh.id = "new"; fresh.name = "Something else"; fresh.kind = ActivityKind.GOAL;
        cfg.activities.add(fresh);
        Similar.seed(fresh, cfg);
        assertEquals(1.0, fresh.q, 1e-9);
        assertEquals(0.35, fresh.qSigma, 1e-9);
    }

    // ------------------------------------------------------------ item 7

    static LogEntry session(String act, LocalDate d, int planned, int actual) {
        LogEntry e = new LogEntry();
        e.id = "session|" + d + "|" + act + "|" + planned + "|" + actual + "|" + Math.random();
        e.kind = LogKind.SESSION; e.date = d; e.at = "10:00"; e.actId = act;
        e.planned = planned; e.actual = actual;
        return e;
    }

    @Test
    public void theCapIsWhatYourGoodDaysHeldAndATenthMore() {
        LocalDate today = LocalDate.of(2026, 11, 8);
        Config cfg = ParityHarness.build(today);
        List<LogEntry> logs = new ArrayList<>();
        // Ten logged days: 1, 1, 1, 1, 1, 2, 2, 2, 3, 4 hours of goal work.
        int[] hours = {1, 1, 1, 1, 1, 2, 2, 2, 3, 4};
        for (int i = 0; i < hours.length; i++) logs.add(session("papers", today.minusDays(i + 1), 120, hours[i] * 60));
        GoalCap.Cap cap = GoalCap.of(cfg, logs, today, Set.of());
        assertNotNull(cap);
        assertEquals(10, cap.days());
        assertEquals(2, cap.goodDayH(), 1e-9);              // the eighth of ten, in order
        assertEquals(2.25, cap.capH(), 1e-9);               // 2.2 rounded up to the quarter hour
    }

    @Test
    public void tooFewLoggedDaysSayNothing() {
        LocalDate today = LocalDate.of(2026, 11, 8);
        Config cfg = ParityHarness.build(today);
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 0; i < 6; i++) logs.add(session("papers", today.minusDays(i + 1), 60, 60));
        assertNull(GoalCap.of(cfg, logs, today, Set.of()));
    }

    @Test
    public void theHorizonPlansNoDayAboveTheCap() {
        LocalDate today = LocalDate.of(2026, 11, 8);
        Config cfg = ParityHarness.build(today);
        cfg.goalCapH = 1.5;
        HorizonPlanner.Plan plan = HorizonPlanner.plan(cfg, today);
        for (int i = 0; i < plan.days().size(); i++) {
            assertTrue(plan.days().get(i).capacityH() <= 1.5 + 1e-9);
            double planned = plan.allocation().get(i).values().stream().mapToDouble(Double::doubleValue).sum();
            assertTrue("day " + i + " plans " + planned, planned <= 1.5 + 1e-6);
        }
        assertEquals(1.5, cfg.copy().goalCapH, 1e-9);
    }
}
