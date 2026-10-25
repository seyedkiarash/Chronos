package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.Calibrator;
import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.LogEntry;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The log shapes the Log screen writes — an energy rating per waking hour and
 * a box per planned block — fed to the calibrator the way the nightly
 * close-out feeds it.
 */
public class DayFeedbackTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    private static long ts = 1_000_000L;

    private static LogEntry hour(int h, int energy, String... tags) {
        LogEntry e = new LogEntry();
        e.id = "energy|" + DAY + "|" + h;
        e.kind = LogKind.CHECKIN;
        e.date = DAY;
        e.at = String.format("%02d:30", h);
        e.ts = ts++;
        e.energy = energy;
        e.tags = new ArrayList<>(List.of(tags));
        return e;
    }

    private static LogEntry session(String act, String at, int planned, int actual,
                                    Double attain, Integer focus, Integer drained, Integer restored) {
        LogEntry e = new LogEntry();
        e.id = "session|" + DAY + "|" + act + "|" + at;
        e.kind = LogKind.SESSION;
        e.date = DAY;
        e.at = at;
        e.ts = ts++;
        e.actId = act;
        e.planned = planned;
        e.actual = actual;
        e.attain = attain;
        e.focus = focus;
        e.fatigueAfter = drained;
        e.restored = restored;
        return e;
    }

    /** What the screen writes for "I skipped it": no start time, no ratings. */
    private static LogEntry skipped(String act, int planned) {
        LogEntry e = session(act, null, planned, 0, null, null, null, null);
        e.id = "session|" + DAY + "|" + act + "|skip";
        e.tags = new ArrayList<>(List.of("Skipped"));
        return e;
    }

    private static List<LogEntry> aDay() {
        List<LogEntry> logs = new ArrayList<>();
        // 07:00 to 22:59. Bed is 23:30 here; the screen files that last half hour at 23:15.
        int[] curve = {2, 3, 4, 4, 5, 4, 3, 3, 4, 4, 3, 3, 2, 2, 2, 1};
        for (int i = 0; i < curve.length; i++) logs.add(hour(7 + i, curve[i]));
        logs.add(hour(15, 5, "Caffeine"));   // replaces nothing: a separate, confounded rating
        logs.add(session("papers", "08:00", 90, 100, 80.0, 4, 3, null));
        logs.add(session("papers", "14:00", 60, 60, 100.0, 3, 4, null));
        logs.add(session("lunch", "13:00", 45, 40, null, null, null, 4));
        logs.add(session("dinner", "19:30", 45, 45, null, null, null, 3));
        return logs;
    }

    @Test
    public void hourlyRatingsFitTheBodyClockAndSkipConfoundedOnes() {
        Config cfg = ParityHarness.build(DAY);
        Calibrator.Outcome out = Calibrator.calibrate(cfg.copy(), aDay());

        // 16 hourly ratings + 2 session focus ratings are clean; the caffeine one is not.
        assertEquals(18, out.cleanObservations());
        assertEquals(19, out.totalObservations());
        assertTrue(out.report().stream().anyMatch(c ->
            c.subject().equals("Body clock") && c.detail().startsWith("fitted on")));
        assertFalse(Double.isNaN(out.params().a0));
    }

    @Test
    public void reportedSessionsMoveTheirActivity() {
        Config cfg = ParityHarness.build(DAY);
        double q0 = cfg.activity("papers").q;
        double rec0 = cfg.activity("lunch").recovery;
        Calibrator.Outcome out = Calibrator.calibrate(cfg.copy(), aDay());

        Activity papers = out.activities().stream().filter(a -> a.id.equals("papers")).findFirst().get();
        assertTrue("quality moved", papers.q != q0);
        assertTrue(papers.q > 0.1 && papers.q < 2);
        Activity lunch = out.activities().stream().filter(a -> a.id.equals("lunch")).findFirst().get();
        assertEquals("one restore rating is not enough to move recovery", rec0, lunch.recovery, 1e-9);
    }

    @Test
    public void aSkippedBlockTeachesNothing() {
        Config cfg = ParityHarness.build(DAY);
        List<LogEntry> with = aDay();
        with.add(skipped("papers", 90));
        with.add(skipped("lunch", 45));

        Calibrator.Outcome a = Calibrator.calibrate(cfg.copy(), aDay());
        Calibrator.Outcome b = Calibrator.calibrate(cfg.copy(), with);

        Activity pa = a.activities().stream().filter(x -> x.id.equals("papers")).findFirst().get();
        Activity pb = b.activities().stream().filter(x -> x.id.equals("papers")).findFirst().get();
        assertEquals(pa.q, pb.q, 1e-12);
        assertEquals(pa.load, pb.load, 1e-12);
        assertEquals(a.cleanObservations(), b.cleanObservations());
        assertEquals(a.params().phi, b.params().phi);
    }
}
