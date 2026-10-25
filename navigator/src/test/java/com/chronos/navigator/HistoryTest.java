package com.chronos.navigator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.estimate.History;
import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.model.Graph;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;

/** The history of each learned value, replayed day by day from the log. */
public class HistoryTest {

    static final LocalDate FIRST = LocalDate.parse("2026-10-14");

    static Inputs log() {
        Inputs in = new Inputs();
        in.nodeActivities.put("T_CONVEX", List.of("watching convex videos"));
        in.unitGoals.put("watching convex videos", new Inputs.UnitGoal(1.5, 10, 30.0));
        double[] minutesPerUnit = {200, 180, 160, 140, 120};
        for (int i = 0; i < minutesPerUnit.length; i++) {
            LocalDate d = FIRST.plusDays(i);
            in.sessions.add(new Inputs.Session(d, "watching convex videos", 120, (int) (2 * minutesPerUnit[i] / 2), true, false, 1.0, true));
            in.sessions.add(new Inputs.Session(d, "watching convex videos", 60, null, true, true, null, true));
        }
        return in;
    }

    @Test
    public void eachValueRunsFromItsPriorToTodayAndTheStepsAddUp() {
        Graph g = ForecastTest.answered();
        LocalDate today = FIRST.plusDays(5);
        List<History.Series> all = History.build(g, log(), today);
        History.Series speed = all.stream().filter(s -> s.kind().equals("speed")).findFirst().orElseThrow();
        assertEquals("h/unit", speed.unit());
        assertTrue("it got faster", speed.now() < speed.points().get(0).value());
        double sum = speed.steps().stream().mapToDouble(History.Step::delta).sum();
        assertEquals("the waterfall adds up from the prior to now", speed.now() - speed.prior(), sum, 1e-9);
        for (History.Point p : speed.points()) assertTrue(p.low() <= p.value() && p.value() <= p.high());
        assertTrue(speed.steps().get(0).why().contains("units in"));

        History.Series adh = all.stream().filter(s -> s.key().endsWith("adherence.watching convex videos")).findFirst().orElseThrow();
        assertEquals("%", adh.unit());
        assertTrue(adh.steps().stream().anyMatch(s -> s.why().contains("of 180 planned min")));
        assertFalse(all.isEmpty());
    }
}
