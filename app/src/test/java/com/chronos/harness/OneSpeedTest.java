package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.Calibrator;
import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.Goal;
import com.chronos.model.LogEntry;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;

/**
 * One speed (8 Nov 2026): once a goal's hours per unit are the speed its log measured, a session logged
 * with its units leaves the activity's quality alone, so the same slowness is not planned twice.
 */
public class OneSpeedTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    static LogEntry slowSession(Double units) {
        LogEntry e = new LogEntry();
        e.id = "session|" + DAY + "|papers|1";
        e.kind = LogKind.SESSION;
        e.date = DAY;
        e.at = "10:00";
        e.ts = 1_000_000L;
        e.actId = "papers";
        e.planned = 60;
        e.actual = 120;          // twice the planned time…
        e.attain = 100.0;        // …for all of it
        e.units = units;
        return e;
    }

    static double qAfter(boolean speedFromLog, Double units) {
        Config cfg = ParityHarness.build(DAY);
        Goal lit = cfg.goal("lit");
        lit.totalUnits = 20.0; lit.doneUnits = 0.0; lit.hoursPerUnit = 1.0; lit.unit = "paper";
        lit.speedFromLog = speedFromLog;
        Activity a = cfg.activities.stream().filter(x -> x.id.equals("papers")).findFirst().orElseThrow();
        a.q = 1.0;
        Calibrator.Outcome out = Calibrator.calibrate(cfg.copy(), List.of(slowSession(units)));
        return out.activities().stream().filter(x -> x.id.equals("papers")).findFirst().orElseThrow().q;
    }

    @Test
    public void beforeTheLearnedSpeedIsTakenAnOverrunLowersQuality() {
        assertTrue(qAfter(false, 1.0) < 1.0);
    }

    @Test
    public void afterItASessionCountedInUnitsSpeaksOnlyThroughTheHoursPerUnit() {
        assertEquals(1.0, qAfter(true, 1.0), 1e-9);
    }

    @Test
    public void aSessionWithoutUnitsStillTeachesQuality() {
        // Its minutes are in no hours per unit, so quality is the only place they can go.
        assertTrue(qAfter(true, null) < 1.0);
    }

    @Test
    public void theFlagSurvivesACopy() {
        Goal g = new Goal();
        g.speedFromLog = true;
        assertTrue(g.copy().speedFromLog);
    }
}
