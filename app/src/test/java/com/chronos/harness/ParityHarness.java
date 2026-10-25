package com.chronos.harness;

import com.chronos.engine.*;
import com.chronos.model.*;
import com.chronos.model.Enums.*;
import com.chronos.validate.Readiness;

import java.time.LocalDate;
import java.util.*;

/**
 * Not part of the app. Rebuilds the configuration the browser prototype ran on
 * so the two implementations can be compared number by number, and exercises
 * the validator against a deliberately empty configuration.
 */
public class ParityHarness {

    static Activity act(String id, String name, ActivityKind kind, String goalId,
                        int[] days, int min, int max, double load, double rec,
                        double k, double u0, double theta, String[][] windows,
                        boolean away, boolean highFocus) {
        Activity a = new Activity();
        a.id = id; a.name = name; a.kind = kind; a.goalId = goalId;
        for (int d : days) a.days.add(d);
        a.minBlock = min; a.maxBlock = max; a.load = load; a.recovery = rec;
        a.k = k; a.u0 = u0; a.theta = theta;
        for (String[] w : windows) a.windows.add(new Activity.Window(w[0], w[1]));
        a.away = away; a.needsHighEnergy = highFocus;
        a.blocksMode = BlocksMode.AUTO;
        return a;
    }

    static Config build(LocalDate today) {
        Config c = new Config();
        Params p = c.params;
        p.sleepStart = "23:30"; p.sleepEnd = "07:00"; p.phi = 660;
        p.a0 = 0.75; p.aC = 0.15; p.aH = 0.10; p.aF = 0.12;
        p.fSafe = 0.62; p.idleRecovery = 0.35; p.slackRatio = 0.10;
        p.lcbBeta = 0.7; p.switchPenalty = 0.04; p.fatiguePenalty = 0.9;
        p.horizonDays = 30;

        Goal lit = new Goal();
        lit.id = "lit"; lit.name = "Thesis literature review"; lit.mode = GoalMode.CLOSED;
        lit.totalUnits = 24.0; lit.doneUnits = 3.0; lit.unit = "paper summaries";
        lit.hoursPerUnit = 1.6; lit.deadline = today.plusDays(30);
        lit.maxPerDayH = 4.0; lit.weight = 0.42;

        Goal courses = new Goal();
        courses.id = "courses"; courses.name = "Coursework"; courses.mode = GoalMode.CLOSED;
        courses.totalUnits = 4.0; courses.doneUnits = 0.0; courses.unit = "problem sets";
        courses.hoursPerUnit = 8.0; courses.deadline = today.plusDays(21);
        courses.maxPerDayH = 3.0; courses.weight = 0.38;

        Goal english = new Goal();
        english.id = "english"; english.name = "English fluency"; english.mode = GoalMode.OPEN;
        english.unit = "hours"; english.hoursPerUnit = 1.0; english.weeklyFloorH = 5.0;
        english.maxPerDayH = 1.5; english.weight = 0.20;
        c.goals.addAll(List.of(lit, courses, english));

        int[] all = {0, 1, 2, 3, 4, 5, 6};
        c.activities.add(act("papers", "Read & summarize papers", ActivityKind.GOAL, "lit", all,
            50, 120, 0.80, 0, 0.15, 12, 95,
            new String[][]{{"08:00", "12:30"}, {"14:00", "19:30"}}, false, true));
        c.activities.add(act("coursework", "Coursework & problem sets", ActivityKind.GOAL, "courses", all,
            50, 100, 0.75, 0, 0.18, 10, 85,
            new String[][]{{"08:00", "22:00"}}, false, true));
        c.activities.add(act("english", "English study", ActivityKind.GOAL, "english", all,
            25, 50, 0.45, 0, 0.30, 5, 70,
            new String[][]{{"07:00", "23:00"}}, false, false));

        Activity breakfast = act("breakfast", "Breakfast", ActivityKind.MAINTENANCE, null, all,
            25, 30, 0, 0.30, 1, 0, 999, new String[][]{{"07:00", "09:30"}}, false, false);
        breakfast.blocksMode = BlocksMode.FIXED; breakfast.maxBlocks = 1;
        breakfast.pins.addAll(List.of(new Activity.Pin(0, "07:05"),
                                      new Activity.Pin(2, "07:05"),
                                      new Activity.Pin(4, "07:05")));
        c.activities.add(breakfast);

        c.activities.add(act("lunch", "Lunch", ActivityKind.MAINTENANCE, null, all,
            30, 45, 0, 0.45, 1, 0, 999, new String[][]{{"12:00", "15:00"}}, false, false));
        c.activities.add(act("dinner", "Dinner", ActivityKind.MAINTENANCE, null, all,
            30, 45, 0, 0.35, 1, 0, 999, new String[][]{{"19:00", "22:00"}}, false, false));
        c.activities.add(act("exercise", "Workout", ActivityKind.MAINTENANCE, null, new int[]{1, 3},
            60, 75, 0.15, 0.55, 1, 0, 999, new String[][]{{"16:00", "20:00"}}, true, false));
        c.activities.add(act("chores", "Housework", ActivityKind.MAINTENANCE, null, new int[]{0, 5, 6},
            45, 75, 0.20, 0.25, 1, 0, 999, new String[][]{{"09:00", "22:30"}}, false, false));

        Commitment classes = new Commitment();
        classes.id = "c1"; classes.name = "Classes"; classes.kind = CommitmentKind.FIXED;
        classes.load = 0.5; classes.recovery = 0.0; classes.away = true;
        classes.sessions.addAll(List.of(
            new Commitment.Session(0, "09:00", "13:00"), new Commitment.Session(1, "10:00", "13:00"),
            new Commitment.Session(2, "09:00", "13:00"), new Commitment.Session(3, "10:00", "13:00"),
            new Commitment.Session(4, "09:00", "14:00"), new Commitment.Session(5, "09:00", "13:00")));

        Commitment commute = new Commitment();
        commute.id = "c2"; commute.name = "Commute"; commute.kind = CommitmentKind.VARIABLE;
        commute.load = 0.25; commute.recovery = 0.20;
        commute.medianMin = 60.0; commute.sigma = 0.25; commute.quantile = 0.8;
        commute.outbound = new Commitment.Leg(LegMode.PINNED);
        commute.inbound = new Commitment.Leg("12:00", "22:00");
        c.commitments.addAll(List.of(classes, commute));
        return c;
    }

