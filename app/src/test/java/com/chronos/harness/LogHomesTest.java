package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.LogHomes;
import com.chronos.engine.LogHomes.Place;
import com.chronos.model.LogEntry;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Saved entries find their block again after the day was arranged anew (your report of 28 Oct 2026). */
public class LogHomesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 27);

    private static LogEntry entry(String id) {
        LogEntry l = new LogEntry();
        l.id = id; l.date = DAY; l.at = "20:00";
        return l;
    }

    @Test
    public void anEntryUnderAMovedBlockIsShownUnderItsBlockNow() {
        // Saved at 22:30 under "paper at 19:15" (slot 77); the day was arranged again and paper now starts at 19:00.
        List<LogEntry> logs = List.of(entry("session|2026-10-27|paper|77"), entry("trip|2026-10-27|bus|70"),
            entry("session|2026-10-27|lunch|48"));
        List<Place> now = List.of(new Place("act|paper", 76), new Place("act|lunch", 48), new Place("trip|bus", 72),
            new Place("act|paper", 88));
        Map<String, Integer> homes = LogHomes.of(DAY, now, logs);
        assertEquals("the moved block opens the entry saved under its old start", 77, (int) homes.get("act|paper|76"));
        assertEquals("a second block of it has nothing saved: its own start", 88, (int) homes.get("act|paper|88"));
        assertEquals("a block that did not move is unchanged", 48, (int) homes.get("act|lunch|48"));
        assertEquals(70, (int) homes.get("trip|bus|72"));
        assertTrue(LogHomes.unseen(DAY, now, logs).isEmpty());
    }

    @Test
    public void aSkippedBlockKeepsWhatWasDoneInstead() {
        List<LogEntry> logs = List.of(entry("session|2026-10-27|cs|82"), entry("instead|2026-10-27|cs|82|reward"));
        Map<String, Integer> homes = LogHomes.of(DAY, List.of(new Place("act|cs", 76)), logs);
        assertEquals(82, (int) homes.get("act|cs|76"));
    }

    @Test
    public void anEntryNoBlockCanTakeIsListedNotHidden() {
        // Both blocks of the activity already have entries of their own; the third entry has no block.
        List<LogEntry> logs = List.of(entry("session|2026-10-27|paper|76"), entry("session|2026-10-27|paper|88"),
            entry("session|2026-10-27|paper|77"), entry("session|2026-10-27|old|40"));
        List<Place> now = List.of(new Place("act|paper", 76), new Place("act|paper", 88));
        List<LogEntry> unseen = LogHomes.unseen(DAY, now, logs);
        assertEquals(2, unseen.size());
        assertTrue(unseen.stream().anyMatch(l -> l.id.endsWith("paper|77")));
        assertTrue(unseen.stream().anyMatch(l -> l.id.endsWith("old|40")));
    }
}
