package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.DayMatch;
import com.chronos.engine.Slots;
import com.chronos.model.Activity;
import com.chronos.model.Block;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.LogEntry;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** How closely a day followed its plan, part by part. */
public class DayMatchTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    private static Block block(Activity a, int start, int minutes) {
        Block b = new Block();
        b.name = a.name; b.goalId = a.goalId; b.start = start; b.dur = minutes / Slots.SLOT_MIN;
        return b;
    }

    private static LogEntry session(Activity a, int slot, String at, Integer actual, Integer drained) {
        LogEntry l = new LogEntry();
        l.id = "session|" + DAY + "|" + a.id + "|" + slot; l.kind = LogKind.SESSION; l.date = DAY;
        l.at = at; l.actual = actual; l.fatigueAfter = drained;
        return l;
    }

    @Test
    public void eachPartIsScoredFromWhatWasLogged() {
        Config cfg = ParityHarness.build(DAY);
        Activity work = cfg.activities.stream().filter(a -> a.kind == ActivityKind.GOAL).findFirst().orElseThrow();
        Activity meal = cfg.activities.stream().filter(a -> a.kind == ActivityKind.MAINTENANCE).findFirst().orElseThrow();
        int s9 = Slots.parse("09:00"), s13 = Slots.parse("13:00"), s16 = Slots.parse("16:00");
        List<Block> plan = List.of(block(work, s9, 90), block(meal, s13, 30), block(work, s16, 60));

        double[] fatigue = new double[Slots.PER_DAY];
        Arrays.fill(fatigue, 0.6);
        double[] capacity = new double[Slots.PER_DAY];
        Arrays.fill(capacity, 0.8);

        LogEntry energy = new LogEntry();
        energy.id = "energy|" + DAY + "|10"; energy.kind = LogKind.CHECKIN; energy.date = DAY; energy.energy = 3;

        List<LogEntry> logs = List.of(
            session(work, s9, "09:30", 90, 3),     // on length, 30 min late, drained as expected
            session(meal, s13, null, null, null),  // skipped
            energy);                                // the 16:00 block is not reported

        DayMatch.Result r = DayMatch.of(cfg, DAY, plan, capacity, fatigue, null, logs);
        assertEquals(2, r.reported());
        assertEquals(1, r.skipped());
        assertEquals(1, r.unreported());
        assertEquals("one on length, one skipped", 0.5, r.duration(), 1e-9);
        assertEquals("half an hour late of an hour's tolerance", 0.5, r.start(), 1e-9);
        assertEquals("expected 0.8, said 0.6", 0.8, r.energy(), 1e-9);
        assertEquals("expected 0.6, said 0.6", 1.0, r.fatigue(), 1e-9);
        assertEquals((0.5 + 0.5 + 0.8 + 1.0) / 4, r.overall(), 1e-9);
        assertEquals(3, r.rows().size());
        assertTrue(r.rows().get(1).skipped());
    }

    @Test
    public void aDayWithNothingLoggedHasNoScore() {
        Config cfg = ParityHarness.build(DAY);
        Activity work = cfg.activities.stream().filter(a -> a.kind == ActivityKind.GOAL).findFirst().orElseThrow();
        DayMatch.Result r = DayMatch.of(cfg, DAY, List.of(block(work, 36, 60)), null, null, null, List.of());
        assertTrue(r.empty());
        assertNull(r.duration());
        assertEquals(1, r.unreported());
    }
}
