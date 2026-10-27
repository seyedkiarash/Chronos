package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.Priorities;
import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.GoalMode;
import com.chronos.model.Goal;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Priorities as levels and an order (26 Oct 2026): placed by the app when new, weighted by level and place. */
public class PrioritiesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    @Test
    public void theFirstTimeEarlierComparisonsBecomeLevelsInTheSameOrder() {
        Config cfg = ParityHarness.build(TODAY);        // weights 0.42, 0.38, 0.20 from comparisons
        assertTrue(Priorities.settle(cfg, TODAY));
        Goal lit = cfg.goal("lit"), courses = cfg.goal("courses"), english = cfg.goal("english");
        assertEquals(Priorities.CRITICAL, (int) lit.tier);
        assertEquals(Priorities.CRITICAL, (int) courses.tier);
        assertEquals(Priorities.IMPORTANT, (int) english.tier);
        assertEquals(0, (int) lit.rank);
        assertEquals(1, (int) courses.rank);
        assertTrue(lit.tierAuto);
        assertTrue(lit.weight > courses.weight && courses.weight > english.weight);
        assertEquals(1.0, lit.weight + courses.weight + english.weight, 1e-9);
        assertFalse("settled twice changes nothing", Priorities.settle(cfg, TODAY));
    }

    @Test
    public void levelsNeverOverlap() {
        Config cfg = ParityHarness.build(TODAY);
        for (int i = 0; i < 5; i++) cfg.goals.add(target("n" + i, Priorities.NORMAL, i));
        cfg.goals.add(target("imp", Priorities.IMPORTANT, 0));
        cfg.goal("lit").tier = Priorities.IMPORTANT; cfg.goal("lit").rank = 1;
        cfg.goal("courses").tier = Priorities.OPTIONAL; cfg.goal("courses").rank = 0;
        cfg.goal("english").tier = Priorities.OPTIONAL; cfg.goal("english").rank = 1;
        Priorities.settle(cfg, TODAY);
        double lowestImportant = Math.min(cfg.goal("imp").weight, cfg.goal("lit").weight);
        for (int i = 0; i < 5; i++) assertTrue(cfg.goal("n" + i).weight < lowestImportant);
        assertTrue(cfg.goal("n4").weight > cfg.goal("courses").weight);
        assertTrue("the top of a level weighs more than its bottom", cfg.goal("n0").weight > cfg.goal("n4").weight);
    }

    @Test
    public void aNewTargetIsPlacedByTheAppWithItsReasons() {
        Config cfg = ParityHarness.build(TODAY);
        Priorities.settle(cfg, TODAY);
        cfg.goals.forEach(g -> g.tierAuto = false);

        // A soft target due in ten days, under no goal you have levelled: important, before later deadlines.
        Goal g = target("report", null, null);
        g.mode = GoalMode.CLOSED; g.hard = false; g.deadline = TODAY.plusDays(10); g.totalUnits = 3.0;
        g.groupName = "report";
        cfg.goals.add(g);
        Priorities.Suggestion s = Priorities.suggest(cfg, g, TODAY);
        assertEquals(Priorities.IMPORTANT, s.tier());
        assertTrue(s.why().get(0), s.why().get(0).contains("10 days"));
        Priorities.settle(cfg, TODAY);
        assertEquals(Priorities.IMPORTANT, (int) g.tier);
        assertTrue(g.tierAuto);
        List<Goal> important = Priorities.level(cfg, Priorities.IMPORTANT);
        assertEquals("due before the open English target, so above it", "report", important.get(0).id);

        // Another activity of a goal you levelled takes that goal's level.
        Goal sibling = target("lit2", null, null);
        sibling.groupName = cfg.goal("lit").groupName == null ? "lit" : cfg.goal("lit").groupName;
        cfg.goal("lit").groupName = sibling.groupName;
        sibling.mode = GoalMode.OPEN;
        cfg.goals.add(sibling);
        assertEquals((int) cfg.goal("lit").tier, Priorities.suggest(cfg, sibling, TODAY).tier());
    }

    @Test
    public void howItMayBeCutSaysHowMuchItMatters() {
        Config cfg = ParityHarness.build(TODAY);
        Goal g = target("chores", null, null);
        g.mode = GoalMode.OPEN; g.groupName = "alone";
        cfg.goals.add(g);
        Activity a = new Activity();
        a.id = "a-chores"; a.name = "chores work"; a.kind = ActivityKind.GOAL; a.goalId = "chores";
        cfg.activities.add(a);
        a.sacrifice = Activity.SACRIFICE_FIRST;
        assertEquals(Priorities.OPTIONAL, Priorities.suggest(cfg, g, TODAY).tier());
        a.sacrifice = Activity.SACRIFICE_NEVER;
        assertEquals(Priorities.IMPORTANT, Priorities.suggest(cfg, g, TODAY).tier());
    }

    @Test
    public void targetsOutOfPlayTakeNothingFromTheOthers() {
        Config cfg = ParityHarness.build(TODAY);
        Priorities.settle(cfg, TODAY);
        cfg.goal("courses").deadline = TODAY.minusDays(1);
        Map<String, Double> w = Priorities.weights(cfg, TODAY);
        assertEquals(1.0, w.get("lit") + w.get("english"), 1e-9);
    }

    private static Goal target(String id, Integer tier, Integer rank) {
        Goal g = new Goal();
        g.id = id; g.name = id; g.mode = GoalMode.OPEN; g.weeklyFloorH = 2.0; g.hoursPerUnit = 1.0;
        g.tier = tier; g.rank = rank;
        return g;
    }
}
