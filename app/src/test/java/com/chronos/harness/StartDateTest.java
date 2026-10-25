package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.HorizonPlanner;
import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.GoalMode;
import com.chronos.model.Goal;
import com.chronos.validate.Readiness;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Map;

/** An activity with a start date is not in the plan before it, and must start before its deadline. */
public class StartDateTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    private static Goal firstGoal(Config cfg) {
        for (Goal g : cfg.goals) if (!cfg.activitiesFor(g.id).isEmpty()) return g;
        throw new AssertionError("the harness has a goal with activities");
    }

    @Test
    public void nothingIsAllocatedBeforeTheStart() {
        Config cfg = ParityHarness.build(DAY);
        Goal g = firstGoal(cfg);
        LocalDate start = DAY.plusDays(5);
        for (Activity a : cfg.activitiesFor(g.id)) a.startDate = start;

        HorizonPlanner.Plan plan = HorizonPlanner.plan(cfg, DAY);
        for (int i = 0; i < 5; i++)
            assertFalse("day " + i + " is before the start", plan.allocation().get(i).containsKey(g.id));
        double after = 0;
        for (int i = 5; i < plan.allocation().size(); i++) after += plan.allocation().get(i).getOrDefault(g.id, 0.0);
        assertTrue("the goal is still served from the start on", after > 0);
    }

    @Test
    public void upkeepThatHasNotStartedTakesNoTime() {
        Config cfg = ParityHarness.build(DAY);
        Activity upkeep = null;
        for (Activity a : cfg.activities)
            if (a.kind == ActivityKind.MAINTENANCE && !a.restOnly && a.days.contains(com.chronos.engine.Slots.dow(DAY))) upkeep = a;
        if (upkeep == null) return;
        double before = HorizonPlanner.capacityOf(cfg, DAY, 0).upkeepH();
        upkeep.startDate = DAY.plusDays(1);
        assertTrue(HorizonPlanner.capacityOf(cfg, DAY, 0).upkeepH() < before);
        assertEquals(before, HorizonPlanner.capacityOf(cfg, DAY.plusDays(7), 7).upkeepH(), 1e-9);
    }

    @Test
    public void aStartOnOrAfterTheDeadlineBlocks() {
        Config cfg = ParityHarness.build(DAY);
        Goal g = null;
        for (Goal x : cfg.goals)
            if (x.mode == GoalMode.CLOSED && x.deadline != null && !cfg.activitiesFor(x.id).isEmpty()) g = x;
        if (g == null) return;
        Activity a = cfg.activitiesFor(g.id).get(0);
        a.startDate = g.deadline;
        boolean blocked = Readiness.check(cfg, DAY).stream().anyMatch(i ->
            i.severity() == Readiness.Severity.BLOCKING && i.what().contains("not before its goal's deadline"));
        assertTrue(blocked);
        a.startDate = g.deadline.minusDays(1);
        assertFalse(Readiness.check(cfg, DAY).stream().anyMatch(i -> i.what().contains("not before its goal's deadline")));
    }

    @Test
    public void configOnShareseverythingButTheWaitingActivities() {
        Config cfg = ParityHarness.build(DAY);
        assertTrue("nothing waits: the same object", cfg.on(DAY) == cfg);
        cfg.activities.get(0).startDate = DAY.plusDays(3);
        assertEquals(cfg.activities.size() - 1, cfg.on(DAY).activities.size());
        assertEquals(cfg.activities.size(), cfg.on(DAY.plusDays(3)).activities.size());
        assertEquals(Map.of(), Map.of());
    }
}
