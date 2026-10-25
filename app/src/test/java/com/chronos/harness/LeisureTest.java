package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.Calibrator;
import com.chronos.engine.DaySequencer;
import com.chronos.engine.Energy;
import com.chronos.engine.HorizonPlanner;
import com.chronos.engine.Slots;
import com.chronos.model.Activity;
import com.chronos.model.Block;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.LogEntry;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Leisure (your request of 25 Oct 2026): the rests are chosen from it, the day never passes the
 * ceiling of stimulation, and how stimulating each is comes from its after-effects in the log.
 */
public class LeisureTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 30);   // a Friday: no classes

    private static Activity leisure(String id, double dopamine, int min, int max, int perDay) {
        int[] all = {0, 1, 2, 3, 4, 5, 6};
        Activity a = ParityHarness.act(id, id, ActivityKind.MAINTENANCE, null, all, min, max, 0.0, 0.4, 1, 0, 999,
            new String[][]{{"08:00", "23:00"}}, false, false);
        a.physLoad = 0.0; a.physRecovery = 0.2;
        a.leisure = true;
        a.dopamine = dopamine;
        a.maxBlocks = perDay;
        return a;
    }

    private static List<Block> leisureIn(DaySequencer.Result r) {
        List<Block> out = new ArrayList<>();
        for (Block b : r.blocks()) if (b.leisure) out.add(b);
        return out;
    }

    private static double peak(Config cfg, DaySequencer.Result r) {
        return Energy.simulate(r.blocks(), cfg.params, Energy.homeostatic(cfg.params)).peakDopamine();
    }

    @Test
    public void restsAreChosenFromLeisureAndStayUnderTheCeiling() {
        Config cfg = ParityHarness.build(DAY);
        cfg.activities.add(leisure("film", 0.5, 30, 90, 1));
        cfg.activities.add(leisure("videos", 1.4, 10, 30, 2));
        cfg.activities.add(leisure("walk", 0.15, 15, 45, 1));
        int dow = Slots.dow(DAY);
        DaySequencer.Result r = DaySequencer.run(cfg, dow, Map.of("lit", 3.0, "english", 1.0), 7L);
        List<Block> fun = leisureIn(r);
        assertTrue("some free time becomes a rest from your leisure", !fun.isEmpty());
        assertTrue("the ceiling holds: " + peak(cfg, r), peak(cfg, r) <= cfg.params.dopCapOr() + 1e-9);
        for (Block b : fun) {
            assertNotNull("a rest says why it is there", b.why);
            assertTrue(b.dur * Slots.SLOT_MIN >= b.act.minBlock && b.dur * Slots.SLOT_MIN <= b.act.maxBlock);
        }
        // No leisure is planned more often than it allows.
        for (Activity a : cfg.activities) if (a.leisure)
            assertTrue(fun.stream().filter(b -> b.act.id.equals(a.id)).count() <= a.maxBlocks);
        // The goal work is what it would have been without leisure.
        Config plain = ParityHarness.build(DAY);
        DaySequencer.Result r0 = DaySequencer.run(plain, dow, Map.of("lit", 3.0, "english", 1.0), 7L);
        assertEquals(r0.elapsedMinutes(), r.elapsedMinutes(), 1e-9);
    }

    @Test
    public void aLowCeilingKeepsAStimulatingOneOut() {
        Config cfg = ParityHarness.build(DAY);
        cfg.activities.add(leisure("videos", 1.4, 10, 30, 2));
        cfg.params.dopCap = 0.1;
        DaySequencer.Result r = DaySequencer.run(cfg, Slots.dow(DAY), Map.of("lit", 3.0), 7L);
        assertTrue("ten minutes of it already pass a ceiling of 0.1", leisureIn(r).isEmpty());
        assertTrue("the free time stays open instead", r.gaps().stream().anyMatch(g -> "Open buffer".equals(g.name)));
    }

    @Test
    public void leisureTakesNothingFromTheHoursForGoals() {
        Config cfg = ParityHarness.build(DAY);
        HorizonPlanner.Plan before = HorizonPlanner.plan(cfg, DAY);
        cfg.activities.add(leisure("film", 0.5, 30, 120, 2));
        HorizonPlanner.Plan after = HorizonPlanner.plan(cfg, DAY);
        for (int i = 0; i < before.days().size(); i++)
            assertEquals(before.days().get(i).capacityH(), after.days().get(i).capacityH(), 1e-9);
    }

    @Test
    public void longWorkGetsABreakFromLeisure() {
        Config cfg = ParityHarness.build(DAY);
        cfg.activities.removeIf(a -> (a.kind == ActivityKind.GOAL && !a.id.equals("papers")) || a.id.equals("lunch") || a.id.equals("chores"));
        Activity papers = cfg.activity("papers");
        papers.windows.clear();
        papers.windows.add(new Activity.Window("08:00", "13:00"));
        papers.minBlock = 60; papers.maxBlock = 120;
        Config plain = cfg.copy();
        cfg.activities.add(leisure("walk", 0.15, 15, 30, 3));
        DaySequencer.Result r = DaySequencer.run(cfg, Slots.dow(DAY), Map.of("lit", 4.0), 3L);
        DaySequencer.Result r0 = DaySequencer.run(plain, Slots.dow(DAY), Map.of("lit", 4.0), 3L);
        StringBuilder day = new StringBuilder();
        for (Block x : r.blocks()) day.append(Slots.format(x.start)).append("-").append(Slots.format(x.start + x.dur)).append(" ").append(x.name).append("; ");
        List<Block> work = new ArrayList<>();
        for (Block b : r.blocks()) if (b.goalId != null) work.add(b);
        work.sort(Comparator.comparingInt(b -> b.start));
        // Goal work runs at most 100 minutes before something restful.
        int run = 0;
        for (int i = 0; i < work.size(); i++) {
            Block b = work.get(i);
            boolean joined = i > 0 && work.get(i - 1).start + work.get(i - 1).dur == b.start;
            run = (joined ? run : 0) + b.dur * Slots.SLOT_MIN;
            assertTrue("a run of " + run + " min with no pause: " + day, run <= DaySequencerBreak.LONGEST || b.dur * Slots.SLOT_MIN == run);
        }
        assertTrue("at least one rest from your leisure: " + day, r.blocks().stream().anyMatch(x -> x.leisure));
        assertEquals("the work delivered is not cut for the breaks: " + day, r0.elapsedMinutes(), r.elapsedMinutes(), 1e-9);
        for (Block x : r.blocks()) for (Block y : r.blocks())
            if (x != y && x.leisure && y.leisure && x.act.id.equals(y.act.id)) assertTrue(x.start + x.dur != y.start);
    }

    /** The longest run of goal work the breaks allow, from the sequencer. */
    private static final class DaySequencerBreak { static final int LONGEST = 100; }

    @Test
    public void stimulationIsLearnedFromItsAfterEffects() {
        Config cfg = ParityHarness.build(DAY);
        Activity games = leisure("games", 0.5, 20, 90, 1);
        cfg.activities.add(games);

        LogEntry hooked = session("games", 60, 5, 5, 5);
        Double high = Calibrator.stimulationSeen(hooked, cfg.params, null);
        LogEntry calm = session("games", 60, 1, 1, 1);
        assertEquals(0.0, Calibrator.stimulationSeen(calm, cfg.params, null), 1e-9);
        assertTrue("hard to stop, hard to start again, wanting more: well past the ceiling's rate", high > 1.0);
        assertNull("nothing said, nothing learned", Calibrator.stimulationSeen(session("games", 60, null, null, null), cfg.params, null));

        Calibrator.Outcome up = Calibrator.calibrate(cfg.copy(), List.of(hooked));
        Activity learned = up.activities().stream().filter(a -> a.id.equals("games")).findFirst().orElseThrow();
        assertTrue("moves up from 0.5, now " + learned.dopamine, learned.dopamine > 0.5);
        assertEquals(1, learned.dopamineSeen);

        Calibrator.Outcome down = Calibrator.calibrate(cfg.copy(), List.of(calm));
        Activity lower = down.activities().stream().filter(a -> a.id.equals("games")).findFirst().orElseThrow();
        assertTrue("moves down from 0.5, now " + lower.dopamine, lower.dopamine < 0.5);
    }

    @Test
    public void focusFallingAfterItCountsToo() {
        Config cfg = ParityHarness.build(DAY);
        cfg.activities.add(leisure("games", 0.5, 20, 90, 1));
        LogEntry g = session("games", 60, 3, 3, 3);
        g.at = "14:00";
        LogEntry before = goal("papers", "09:00", 4);
        LogEntry after = goal("papers", "15:05", 2);
        Double drop = Calibrator.focusDropAfter(g, List.of(g, before, after), cfg);
        assertEquals(2.0, drop, 1e-9);
        assertTrue(Calibrator.stimulationSeen(g, cfg.params, drop) > Calibrator.stimulationSeen(g, cfg.params, null));
    }

    private static LogEntry session(String act, int minutes, Integer stop, Integer next, Integer more) {
        LogEntry l = new LogEntry();
        l.id = "session|" + DAY + "|" + act + "|64";
        l.kind = LogKind.SESSION;
        l.date = DAY;
        l.at = "16:00";
        l.ts = 1_000_000L;
        l.actId = act;
        l.planned = minutes; l.actual = minutes;
        l.restored = 3;
        l.stopHard = stop; l.nextStart = next; l.craving = more;
        return l;
    }

    private static LogEntry goal(String act, String at, int focus) {
        LogEntry l = new LogEntry();
        l.id = "session|" + DAY + "|" + act + "|" + Slots.parse(at);
        l.kind = LogKind.SESSION;
        l.date = DAY;
        l.at = at;
        l.ts = 1_000_000L;
        l.actId = act;
        l.planned = 60; l.actual = 60; l.focus = focus; l.attain = 100.0;
        return l;
    }
}