    public static void main(String[] args) {
        LocalDate today = LocalDate.parse("2026-09-22");

        System.out.println("=== validator against an empty configuration ===");
        for (Readiness.Issue i : Readiness.check(new Config(), today).subList(0, 8))
            System.out.println("  [" + i.severity() + "] " + i.where() + ": " + i.what());
        System.out.println("  ... solver locked: " + !Readiness.canSolve(Readiness.check(new Config(), today)));

        Config cfg = build(today);
        List<Readiness.Issue> issues = Readiness.check(cfg, today);
        System.out.println("\n=== validator against the full configuration ===");
        System.out.println("  issues: " + issues.size() + ", can solve: " + Readiness.canSolve(issues));
        for (Readiness.Issue i : issues) System.out.println("  [" + i.severity() + "] " + i.what());

        HorizonPlanner.Plan plan = HorizonPlanner.plan(cfg, today);
        System.out.println("\n=== horizon ===");
        System.out.println("  violations " + plan.violations().size()
                         + ", shortfalls " + plan.shortfalls().size()
                         + ", overdue " + plan.overdue().size());
        for (int i = 0; i < 7; i++) {
            HorizonPlanner.DayCapacity d = plan.days().get(i);
            System.out.printf("  %s %s  free %.1fh  upkeep %.1fh  travel %.1fh  allocatable %.1fh%n",
                Slots.DOW[d.dow()], d.date(), d.freeH(), d.upkeepH(), d.travelH(), d.capacityH());
        }

        System.out.println("\n=== days ===");
        for (int i = 0; i < 7; i++) {
            HorizonPlanner.DayCapacity d = plan.days().get(i);
            long seed = d.date().toEpochDay() * 31 + cfg.activities.size();
            DaySequencer.Result r = DaySequencer.run(cfg, d.dow(), plan.allocation().get(i), seed);
            String back = r.blocks().stream().filter(b -> b.name.contains("back"))
                .map(b -> Slots.format(b.start)).findFirst().orElse("NONE");
            System.out.printf("%n%s %s | effective %.0f%% | excess %.2f | goalBlocks %d | back %s | unplaced %d%n",
                Slots.DOW[d.dow()], Jalali.both(d.date()),
                100 * r.effectiveMinutes() / Math.max(1, r.elapsedMinutes()),
                r.score().excess(),
                r.blocks().stream().filter(b -> b.goalId != null).count(),
                back, r.unplaced().size());
            for (String w : r.warnings()) System.out.println("    ! " + w);
            for (Block b : r.blocks())
                System.out.printf("    %s-%s  %-28s %s%n", Slots.format(b.start),
                    Slots.format(b.start + b.dur), b.name,
                    b.goalId != null ? String.format("eta %.2f", b.meanEta) : "");
        }

        System.out.println("\n=== determinism ===");
        DaySequencer.Result a = DaySequencer.run(cfg, 0, plan.allocation().get(0), 12345L);
        DaySequencer.Result b = DaySequencer.run(cfg, 0, plan.allocation().get(0), 12345L);
        boolean same = a.blocks().size() == b.blocks().size();
        for (int i = 0; same && i < a.blocks().size(); i++)
            same = a.blocks().get(i).start == b.blocks().get(i).start;
        System.out.println("  same seed reproduces the same day: " + same);
    }
}
