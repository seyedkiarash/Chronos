package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.chronos.data.ChronosRepository;
import com.chronos.engine.DaySequencer;
import com.chronos.model.Activity;
import com.chronos.model.Block;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.LogEntry;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;

/** A past day whose kept plan was lost is rebuilt from the boxes filled in for it. */
public class LostDayTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    private static LogEntry entry(String id, Integer planned, Integer actual, String at) {
        LogEntry l = new LogEntry();
        l.id = id; l.kind = LogKind.SESSION; l.date = DAY; l.planned = planned; l.actual = actual; l.at = at;
        return l;
    }

    @Test
    public void theBlocksComeBackFromTheLog() {
        Config cfg = ParityHarness.build(DAY);
        Activity work = null, meal = null;
        for (Activity a : cfg.activities) {
            if (work == null && a.kind == ActivityKind.GOAL) work = a;
            if (meal == null && a.kind == ActivityKind.MAINTENANCE) meal = a;
        }
        List<LogEntry> logs = List.of(
            entry("session|" + DAY + "|" + work.id + "|36", 90, 80, "09:05"),
            entry("session|" + DAY + "|" + meal.id + "|52", 30, 30, "13:00"),
            // a skipped block with what was done instead: the block itself still comes back
            entry("instead|" + DAY + "|" + work.id + "|60|" + meal.id, 45, 45, "15:00"),
            entry("energy|" + DAY + "|9", null, null, "09:30"));

        DaySequencer.Result r = ChronosRepository.fromLog(cfg, DAY, logs);
        List<Block> mine = r.blocks().stream().filter(b -> !b.fixed).toList();
        assertEquals(3, mine.size());
        assertEquals(36, mine.get(0).start);
        assertEquals(90 / com.chronos.engine.Slots.SLOT_MIN, mine.get(0).dur);
        assertEquals(work.name, mine.get(0).name);
        assertEquals(60, mine.get(2).start);
        assertTrue(r.warnings().get(0).contains("rebuilt"));
    }

    @Test
    public void aDayWithNothingLoggedStaysEmpty() {
        assertNull(ChronosRepository.fromLog(ParityHarness.build(DAY), DAY, List.of()));
    }
}
