package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.chronos.data.Codec;
import com.chronos.engine.DaySequencer;
import com.chronos.engine.Slots;
import com.chronos.model.Activity;
import com.chronos.model.Block;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Upkeep is placed completely or reported: every session of a several-a-day
 * activity, a pin even when its changeover has no room, travel that does not
 * run into the outing's changeover, and a second try when a flexible neighbour
 * could simply move.
 */
public class UpkeepPlacementTest {

    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 3);   // classes 09:00–13:00
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);     // no classes

    private static Activity upkeep(String id, String name, int min, String... windows) {
        Activity a = new Activity();
        a.id = id; a.name = name; a.kind = ActivityKind.MAINTENANCE;
        for (int d = 0; d < 7; d++) a.days.add(d);
        a.minBlock = min; a.maxBlock = min; a.load = 0.0; a.recovery = 0.3;
        for (int i = 0; i + 1 < windows.length; i += 2) a.windows.add(new Activity.Window(windows[i], windows[i + 1]));
        return a;
    }

    private static List<Block> named(DaySequencer.Result r, String name) {
        return r.blocks().stream().filter(b -> name.equals(b.name)).toList();
    }

    @Test
    public void everySessionOfASeveralADayActivityIsPlaced() {
        Config cfg = ParityHarness.build(FRIDAY);
        Activity pray = upkeep("pray", "Pray", 15, "07:15", "07:30", "13:30", "14:30", "18:15", "20:15");
        pray.maxBlocks = 3;
        int dow = Slots.dow(FRIDAY);
        pray.pins.add(new Activity.Pin(dow, "07:15"));
        pray.pins.add(new Activity.Pin(dow, "13:30"));
        cfg.activities.add(pray);

        DaySequencer.Result r = DaySequencer.run(cfg, dow, Map.of(), 5);
        List<Block> sessions = named(r, "Pray");
        assertEquals("three sessions, not one", 3, sessions.size());
        assertTrue(sessions.stream().anyMatch(b -> b.start == Slots.parse("07:15")));
        assertTrue(sessions.stream().anyMatch(b -> b.start == Slots.parse("13:30")));
        assertTrue("the third goes in the window no pin used", sessions.stream().anyMatch(b ->
            b.start >= Slots.parse("18:15") && b.start + b.dur <= Slots.parse("20:15")));
    }

    @Test
    public void aPinWhoseChangeoverHasNoRoomIsStillPlacedAndReported() {
        Config cfg = ParityHarness.build(SATURDAY);
        Activity lunch = cfg.activity("lunch");
        lunch.changeoverMin = 15;
        lunch.pins.add(new Activity.Pin(Slots.dow(SATURDAY), "13:00"));   // the minute class ends

        DaySequencer.Result r = DaySequencer.run(cfg, Slots.dow(SATURDAY), Map.of(), 5);
        assertEquals(Slots.parse("13:00"), named(r, "Lunch").get(0).start);
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("Lunch starts at 13:00 with no time")));
        assertTrue(r.unplaced().isEmpty());
    }

    @Test
    public void theTripEndsWhereTheOutingsChangeoverBegins() {
        Config cfg = ParityHarness.build(FRIDAY);
        Activity workout = cfg.activity("exercise");
        workout.days.clear();
        workout.days.add(Slots.dow(FRIDAY));
        workout.changeoverMin = 15;

        DaySequencer.Result r = DaySequencer.run(cfg, Slots.dow(FRIDAY), Map.of(), 5);
        Block gym = named(r, "Workout").get(0);
        Block out = r.blocks().stream().filter(b -> b.name.endsWith("(out)")).findFirst().get();
        assertTrue("arrives before the changeover", out.start + out.dur <= gym.start - 1);
        assertTrue("and not hours early", out.start + out.dur >= gym.start - 3);
    }

    @Test
    public void aNeighbourThatCanMoveMakesRoom() {
        Config cfg = ParityHarness.build(FRIDAY);
        Activity breakfast = cfg.activity("breakfast");
        breakfast.windows.clear();
        breakfast.windows.add(new Activity.Window("07:00", "08:00"));
        breakfast.changeoverMin = 15;
        Activity pray = upkeep("pray", "Pray", 15, "07:00", "07:15");   // one slot, no choice
        cfg.activities.add(pray);

        DaySequencer.Result r = DaySequencer.run(cfg, Slots.dow(FRIDAY), Map.of(), 5);
        assertEquals(Slots.parse("07:00"), named(r, "Pray").get(0).start);
        assertFalse(named(r, "Breakfast").isEmpty());
        assertTrue(r.unplaced().isEmpty());
    }

    @Test
    public void whatCannotFitIsReportedWithWhatFillsItsWindow() {
        Config cfg = ParityHarness.build(FRIDAY);
        Activity a = upkeep("a", "Stretch", 30, "10:00", "10:30");
        Activity b = upkeep("b", "Tea", 30, "10:00", "10:30");
        cfg.activities.add(a);
        cfg.activities.add(b);

        DaySequencer.Result r = DaySequencer.run(cfg, Slots.dow(FRIDAY), Map.of(), 5);
        assertEquals(1, r.unplaced().size());
        String reason = r.unplaced().get(0).reason;
        assertTrue(reason, reason.contains("10:00–10:30: Stretch") || reason.contains("10:00–10:30: Tea"));
        assertTrue(reason, reason.contains("widen a window"));

        // and the reason survives being stored and read back
        Block back = Codec.parseBlocks(Codec.blocks(r.unplaced())).get(0);
        assertEquals(-1, back.start);
        assertEquals(reason, back.reason);
    }
}
