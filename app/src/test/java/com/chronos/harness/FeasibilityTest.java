package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.chronos.data.Codec;
import com.chronos.engine.Feasibility;
import com.chronos.engine.FixedFrame;
import com.chronos.engine.Slots;
import com.chronos.model.Activity;
import com.chronos.model.Block;
import com.chronos.model.Commitment;
import com.chronos.model.Config;
import com.chronos.model.Goal;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;

/**
 * The deadline check, and per-session fatigue on a fixed commitment.
 *
 * The configuration comes from {@link ParityHarness}, so these run on a plain
 * JVM with no device. The deficit case is built by narrowing one goal until it
 * cannot finish, which is the situation the check exists to catch: the horizon
 * paces hours the days have no room for.
 */
public class FeasibilityTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-09-22");

    @Test
    public void aReachableConfigurationReportsNothing() {
        Feasibility.Report r = Feasibility.analyse(ParityHarness.build(TODAY), TODAY);
        for (Feasibility.Deficit d : r.deficits())
            System.out.printf("  %s short %.1fh%n", d.goalName(), d.missingH());
        assertTrue("the harness configuration is meant to be solvable", r.clean());
    }

    @Test
    public void anImpossibleGoalIsReportedWithRemediesThatWereMeasured() {
        Config cfg = ParityHarness.build(TODAY);
        Goal g = cfg.goals.get(0);
        // Far more work than the two weeks before the deadline can hold.
        g.totalUnits = 200.0;
        g.doneUnits = 0.0;
        g.deadline = TODAY.plusDays(14);

        Feasibility.Report r = Feasibility.analyse(cfg, TODAY);
        assertFalse("an unreachable deadline must be reported", r.clean());

        Feasibility.Deficit d = r.deficits().get(0);
        System.out.printf("%s by %s: needs %.1fh, can take %.1fh, short %.1fh [%s]%n",
            d.goalName(), d.deadline(), d.neededH(), d.deliverableH(), d.missingH(), d.cause());
        assertEquals(g.id, d.goalId());
        assertTrue("the gap is the difference, not a guess",
            Math.abs(d.missingH() - (d.neededH() - d.deliverableH())) < 1e-6);

        List<Feasibility.Remedy> remedies = r.remedies();
        assertFalse("a deficit this large must have something that helps", remedies.isEmpty());
        for (Feasibility.Remedy m : remedies)
            System.out.printf("  [%s] +%.1fh%s  %s%n", m.cost(), m.recoveredH(),
                m.clearsIt() ? " CLEARS" : "", m.what());

        // Every remedy recovers real hours, and the cheapest cost comes first:
        // the point of the ordering is that rearranging beats spending fatigue.
        int previous = -1;
        for (Feasibility.Remedy m : remedies) {
            assertTrue("a remedy must recover hours to be listed", m.recoveredH() > 0.25);
            assertTrue("remedies must be ordered by what they cost",
                m.cost().ordinal() >= previous);
            previous = m.cost().ordinal();
        }
    }

    /** Room is zero on a weekday the goal's activity does not run. */
    @Test
    public void roomIsZeroOnADayTheActivityIsNotAllowed() {
        Config cfg = ParityHarness.build(TODAY);
        Goal g = cfg.goals.get(0);
        for (Activity a : cfg.activitiesFor(g.id)) a.days.remove(3);
        assertEquals(0.0, Feasibility.roomH(cfg, g.id, 3), 1e-9);
        assertTrue("and positive on a day it does run",
            Feasibility.roomH(cfg, g.id, 1) > 0);
    }

    // ------------------------------------------- per-session load and recovery

    @Test
    public void aSessionCanCarryItsOwnLoad() {
        Commitment c = new Commitment();
        c.load = 0.4;
        c.recovery = 0.1;
        Commitment.Session lecture = new Commitment.Session(1, "09:00", "11:00");
        Commitment.Session lab = new Commitment.Session(1, "14:00", "18:00");
        lab.load = 0.9;

        assertEquals("unset inherits the commitment", 0.4, lecture.loadOr(c.load), 1e-9);
        assertEquals("set overrides it", 0.9, lab.loadOr(c.load), 1e-9);
        assertEquals("recovery still inherits", 0.1, lab.recoveryOr(c.recovery), 1e-9);
    }

    @Test
    public void perSessionFiguresSurviveStorageAndOldRowsStillParse() {
        Commitment.Session s = new Commitment.Session(2, "08:00", "12:00");
        s.load = 0.85;
        s.recovery = 0.05;
        Commitment.Session plain = new Commitment.Session(3, "13:00", "15:00");

        List<Commitment.Session> back = Codec.parseSessions(Codec.sessions(List.of(s, plain)));
        assertEquals(2, back.size());
        assertEquals(0.85, back.get(0).load, 1e-9);
        assertEquals(0.05, back.get(0).recovery, 1e-9);
        assertNull("an inheriting session stays null", back.get(1).load);
        assertEquals("13:00", back.get(1).start);

        // Rows written before sessions had their own figures.
        List<Commitment.Session> old = Codec.parseSessions("0|09:00|13:00;1|10:00|13:00");
        assertEquals(2, old.size());
        assertNull(old.get(0).load);
        assertEquals("10:00", old.get(1).start);
    }

    /** The figure the fixed frame hands the fatigue simulation is the session's. */
    @Test
    public void theFixedFrameUsesTheSessionsOwnLoad() {
        Config cfg = ParityHarness.build(TODAY);
        Commitment fixed = null;
        for (Commitment c : cfg.commitments) if (!c.sessions.isEmpty()) { fixed = c; break; }
        assertTrue("the harness has a fixed commitment", fixed != null);

        Commitment.Session first = fixed.sessions.get(0);
        first.load = 0.97;
        FixedFrame.Frame frame = FixedFrame.of(cfg.params, cfg.commitments, first.day);

        double found = -1;
        for (Block b : frame.items())
            if (b.start == Slots.parse(first.start)) found = b.load;
        assertEquals("the session's own load reaches the day", 0.97, found, 1e-9);
    }
}
