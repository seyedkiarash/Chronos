package com.chronos.harness;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.DaySequencer;
import com.chronos.engine.Slots;
import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.validate.Readiness;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Map;

/** A window that ends before it starts is a mistake: reported, and left out of planning. */
public class WindowTest {

    @Test
    public void spansAndMistakes() {
        assertArrayEquals(new int[]{32, 48}, Slots.span("08:00", "12:00"));
        assertArrayEquals("00:00 as the end is midnight", new int[]{88, 96}, Slots.span("22:00", "00:00"));
        assertNull(Slots.span("14:00", "12:00"));
        assertNull(Slots.span("09:00", "09:00"));
    }

    @Test
    public void aBackwardsWindowIsIgnoredAndReported() {
        LocalDate today = LocalDate.of(2026, 9, 28);
        Config cfg = ParityHarness.build(today);
        Activity lunch = cfg.activity("lunch");
        lunch.windows.clear();
        lunch.windows.add(new Activity.Window("15:00", "12:00"));   // backwards, and the only one

        assertFalse("the only window being wrong blocks the plan", Readiness.canSolve(Readiness.check(cfg, today)));
        DaySequencer.Result day = DaySequencer.run(cfg, Slots.dow(today), Map.of(), 3);
        assertTrue("with no valid window it has nowhere to go",
            day.blocks().stream().noneMatch(b -> "Lunch".equals(b.name)));

        lunch.windows.add(new Activity.Window("12:00", "15:00"));   // a good one beside it
        assertTrue(Readiness.check(cfg, today).stream().anyMatch(i ->
            i.severity() == Readiness.Severity.WARNING && i.what().contains("ends before it starts")));
        DaySequencer.Result fixed = DaySequencer.run(cfg, Slots.dow(today), Map.of(), 3);
        fixed.blocks().stream().filter(b -> "Lunch".equals(b.name)).forEach(b ->
            assertTrue("placed inside the valid window", b.start >= 48 && b.start + b.dur <= 60));
    }
}
