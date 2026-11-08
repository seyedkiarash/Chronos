package com.chronos.navigator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.estimate.Accuracy;
import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.model.Graph;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;

/** How right the model has been: each past week replayed with only what came before it (8 Nov 2026). */
public class AccuracyTest {

    static final LocalDate TODAY = LocalDate.parse("2026-11-08");

    /** Four weeks of an hour planned a day, worked {@code worked} minutes, and a unit taking {@code perUnit} minutes. */
    static Inputs log(int worked, int lastWeekWorked, int perUnit) {
        Inputs in = new Inputs();
        in.unitGoals.put("videos", new Inputs.UnitGoal(1.0, 0, 40.0));
        for (int i = 28; i >= 1; i--) {
            LocalDate d = TODAY.minusDays(i);
            int w = i <= 7 ? lastWeekWorked : worked;
            in.sessions.add(new Inputs.Session(d, "reading", 60, w, true, false, null, true));
            in.sessions.add(new Inputs.Session(d, "videos", perUnit, perUnit, true, false, 1.0, true));
        }
        return in;
    }

    @Test
    public void aWeekIsForeseenFromTheDaysBeforeItAndBeatsThePlanAtItsWord() {
        Graph g = EstimatorTest.ready();
        List<Accuracy.Week> weeks = Accuracy.backtest(g, log(30, 30, 90), TODAY, 8);
        assertEquals("four weeks of log, four weeks judged", 4, weeks.size());
        Accuracy.Week last = weeks.get(0);
        assertEquals(TODAY.minusDays(7), last.from());
        assertEquals(TODAY.minusDays(1), last.to());
        assertEquals(17.5, last.plannedH(), 1e-9);            // 7 days × (1 h of reading + 1.5 h of videos)
        assertTrue("half the reading hour is worked, and learned: " + last.hoursErr() + " vs " + last.plainHoursErr(),
            last.hoursErr() < last.plainHoursErr());
        assertTrue("a unit takes 90 minutes, not the 60 typed: " + last.speedErr() + " vs " + last.plainSpeedErr(),
            last.speedErr() < last.plainSpeedErr());
        assertEquals(1 / 3.0, last.plainSpeedErr(), 1e-9);     // the typed hour: |60 − 90| ÷ 90
    }

    @Test
    public void theWeekJudgedDoesNotTeachItsOwnForecast() {
        Graph g = EstimatorTest.ready();
        Accuracy.Week same = Accuracy.backtest(g, log(30, 30, 90), TODAY, 1).get(0);
        Accuracy.Week other = Accuracy.backtest(g, log(30, 60, 90), TODAY, 1).get(0);
        // A different last week changes what happened, not what was foreseen before it.
        assertEquals(same.predictedH(), other.predictedH(), 1e-9);
        assertTrue(other.workedH() > same.workedH());
    }

    @Test
    public void theSummaryWeighsHoursByPlannedTime() {
        Graph g = EstimatorTest.ready();
        List<Accuracy.Week> weeks = Accuracy.backtest(g, log(30, 30, 90), TODAY, 8);
        double[] s = Accuracy.summary(weeks);
        assertTrue(s[0] < s[1]);
        assertTrue(s[2] < s[3]);
        assertEquals(weeks.size(), Accuracy.asParams(weeks, 0).size());
    }
}
