package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.chronos.data.Codec;
import com.chronos.engine.DaySequencer;
import com.chronos.engine.Slots;
import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.validate.Readiness;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Allowed hours of their own for each weekday (your request of 27 Oct 2026). */
public class WindowsByDayTest {

    private static final LocalDate SAT = LocalDate.of(2026, 10, 31);   // dow 0
    private static final LocalDate THU = LocalDate.of(2026, 10, 29);   // dow 5

    @Test
    public void theDaysOfAWindowAreStoredAndReadBack() {
        List<Activity.Window> ws = List.of(new Activity.Window("08:00", "12:00", List.of(0, 1, 2)),
            new Activity.Window("14:00", "18:00", List.of(5)), new Activity.Window("19:00", "21:00"));
        String stored = Codec.windows(ws);
        assertEquals("08:00-12:00@0,1,2;14:00-18:00@5;19:00-21:00", stored);
        List<Activity.Window> back = Codec.parseWindows(stored);
        assertEquals(3, back.size());
        assertEquals(List.of(0, 1, 2), List.copyOf(back.get(0).days));
        assertEquals(List.of(5), List.copyOf(back.get(1).days));
        assertNull("no days: every day", back.get(2).days);
        assertEquals("what was stored before reads as it did", 2, Codec.parseWindows("08:00-12:30;14:00-19:30").size());
    }

    @Test
    public void eachDayIsPlannedInItsOwnHours() {
        Config cfg = ParityHarness.build(SAT);
        Activity lunch = cfg.activity("lunch");
        lunch.windows.clear();
        lunch.windows.add(new Activity.Window("12:00", "14:00", List.of(0, 1, 2, 3, 4, 6)));
        lunch.windows.add(new Activity.Window("15:00", "17:00", List.of(5)));
        assertEquals(1, lunch.windowsOn(5).size());

        Activity onThu = cfg.on(THU).activity("lunch");
        assertEquals("15:00", onThu.windows.get(0).from);
        assertEquals(1, onThu.windows.size());
        assertEquals("12:00", cfg.on(SAT).activity("lunch").windows.get(0).from);
        assertEquals("the stored activity keeps every window", 2, lunch.windows.size());

        DaySequencer.Result thu = DaySequencer.run(cfg.on(THU), Slots.dow(THU), Map.of(), 3);
        assertTrue("lunch on Thursday: " + thu.blocks().stream().map(b -> b.name + "@" + b.start).toList(),
            thu.blocks().stream().anyMatch(b -> "Lunch".equals(b.name)));
        thu.blocks().stream().filter(b -> "Lunch".equals(b.name)).forEach(b ->
            assertTrue("Thursday's lunch sits in Thursday's hours", b.start >= 60 && b.start + b.dur <= 68));
        DaySequencer.Result sat = DaySequencer.run(cfg.on(SAT), Slots.dow(SAT), Map.of(), 3);
        sat.blocks().stream().filter(b -> "Lunch".equals(b.name)).forEach(b ->
            assertTrue("Saturday's lunch sits in Saturday's hours", b.start >= 48 && b.start + b.dur <= 56));
        assertTrue("every day it happens has hours", Readiness.check(cfg, SAT).stream().noneMatch(i -> i.what().contains("none of its windows")));
    }

    @Test
    public void aDayWithNoWindowIsNotPlannedAndSetupSaysSo() {
        Config cfg = ParityHarness.build(SAT);
        Activity lunch = cfg.activity("lunch");
        lunch.windows.clear();
        lunch.windows.add(new Activity.Window("12:00", "14:00", List.of(0, 1, 2, 3, 4, 6)));
        assertNull("no window holds on Thursday", cfg.on(THU).activity("lunch"));
        assertTrue(Readiness.check(cfg, SAT).stream().anyMatch(i ->
            i.severity() == Readiness.Severity.WARNING && i.what().contains("on Thu none of its windows holds")));
    }

    @Test
    public void withoutDaysNothingChanges() {
        Config cfg = ParityHarness.build(SAT);
        cfg.planFrom = null;
        assertSame("no window with days of its own: the same configuration", cfg, cfg.on(SAT));
        assertFalse(cfg.activity("lunch").windowsByDay());
    }
}
