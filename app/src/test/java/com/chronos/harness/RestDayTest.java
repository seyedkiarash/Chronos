package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.DaySequencer;
import com.chronos.engine.HorizonPlanner;
import com.chronos.engine.RestAdvisor;
import com.chronos.engine.RestAdvisor.Level;
import com.chronos.engine.Slots;
import com.chronos.engine.WeeklyLoad;
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

/** Rest days: when the app calls one, what it does to the plan, and how the week's load is read. */
public class RestDayTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    @Test
    public void aBadMorningAloneCanCallARestDay() {
        RestAdvisor.Verdict v = RestAdvisor.assess(1, List.of("Feeling unwell"), null, null, List.of(), null, List.of());
        assertEquals(Level.REST, v.level());
        assertEquals(2, v.reasons().size());
    }

    @Test
    public void oneMildSignNeedsCompany() {
        assertEquals(Level.NORMAL, RestAdvisor.assess(3, List.of("Slept badly"), null, null, List.of(), null, List.of()).level());
        RestAdvisor.Verdict heavyWeek = RestAdvisor.assess(2, List.of("Slept badly"), null, null, List.of(), 1.35, List.of());
        assertEquals("low energy, a bad night and a heavy week", Level.CONSIDER, heavyWeek.level());
        RestAdvisor.Verdict sliding = RestAdvisor.assess(2, List.of("Low mood"), 2, 4, List.of("Overwhelmed"),
            1.55, List.of(2, 2, 3));
        assertEquals(Level.REST, sliding.level());
    }

    @Test
    public void aRestDayGetsNoGoalWorkAndTheReturnIsGradual() {
        Config cfg = ParityHarness.build(DAY);
        HorizonPlanner.Plan normal = HorizonPlanner.plan(cfg, DAY);
        double[] scale = {0, 0.6, 0.8};
        HorizonPlanner.Plan rested = HorizonPlanner.plan(cfg, DAY, Map.of(), List.of(), scale);
        assertTrue("nothing on the rest day", rested.allocation().get(0).isEmpty());
        assertEquals(normal.days().get(1).capacityH() * 0.6, rested.days().get(1).capacityH(), 1e-9);
        assertEquals(normal.days().get(3).capacityH(), rested.days().get(3).capacityH(), 1e-9);
    }

    @Test
    public void restOnlyActivitiesArePlannedOnlyOnRestDays() {
        Config cfg = ParityHarness.build(DAY);
        Activity walk = new Activity();
        walk.id = "walk"; walk.name = "Long walk"; walk.kind = ActivityKind.MAINTENANCE; walk.restOnly = true;
        for (int d = 0; d < 7; d++) walk.days.add(d);
        walk.minBlock = 60; walk.maxBlock = 90; walk.load = 0.0; walk.recovery = 0.6;
        walk.windows.add(new Activity.Window("15:00", "18:00"));
        cfg.activities.add(walk);
        int dow = Slots.dow(DAY);

        DaySequencer.Result normal = DaySequencer.run(cfg, dow, Map.of("lit", 3.0), 9, Map.of(), false);
        DaySequencer.Result rest = DaySequencer.run(cfg, dow, Map.of("lit", 3.0), 9, Map.of(), true);
        assertFalse(normal.blocks().stream().anyMatch(b -> "Long walk".equals(b.name)));
        assertTrue(rest.blocks().stream().anyMatch(b -> "Long walk".equals(b.name)));
        assertTrue("no goal work on a rest day", rest.blocks().stream().noneMatch(b -> b.goalId != null));
        assertTrue(normal.blocks().stream().anyMatch(b -> b.goalId != null));
    }

    private static LogEntry session(LocalDate d, int minutes) {
        LogEntry e = new LogEntry();
        e.id = "s" + d; e.kind = LogKind.SESSION; e.date = d; e.at = "10:00"; e.ts = 1;
        e.actId = "papers"; e.planned = minutes; e.actual = minutes; e.attain = 100.0;
        return e;
    }

    @Test
    public void theWeeksLoadIsComparedOnlyOnceThereIsAHistory() {
        Config cfg = ParityHarness.build(DAY);
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 0; i < 5; i++) logs.add(session(DAY.minusDays(i), 120));
        WeeklyLoad.Report short_ = WeeklyLoad.report(cfg, DAY, logs);
        assertTrue(short_.weekLoadH() > 0);
        assertNull("five days is not a history", short_.ratio());

        for (int i = 7; i < 28; i++) logs.add(session(DAY.minusDays(i), 60));
        WeeklyLoad.Report full = WeeklyLoad.report(cfg, DAY, logs);
        assertNotNull(full.ratio());
        assertTrue("two hours a day against a month of one is a heavy week", full.ratio() > 1.2);
    }
}
