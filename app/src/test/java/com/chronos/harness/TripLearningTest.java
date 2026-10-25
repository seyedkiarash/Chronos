package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.Calibrator;
import com.chronos.model.Commitment;
import com.chronos.model.Config;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.LogEntry;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A trip is learned like an activity: how long it takes, and what it costs. */
public class TripLearningTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    private static LogEntry trip(String at, int minutes, Integer drained, String... tags) {
        LogEntry e = new LogEntry();
        e.id = "trip|" + DAY + "|c2|" + at;
        e.kind = LogKind.TRAVEL;
        e.date = DAY;
        e.at = at;
        e.ts = 1;
        e.comId = "c2";
        e.actual = minutes;
        e.fatigueAfter = drained;
        e.tags = new ArrayList<>(List.of(tags));
        return e;
    }

    private static Commitment commute(Calibrator.Outcome out) {
        return out.commitments().stream().filter(c -> c.id.equals("c2")).findFirst().get();
    }

    @Test
    public void drainingTripsRaiseTheTripsLoad() {
        Config cfg = ParityHarness.build(DAY);
        double before = cfg.commitments.get(1).load;
        List<LogEntry> logs = List.of(trip("08:00", 60, 5), trip("17:00", 70, 5));
        Calibrator.Outcome out = Calibrator.calibrate(cfg.copy(), logs);
        assertTrue("two exhausting trips make it costlier", commute(out).load > before);
        assertTrue(out.report().stream().anyMatch(c -> c.detail().startsWith("fatigue load")));
    }

    @Test
    public void oneRatingOrAConfoundedOneMovesNothing() {
        Config cfg = ParityHarness.build(DAY);
        double before = cfg.commitments.get(1).load;
        Calibrator.Outcome one = Calibrator.calibrate(cfg.copy(), List.of(trip("08:00", 60, 5)));
        assertEquals(before, commute(one).load, 1e-12);
        Calibrator.Outcome sick = Calibrator.calibrate(cfg.copy(),
            List.of(trip("08:00", 60, 5, "Unwell"), trip("17:00", 60, 5, "Unwell")));
        assertEquals(before, commute(sick).load, 1e-12);
    }

    @Test
    public void tripsWithoutARatingStillTeachTheirLength() {
        Config cfg = ParityHarness.build(DAY);
        List<LogEntry> logs = List.of(trip("08:00", 80, null), trip("17:00", 85, null), trip("08:05", 82, null));
        Calibrator.Outcome out = Calibrator.calibrate(cfg.copy(), logs);
        assertEquals(82.0, commute(out).medianMin, 1.0);
    }
}
