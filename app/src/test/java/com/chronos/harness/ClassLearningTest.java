package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.Calibrator;
import com.chronos.engine.Slots;
import com.chronos.model.Commitment;
import com.chronos.model.Config;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.LogEntry;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;

/** A class rated afterwards teaches that session's mental and physical figures, each from its own rating. */
public class ClassLearningTest {

    @Test
    public void aClassRatingMovesItsOwnSessionMindAndBodyApart() {
        LocalDate day = LocalDate.of(2026, 10, 25);           // a Sunday: Slots.dow = 1
        Config cfg = ParityHarness.build(day);
        Commitment c = cfg.commitments.stream().filter(k -> k.id.equals("c1")).findFirst().orElseThrow();
        int dow = Slots.dow(day);
        Commitment.Session s = c.sessions.stream().filter(x -> x.day == dow).findFirst().orElse(null);
        if (s == null) { s = new Commitment.Session(dow, "10:00", "12:00"); c.sessions.add(s); }
        s.load = 0.4; s.recovery = 0.0; s.physLoad = 0.1; s.physRecovery = 0.0;

        LogEntry e = new LogEntry();
        e.id = "class|" + day + "|c1|" + Slots.parse(s.start);
        e.kind = LogKind.CLASS;
        e.date = day;
        e.at = s.start;
        e.ts = 1_000_000L;
        e.comId = "c1";
        e.actual = 120;
        e.planned = 120;
        e.mentalAfter = 5;      // spent
        e.physicalAfter = 1;    // fresh

        Calibrator.Outcome out = Calibrator.calibrate(cfg.copy(), List.of(e));
        Commitment learned = out.commitments().stream().filter(k -> k.id.equals("c1")).findFirst().orElseThrow();
        Commitment.Session after = learned.sessionAt(dow, Slots.parse(s.start));
        assertTrue("mental load should rise, was 0.4 now " + after.load, after.load > 0.4);
        assertNotEquals(0.4, after.load, 1e-9);
        // Physically fresh: the physical side does not rise.
        assertTrue("physical load should not rise, now " + after.physLoad, after.physLoad <= 0.1 + 1e-9);
        // Other sessions are untouched.
        for (Commitment.Session o : learned.sessions)
            if (o != after) assertEquals(o.load, c.sessions.get(learned.sessions.indexOf(o)).load);
    }
}
