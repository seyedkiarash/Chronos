package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.chronos.data.Codec;
import com.chronos.engine.Calibrator;
import com.chronos.engine.Energy;
import com.chronos.engine.FatigueSplit;
import com.chronos.engine.Slots;
import com.chronos.model.Activity;
import com.chronos.model.Block;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.LogEntry;
import com.chronos.model.Params;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Fatigue in two kinds: mental and physical, apart and acting on each other. */
public class TwoFatiguesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    private static Config cfg() {
        Config c = ParityHarness.build(DAY);
        for (Activity a : c.activities) FatigueSplit.fill(a);
        FatigueSplit.fill(c.params);
        return c;
    }

    private static Block block(int start, int slots, double load, double rec, double pLoad, double pRec) {
        Block b = new Block();
        b.start = start; b.dur = slots; b.load = load; b.recovery = rec; b.physLoad = pLoad; b.physRecovery = pRec;
        return b;
    }

    @Test
    public void aWorkoutTiresTheBodyAndRestsTheMind() {
        Params p = cfg().params;
        double[] h = Energy.homeostatic(p);
        int s = Slots.parse("16:00");
        Energy.Trace study = Energy.simulate(List.of(block(s, 8, 0.7, 0, 0.05, 0)), p, h);
        Energy.Trace gym = Energy.simulate(List.of(block(s, 8, 0.05, 0.3, 0.6, 0)), p, h);
        int end = s + 8;
        assertTrue("studying tires the mind more", study.fatigue()[end] > gym.fatigue()[end]);
        assertTrue("the gym tires the body more", gym.physical()[end] > study.physical()[end]);
    }

    @Test
    public void aTiredBodyLowersThinkingOnlyPastItsLine() {
        Params p = cfg().params;
        p.aP = 0.3; p.pTheta = 0.5;
        int slot = Slots.parse("11:00");
        double fresh = Energy.capacity(p, slot, 0.3, 0.2, 0.3);
        double atLine = Energy.capacity(p, slot, 0.3, 0.2, 0.5);
        double spent = Energy.capacity(p, slot, 0.3, 0.2, 0.9);
        assertEquals("below the line the body does not matter", fresh, atLine, 1e-12);
        assertEquals(atLine - 0.3 * 0.4, spent, 1e-12);
    }

    @Test
    public void aTiredMindMakesEffortCostMore() {
        Params p = cfg().params;
        p.kMP = 0.5;
        double[] h = Energy.homeostatic(p);
        int s = Slots.parse("17:00");
        Energy.Trace rested = Energy.simulate(List.of(block(s, 4, 0, 0, 0.6, 0)), p, h);
        Energy.Trace drained = Energy.simulate(List.of(block(s - 16, 16, 0.8, 0, 0, 0), block(s, 4, 0, 0, 0.6, 0)), p, h);
        int end = s + 4;
        double gainRested = rested.physical()[end] - rested.physical()[s];
        double gainDrained = drained.physical()[end] - drained.physical()[s];
        assertTrue("the same workout costs more after hard thinking", gainDrained > gainRested);
    }

    private static LogEntry session(Activity a, String at, int minutes, Integer mind, Integer body, Integer drained) {
        LogEntry e = new LogEntry();
        e.id = "session|" + DAY + "|" + a.id + "|" + at;
        e.kind = LogKind.SESSION;
        e.date = DAY;
        e.at = at;
        e.ts = 1;
        e.actId = a.id;
        e.planned = minutes;
        e.actual = minutes;
        e.attain = 100.0;
        e.focus = 3;
        e.mentalAfter = mind;
        e.physicalAfter = body;
        e.fatigueAfter = drained;
        e.tags = new ArrayList<>();
        return e;
    }

    @Test
    public void eachScaleTeachesItsOwnLoad() {
        Config c = cfg();
        Activity work = c.activities.stream().filter(a -> a.kind == ActivityKind.GOAL).findFirst().orElseThrow();
        double mind0 = work.load, body0 = work.physLoad;
        // Physically spent, mentally fresh: the body's load rises, the mind's falls.
        List<LogEntry> logs = List.of(session(work, "09:00", 60, 1, 5, null), session(work, "15:00", 60, 1, 5, null));
        Activity after = Calibrator.calibrate(c.copy(), logs).activities().stream()
            .filter(a -> a.id.equals(work.id)).findFirst().orElseThrow();
        assertTrue("physical load up", after.physLoad > body0);
        assertTrue("mental load down", after.load < mind0);
    }

    @Test
    public void anOldSingleRatingIsSplitByHowPhysicalTheActivityIs() {
        Config c = cfg();
        Activity work = c.activities.stream().filter(a -> a.kind == ActivityKind.GOAL).findFirst().orElseThrow();
        work.load = 0.4; work.physLoad = 0.0;          // all mental: an old "drained" rating is all mental
        double body0 = work.physLoad;
        List<LogEntry> logs = List.of(session(work, "09:00", 60, null, null, 5), session(work, "15:00", 60, null, null, 5));
        Activity after = Calibrator.calibrate(c.copy(), logs).activities().stream()
            .filter(a -> a.id.equals(work.id)).findFirst().orElseThrow();
        assertTrue("mental load moved", after.load > 0.4);
        assertEquals("physical load untouched", body0, after.physLoad, 1e-12);
    }

    @Test
    public void theSuggestedSplitReadsWhatTheActivityIs() {
        double[] gym = FatigueSplit.suggest("Workout", false, ActivityKind.MAINTENANCE, 0.6, 0.0);
        double[] study = FatigueSplit.suggest("course videos", false, ActivityKind.GOAL, 0.6, 0.0);
        double[] walk = FatigueSplit.suggest("پیاده‌روی", false, ActivityKind.MAINTENANCE, 0.0, 0.5);
        assertTrue(gym[2] > gym[0]);
        assertTrue(study[0] > study[2]);
        assertTrue("a walk rests the mind", walk[1] >= 0.3);
        Activity a = new Activity();
        a.name = "Gym"; a.kind = ActivityKind.MAINTENANCE; a.load = 0.5; a.recovery = 0.0;
        assertTrue(FatigueSplit.fill(a));
        assertTrue(!a.splitConfirmed && a.physLoad > a.load);
    }

    @Test
    public void storedDaysKeepThePhysicalFiguresAndOldOnesStillRead() {
        Block b = block(10, 4, 0.5, 0.1, 0.3, 0.05);
        b.name = "x";
        Block back = Codec.parseBlocks(Codec.blocks(List.of(b))).get(0);
        assertEquals(0.3, back.physLoad, 1e-12);
        assertEquals(0.05, back.physRecovery, 1e-12);
        String old = Codec.blocks(List.of(b));
        // A day stored before fatigue had two kinds: without the physical pair, nor the leisure pair after it (v15).
        for (int i = 0; i < 4; i++) old = old.substring(0, old.lastIndexOf('\u001F'));
        Block legacy = Codec.parseBlocks(old).get(0);
        assertEquals(0.5, legacy.load, 1e-12);
        assertEquals(0.0, legacy.physLoad, 1e-12);
    }
}
