package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.Ahp;
import com.chronos.engine.Umbrellas;
import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.GoalMode;
import com.chronos.model.Goal;
import com.chronos.validate.Readiness;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Goals become umbrellas: one target per goal activity, a shared goal split with its numbers left to you. */
public class UmbrellasTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 17);

    @Test
    public void aSharedGoalIsSplitOneTargetPerActivity() {
        Config cfg = ParityHarness.build(DAY);
        Goal shared = null;
        for (Goal g : cfg.goals) if (cfg.activitiesFor(g.id).size() == 1) { shared = g; break; }
        // Give that goal a second activity, as "reading courses" has two.
        Activity first = cfg.activitiesFor(shared.id).get(0);
        Activity second = first.copy();
        second.id = "second"; second.name = "second reading";
        cfg.activities.add(second);
        String before = shared.name;
        Goal other = cfg.goals.stream().filter(g -> !g.id.equals(first.goalId)).findFirst().orElseThrow();
        Map<String, Double> cmp = new HashMap<>();
        cmp.put(shared.id + "|" + other.id, 2.0);
        double weight = shared.weight;
        int goalsBefore = cfg.goals.size();

        assertTrue(Umbrellas.apply(cfg, cmp));
        assertEquals(goalsBefore + 1, cfg.goals.size());
        Goal t1 = cfg.goal(first.goalId), t2 = cfg.goal(second.goalId);
        assertFalse("each activity has its own target", t1.id.equals(t2.id));
        assertEquals(before, t1.groupName);
        assertEquals(before, t2.groupName);
        assertEquals("the target is named after its activity", "second reading", t2.name);
        assertTrue(t1.note.contains(before));
        assertEquals(weight / 2, t2.weight, 1e-9);
        if (t1.mode == GoalMode.CLOSED) assertNull(t1.deadline); else assertNull(t1.weeklyFloorH);
        assertEquals("its share of the comparison", 1.0, cmp.get(t2.id + "|" + other.id), 1e-9);
        assertEquals(1.0, cmp.get(t1.id + "|" + other.id), 1e-9);

        // You fill them in: until then the planner waits.
        List<Readiness.Issue> issues = Readiness.check(cfg, DAY);
        assertTrue(issues.stream().anyMatch(i -> i.severity() == Readiness.Severity.BLOCKING && t2.id.equals(i.subjectId())));

        // Done once: nothing changes the second time.
        assertFalse(Umbrellas.apply(cfg, cmp));
        // Weights still come out for every target.
        List<String> ids = new ArrayList<>();
        cfg.goals.forEach(g -> ids.add(g.id));
        assertEquals(cfg.goals.size(), Ahp.of(ids, cmp).weights().size());
    }

    @Test
    public void aGoalWithOneActivityKeepsItsNumbersAndGetsItsGroup() {
        Config cfg = ParityHarness.build(DAY);
        Goal g = null;
        for (Goal x : cfg.goals) if (cfg.activitiesFor(x.id).size() == 1 && x.mode == GoalMode.CLOSED) { g = x; break; }
        if (g == null) return;
        String name = g.name; Double total = g.totalUnits;
        Activity a = cfg.activitiesFor(g.id).get(0);
        Umbrellas.apply(cfg, new HashMap<>());
        assertEquals(name, g.groupName);
        assertEquals(a.name.trim(), g.name);
        assertEquals(total, g.totalUnits);
        String id = g.id;
        assertTrue(Readiness.check(cfg, DAY).stream().noneMatch(i -> i.severity() == Readiness.Severity.BLOCKING
            && id.equals(i.subjectId())));
        assertEquals(ActivityKind.GOAL, a.kind);
    }
}
