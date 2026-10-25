package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.DaySequencer;
import com.chronos.engine.HorizonPlanner;
import com.chronos.engine.Sacrifice;
import com.chronos.engine.Slots;
import com.chronos.model.Activity;
import com.chronos.model.Block;
import com.chronos.model.Config;
import com.chronos.model.Goal;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Cutting marked activities to make a deadline fit: only before the deadline,
 * only as much as is missing, "first" before "last resort", and never an
 * activity nobody allowed to be cut.
 */
public class SacrificeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26);

    /** The harness week with the literature review squeezed into ten days. */
    private static Config tight() {
        Config cfg = ParityHarness.build(TODAY);
        Goal lit = cfg.goal("lit");
        lit.deadline = TODAY.plusDays(9);
        lit.totalUnits = 60.0;      // ~90h of reading in ten days: it cannot all fit
        lit.maxPerDayH = 12.0;
        return cfg;
    }

    private static double missing(HorizonPlanner.Plan plan, String name) {
        return Sacrifice.missingH(plan, name);
    }

    @Test
    public void nothingIsCutUnlessAllowed() {
        Config cfg = tight();
        HorizonPlanner.Plan plan = HorizonPlanner.plan(cfg, TODAY);
        String name = cfg.goal("lit").name;
        assertTrue("the setup must actually be short", missing(plan, name) > 0.5);
        assertFalse(Sacrifice.anyCuttable(cfg));
        List<Map<String, Double>> cuts = Sacrifice.extend(cfg, TODAY, plan, List.of(), name);
        assertTrue(Sacrifice.isEmpty(cuts));
    }

    @Test
    public void cutsLandBeforeTheDeadlineAndRecoverHours() {
        Config cfg = tight();
        cfg.activity("dinner").sacrifice = Activity.SACRIFICE_LAST;
        cfg.activity("lunch").sacrifice = Activity.SACRIFICE_FIRST;
        cfg.activity("lunch").keepMin = 15;
        String name = cfg.goal("lit").name;

        HorizonPlanner.Plan plan = HorizonPlanner.plan(cfg, TODAY);
        double before = missing(plan, name);
        List<Map<String, Double>> cuts = Sacrifice.extend(cfg, TODAY, plan, List.of(), name);
        assertFalse(Sacrifice.isEmpty(cuts));

        for (int i = 10; i < cuts.size(); i++)
            assertTrue("nothing after the deadline is touched", cuts.get(i).isEmpty());
        for (Map<String, Double> day : cuts) {
            // "first" is used up before any "last resort" cut is made
            if (day.containsKey("dinner"))
                for (int i = 0; i < 10; i++) assertTrue(cuts.get(i).containsKey("lunch"));
            if (day.containsKey("lunch")) assertEquals(15.0, day.get("lunch"), 1e-9);
        }

        HorizonPlanner.Plan after = HorizonPlanner.plan(cfg, TODAY, Map.of(), cuts);
        System.out.printf("  missing %.1fh -> %.1fh with %d cuts%n", before, missing(after, name),
            cuts.stream().mapToInt(Map::size).sum());
        assertTrue("the cuts recover hours", missing(after, name) < before - 0.1);
    }

    @Test
    public void onlyAsManyAsNeeded() {
        Config cfg = tight();
        cfg.goal("lit").totalUnits = 40.0;               // short by a little, not a lot
        cfg.activity("lunch").sacrifice = Activity.SACRIFICE_FIRST;
        String name = cfg.goal("lit").name;
        HorizonPlanner.Plan plan = HorizonPlanner.plan(cfg, TODAY);
        double before = missing(plan, name);
        if (before <= 0.05) return;                        // fits already: nothing to show
        List<Map<String, Double>> cuts = Sacrifice.extend(cfg, TODAY, plan, List.of(), name);
        int n = cuts.stream().mapToInt(Map::size).sum();
        double perCut = 30 / 60.0 * (1 - cfg.params.slackRatio);  // lunch is 30 min
        assertTrue("no more cuts than the missing hours call for",
            n <= Math.ceil(before / perCut));
    }

    @Test
    public void theDayLeavesOutACutUpkeepBlock() {
        Config cfg = tight();
        int dow = Slots.dow(TODAY);
        DaySequencer.Result normal = DaySequencer.run(cfg, dow, Map.of(), 7);
        DaySequencer.Result cut = DaySequencer.run(cfg, dow, Map.of(), 7, Map.of("lunch", 0.0));
        assertTrue(normal.blocks().stream().anyMatch(b -> "Lunch".equals(b.name)));
        assertFalse(cut.blocks().stream().anyMatch(b -> "Lunch".equals(b.name)));
        assertTrue(cut.warnings().stream().anyMatch(w -> w.contains("Lunch")));

        DaySequencer.Result shorter = DaySequencer.run(cfg, dow, Map.of(), 7, Map.of("dinner", 15.0));
        Block dinner = shorter.blocks().stream().filter(b -> "Dinner".equals(b.name)).findFirst().get();
        assertEquals(15, dinner.dur * Slots.SLOT_MIN);
    }
}
