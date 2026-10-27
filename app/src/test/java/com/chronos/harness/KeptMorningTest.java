package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.DaySequencer;
import com.chronos.engine.Slots;
import com.chronos.model.Block;
import com.chronos.model.Config;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A day built again from an hour of it (26 Oct 2026): the morning stays as it was and the rest is
 * arranged around it — no second commute over the first, no second lunch, no goal work done twice.
 */
public class KeptMorningTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 26);   // a Monday: classes 09:00–13:00

    private static String describe(List<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) sb.append(Slots.format(b.start)).append('-').append(Slots.format(b.start + b.dur))
            .append(' ').append(b.name).append("; ");
        return sb.toString();
    }

    @Test
    public void theMorningStaysAndNothingIsArrangedTwice() {
        Config cfg = ParityHarness.build(DAY);
        int dow = Slots.dow(DAY);
        Map<String, Double> demand = Map.of("lit", 3.0, "courses", 2.0, "english", 1.0);
        DaySequencer.Result whole = DaySequencer.run(cfg, dow, demand, 11L);

        int floor = Slots.parse("14:00");
        List<Block> kept = new ArrayList<>();
        for (Block b : whole.blocks()) if (b.start < floor && !b.fixed) kept.add(b);
        assertFalse("the morning had something in it: " + describe(whole.blocks()), kept.isEmpty());

        cfg.planFrom = DAY.atTime(14, 0);
        DaySequencer.Result again = DaySequencer.run(cfg.on(DAY), dow, demand, 11L, Map.of(), false, kept, floor);
        List<Block> all = again.blocks();
        String day = describe(all);

        // Nothing overlaps.
        for (Block a : all) for (Block b : all)
            if (a != b) assertFalse("overlap: " + day, a.start < b.start + b.dur && b.start < a.start + a.dur);
        // Everything before the hour is what was kept.
        for (Block b : all) if (b.start < floor && !b.fixed) assertTrue("new before the hour: " + day, b.kept);
        // No upkeep and no leg of a trip twice.
        for (String name : new String[]{"Breakfast", "Lunch", "Dinner", "Commute (out)", "Commute (back)"})
            assertTrue(name + " twice: " + day, all.stream().filter(b -> name.equals(b.name)).count() <= 1);
        // The goal work of the whole day is what the day was given, not the morning's again on top.
        double before = 0, now = 0;
        for (Block b : whole.blocks()) if (b.goalId != null) before += b.dur * Slots.SLOT_MIN;
        for (Block b : all) if (b.goalId != null) now += b.dur * Slots.SLOT_MIN;
        assertTrue("goal work doubled: " + before + " then " + now + ": " + day, now <= before + 1e-9);
    }

    @Test
    public void aDayWithNothingKeptIsArrangedAsBefore() {
        Config cfg = ParityHarness.build(DAY);
        int dow = Slots.dow(DAY);
        Map<String, Double> demand = Map.of("lit", 3.0);
        DaySequencer.Result a = DaySequencer.run(cfg, dow, demand, 5L);
        DaySequencer.Result b = DaySequencer.run(cfg, dow, demand, 5L, Map.of(), false, List.of(), 0);
        assertEquals(describe(a.blocks()), describe(b.blocks()));
    }
}
