package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.HorizonPlanner;
import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.GoalMode;
import com.chronos.model.Goal;
import com.chronos.navigator.bridge.ChronosControl;
import com.chronos.navigator.llm.ChangeSet;
import com.chronos.navigator.model.Graph;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/** The Navigator's changes to Chronos, read from the crew's vocabulary and written into a configuration. */
public class ChronosControlTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);

    @Test
    public void aPackageBecomesGoalsActivitiesLeversAndRestDays() {
        Config cfg = ParityHarness.build(DAY);
        Activity goalAct = null, upkeep = null;
        for (Activity a : cfg.activities) {
            if (goalAct == null && a.kind == ActivityKind.GOAL) goalAct = a;
            if (upkeep == null && a.kind == ActivityKind.MAINTENANCE && !a.restOnly && !a.nap
                && a.days.contains(com.chronos.engine.Slots.dow(DAY.plusDays(1)))) upkeep = a;
        }
        assertNotNull(goalAct);
        assertNotNull(upkeep);
        Set<LocalDate> rest = new HashSet<>();
        JsonObject view = ChronosControl.INSTANCE.view(cfg, rest, DAY);

        Graph g = new Graph();
        g.horizonEnd = DAY.plusYears(2);
        double slack = cfg.params.slackRatio;
        String answer = "{\"changes\":["
            + "{\"title\":\"Report\",\"actions\":[{\"type\":\"add_task\",\"key\":\"REPORT\",\"title\":\"Write the report\","
            + "\"owner\":\"ADVISOR\",\"p50_hours\":8,\"p90_hours\":14,\"deadline\":\"" + DAY.plusDays(20) + "\","
            + "\"activity\":\"report writing\",\"like\":\"" + goalAct.name + "\"}]},"
            + "{\"title\":\"Room\",\"actions\":[{\"type\":\"archive_activity\",\"activity\":\"" + upkeep.name + "\"},"
            + "{\"type\":\"pressure\",\"slack_ratio\":" + Math.max(0.05, slack - 0.05) + "}]},"
            + "{\"title\":\"Rest\",\"actions\":[{\"type\":\"rest_day\",\"date\":\"" + DAY.plusDays(4) + "\"}]}],"
            + "\"consent_self_check\":{\"targets_other_person\":false}}";
        ChangeSet.Parsed p = ChangeSet.read(g, DAY, view, answer);
        assertTrue(p.rejected().toString(), p.rejected().isEmpty());
        assertEquals(3, p.groups().size());

        double upkeepBefore = HorizonPlanner.capacityOf(cfg, DAY.plusDays(1), 1).upkeepH();
        Config copy = cfg.copy();
        Set<LocalDate> rest2 = new HashSet<>(rest);
        var out = ChronosControl.INSTANCE.apply(copy, rest2, p.ops(), DAY);
        assertEquals(1, out.getSecond().size());

        Goal made = out.getSecond().get(0).getGoal();
        assertEquals(GoalMode.CLOSED, made.mode);
        assertEquals(8.0, made.totalUnits, 0);
        assertEquals(DAY.plusDays(20), made.deadline);
        assertNotNull("Chronos needs a daily ceiling", made.maxPerDayH);
        // Nothing the package added leaves Chronos unable to plan.
        var before = com.chronos.validate.Readiness.check(cfg, DAY).stream()
            .filter(i -> i.severity() == com.chronos.validate.Readiness.Severity.BLOCKING).map(i -> i.what()).toList();
        var after = com.chronos.validate.Readiness.check(copy, DAY).stream()
            .filter(i -> i.severity() == com.chronos.validate.Readiness.Severity.BLOCKING).map(i -> i.what())
            .filter(w -> !before.contains(w)).toList();
        assertTrue(after.toString(), after.isEmpty());
        Activity act = null;
        for (Activity a : copy.activities) if ("report writing".equals(a.name)) act = a;
        assertNotNull("the task has its own activity", act);
        assertEquals(made.id, act.goalId);
        assertEquals(goalAct.windows.size(), act.windows.size());

        assertTrue(copy.activity(upkeep.id).archived);
        assertTrue("an archived upkeep takes no time", HorizonPlanner.capacityOf(copy, DAY.plusDays(1), 1).upkeepH() < upkeepBefore);
        assertEquals(Math.max(0.05, slack - 0.05), copy.params.slackRatio, 1e-9);
        assertTrue(rest2.contains(DAY.plusDays(4)));
        // The original is untouched: a what-if works on a copy.
        assertEquals(slack, cfg.params.slackRatio, 1e-9);
        assertTrue(!cfg.activity(upkeep.id).archived);

        // The new goal gets hours before its deadline.
        HorizonPlanner.Plan plan = HorizonPlanner.plan(copy, DAY);
        double h = 0;
        for (var d : plan.allocation()) h += d.getOrDefault(made.id, 0.0);
        assertTrue("Chronos plans the new task", h > 1);
    }

    @Test
    public void aPausedActivityIsNotPlannedOnItsDays() {
        Config cfg = ParityHarness.build(DAY);
        Activity a = null;
        for (Activity x : cfg.activities) if (x.kind == ActivityKind.GOAL && x.goalId != null) { a = x; break; }
        assertNotNull(a);
        Graph g = new Graph();
        g.horizonEnd = DAY.plusYears(1);
        JsonObject view = ChronosControl.INSTANCE.view(cfg, new HashSet<>(), DAY);
        String answer = "{\"changes\":[{\"title\":\"Pause\",\"actions\":[{\"type\":\"pause\",\"node\":\"" + a.name
            + "\",\"from\":\"" + DAY.plusDays(1) + "\",\"until\":\"" + DAY.plusDays(3) + "\"}]}],"
            + "\"consent_self_check\":{\"targets_other_person\":false}}";
        ChangeSet.Parsed p = ChangeSet.read(g, DAY, view, answer);
        assertTrue(p.rejected().toString(), p.rejected().isEmpty());
        Config copy = cfg.copy();
        ChronosControl.INSTANCE.apply(copy, new HashSet<>(), p.ops(), DAY);
        Activity paused = copy.activity(a.id);
        assertTrue(paused.pausedOn(DAY.plusDays(2)));
        assertTrue("not before the pause", paused.startedBy(DAY));
        assertTrue("and back after it", paused.startedBy(DAY.plusDays(4)));
        assertTrue("left out of the days it is paused", copy.on(DAY.plusDays(2)).activity(a.id) == null);
    }
}
