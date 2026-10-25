package com.chronos.validate;

import com.chronos.engine.FixedFrame;
import com.chronos.engine.Feasibility;
import com.chronos.engine.HorizonPlanner;
import com.chronos.engine.Slots;
import com.chronos.model.Activity;
import com.chronos.model.Commitment;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.CommitmentKind;
import com.chronos.model.Enums.GoalMode;
import com.chronos.model.Goal;
import com.chronos.model.Params;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Nothing is seeded, so nothing may be assumed. A half-filled configuration is
 * allowed to persist - abandoning a form should not destroy what was typed -
 * but the solver stays locked until every issue below is cleared.
 */
public final class Readiness {
    private Readiness() {}

    public enum Severity { BLOCKING, WARNING }

    /**
     * @param where a screen identifier the UI can deep-link to
     * @param what  what is missing or wrong, in the user's terms
     */
    public record Issue(Severity severity, String where, String subjectId, String what) {}

    public static boolean canSolve(List<Issue> issues) {
        return issues.stream().noneMatch(i -> i.severity() == Severity.BLOCKING);
    }

    public static List<Issue> check(Config cfg, LocalDate today) {
        List<Issue> out = new ArrayList<>();
        checkParams(cfg.params, out);
        checkGoals(cfg, today, out);
        checkActivities(cfg, out);
        checkCommitments(cfg, out);
        if (canSolve(out)) checkCapacity(cfg, today, out);
        return out;
    }

    private static void need(Object value, String where, String what, List<Issue> out) {
        need(value, where, null, what, out);
    }

    /** With the id of what is missing, so a screen can mark the very card the answer belongs on. */
    private static void need(Object value, String where, String subjectId, String what, List<Issue> out) {
        if (value == null) out.add(new Issue(Severity.BLOCKING, where, subjectId, what));
    }

    private static void checkParams(Params p, List<Issue> out) {
        need(p.sleepStart, "bodyClock", "Sleep start time is not set", out);
        need(p.sleepEnd, "bodyClock", "Sleep end time is not set", out);
        need(p.phi, "bodyClock", "Your sharpest hour of the day is not set", out);
        need(p.a0, "bodyClock", "Baseline capacity is not set", out);
        need(p.aC, "bodyClock", "Sensitivity to time of day is not set", out);
        need(p.aH, "bodyClock", "Sensitivity to hours awake is not set", out);
        need(p.aF, "bodyClock", "Sensitivity to accumulated work is not set", out);
        need(p.aP, "bodyClock", "Sensitivity to physical fatigue is not set", out);
        need(p.pTheta, "bodyClock", "Physical fatigue threshold is not set", out);
        need(p.fSafe, "solver", "Safe fatigue line is not set", out);
        need(p.idleRecovery, "solver", "Recovery while resting is not set", out);
        need(p.fSafeP, "solver", "Safe physical fatigue line is not set", out);
        need(p.idleRecoveryP, "solver", "Physical recovery while resting is not set", out);
        need(p.kMP, "solver", "How much a tired mind makes physical effort cost is not set", out);
        need(p.slackRatio, "solver", "Slack reserve is not set", out);
        need(p.lcbBeta, "solver", "Pessimism is not set", out);
        need(p.switchPenalty, "solver", "Cost of starting a block is not set", out);
        need(p.fatiguePenalty, "solver", "Cost of overworking is not set", out);
        need(p.horizonDays, "solver", "Planning horizon is not set", out);
        if (p.horizonDays != null && (p.horizonDays < 7 || p.horizonDays > 90))
            out.add(new Issue(Severity.BLOCKING, "solver", null,
                "Planning horizon must be between 7 and 90 days"));

        if (p.sleepStart != null && p.sleepEnd != null) {
            int s0 = Slots.parse(p.sleepStart), s1 = Slots.parse(p.sleepEnd);
            int asleep = s0 > s1 ? (Slots.PER_DAY - s0 + s1) : (s1 - s0);
            if (asleep <= 0)
                out.add(new Issue(Severity.BLOCKING, "bodyClock", null,
                    "Sleep start and end are the same time"));
            else if (asleep * Slots.SLOT_MIN < 4 * 60)
                out.add(new Issue(Severity.WARNING, "bodyClock", null,
                    "Under four hours of sleep is scheduled; the capacity model will not be realistic"));
        }
    }

    private static void checkGoals(Config cfg, LocalDate today, List<Issue> out) {
        if (cfg.goals.isEmpty()) {
            out.add(new Issue(Severity.BLOCKING, "goals", null, "No goals defined yet"));
            return;
        }
        boolean anyWeight = false;
        for (Goal g : cfg.goals) {
            String id = g.id;
            if (g.name == null || g.name.isBlank())
                out.add(new Issue(Severity.BLOCKING, "goals", id, "A goal has no name"));
            if (g.mode == null) {
                out.add(new Issue(Severity.BLOCKING, "goals", id, g.name + ": type is not set"));
                continue;
            }
            need(g.hoursPerUnit, "goals", g.id, g.name + ": hours per unit is not set", out);
            need(g.maxPerDayH, "goals", g.id, g.name + ": daily ceiling is not set", out);
            if (g.mode == GoalMode.CLOSED) {
                need(g.totalUnits, "goals", g.id, g.name + ": total amount is not set", out);
                need(g.unit, "goals", g.id, g.name + ": unit is not named", out);
                need(g.deadline, "goals", g.id, g.name + ": deadline is not set", out);
                if (g.deadline != null && g.deadline.isBefore(today) && g.remainingUnits() > 0)
                    out.add(new Issue(Severity.BLOCKING, "goals", id,
                        g.name + ": deadline has passed with work outstanding"));
            } else {
                need(g.weeklyFloorH, "goals", g.id, g.name + ": weekly floor is not set", out);
            }
            if (cfg.activitiesFor(g.id).isEmpty())
                out.add(new Issue(Severity.BLOCKING, "activities", id,
                    g.name + " has no activity that works on it"));
            if (g.weight > 0) anyWeight = true;
        }
        if (!anyWeight && cfg.goals.size() > 1)
            out.add(new Issue(Severity.BLOCKING, "weights", null,
                "Relative priorities have not been set; compare your goals two at a time"));
    }

    private static void checkActivities(Config cfg, List<Issue> out) {
        if (cfg.activities.isEmpty()) {
            out.add(new Issue(Severity.BLOCKING, "activities", null, "No activities defined yet"));
            return;
        }
        for (Activity a : cfg.activities) {
            String id = a.id;
            // Goal work and upkeep are set up on separate steps; each issue goes to its own.
            String where = a.kind == ActivityKind.MAINTENANCE ? (a.leisure ? "leisure" : "upkeep") : "activities";
            if (a.startDate != null && a.kind == ActivityKind.GOAL && a.goalId != null) {
                Goal g = cfg.goal(a.goalId);
                if (g != null && g.mode == GoalMode.CLOSED && g.deadline != null && !a.startDate.isBefore(g.deadline))
                    out.add(new Issue(Severity.BLOCKING, where, id, a.name + ": starts on " + a.startDate
                        + ", which is not before its goal's deadline " + g.deadline + "; move the start earlier"));
            }
            if (a.name == null || a.name.isBlank())
                out.add(new Issue(Severity.BLOCKING, where, id, "An activity has no name"));
            if (a.kind == null) {
                out.add(new Issue(Severity.BLOCKING, where, id, a.name + ": kind is not set"));
                continue;
            }
            if (a.kind == ActivityKind.GOAL && (a.goalId == null || cfg.goal(a.goalId) == null))
                out.add(new Issue(Severity.BLOCKING, where, id,
                    a.name + ": not attached to a goal"));
            need(a.minBlock, where, a.id, a.name + ": shortest block is not set", out);
            need(a.maxBlock, where, a.id, a.name + ": longest block is not set", out);
            need(a.load, where, a.id, a.name + ": fatigue load is not set", out);
            need(a.recovery, where, a.id, a.name + ": recovery is not set", out);
            need(a.physLoad, where, a.id, a.name + ": physical load is not set", out);
            need(a.physRecovery, where, a.id, a.name + ": physical recovery is not set", out);
            if (a.leisure) need(a.dopamine, where, a.id, a.name + ": how stimulating it is has no first guess yet", out);
            if (!a.splitConfirmed && a.physLoad != null)
                out.add(new Issue(Severity.WARNING, where, a.id,
                    a.name + ": the split between physical and mental fatigue was suggested by the app; confirm or change it"));
            if (a.days.isEmpty())
                out.add(new Issue(Severity.BLOCKING, where, id,
                    a.name + ": no weekday is selected"));
            if (a.windows.isEmpty())
                out.add(new Issue(Severity.BLOCKING, where, id,
                    a.name + ": no allowed hours are set"));
            if (a.minBlock != null && a.maxBlock != null && a.maxBlock < a.minBlock)
                out.add(new Issue(Severity.BLOCKING, where, id,
                    a.name + ": longest block is shorter than the shortest"));

            // A window that ends before it starts is ignored until it is fixed.
            // It blocks only when it was the activity's one and only window.
            int valid = 0;
            for (Activity.Window w : a.windows) if (Slots.span(w.from, w.to) != null) valid++;
            for (Activity.Window w : a.windows) {
                if (Slots.span(w.from, w.to) != null) continue;
                out.add(new Issue(valid == 0 ? Severity.BLOCKING : Severity.WARNING, where, id,
                    a.name + ": the window " + w.from + "–" + w.to
                    + " ends before it starts, so it is ignored until you fix it"));
            }
            if (a.minBlock != null && valid > 0) {
                int widest = 0;
                for (Activity.Window w : a.windows) {
                    int[] span = Slots.span(w.from, w.to);
                    if (span != null) widest = Math.max(widest, (span[1] - span[0]) * Slots.SLOT_MIN);
                }
                if (widest < a.minBlock)
                    out.add(new Issue(Severity.BLOCKING, where, id,
                        a.name + ": its widest allowed window is " + widest
                        + " minutes, shorter than its own shortest block"));
            }
            for (Activity.Pin pin : a.pins) {
                if (!a.days.contains(pin.day))
                    out.add(new Issue(Severity.WARNING, where, id,
                        a.name + ": pinned on " + Slots.DOW[pin.day] + ", a day it is not allowed on"));
                if (cfg.params.sleepStart != null && cfg.params.sleepEnd != null
                    && com.chronos.engine.Energy.isAsleep(Slots.parse(pin.start),
                            Slots.parse(cfg.params.sleepStart), Slots.parse(cfg.params.sleepEnd)))
                    out.add(new Issue(Severity.BLOCKING, where, id,
                        a.name + ": pinned to " + pin.start + ", which is while you are asleep"));
            }
        }
    }

    private static void checkCommitments(Config cfg, List<Issue> out) {
        boolean anyAway = cfg.activities.stream().anyMatch(a -> a.away);
        boolean anyTrip = false;
        for (Commitment c : cfg.commitments) {
            String id = c.id;
            if (c.kind == null) {
                out.add(new Issue(Severity.BLOCKING, "commitments", id, "A commitment has no kind"));
                continue;
            }
            if (c.kind == CommitmentKind.FIXED) {
                // Each session carries its own cost: a lab and a lecture of the
                // same course do not drain alike, so there is no course-wide figure.
                if (c.sessions.stream().anyMatch(s -> s.load == null))
                    out.add(new Issue(Severity.BLOCKING, "commitments", id,
                        c.name + ": a session's fatigue load is not set"));
            } else {
                need(c.load, "commitments", c.id, c.name + ": fatigue load is not set", out);
            }
            if (c.kind == CommitmentKind.FIXED) {
                if (c.sessions.isEmpty())
                    out.add(new Issue(Severity.BLOCKING, "commitments", id,
                        c.name + ": no sessions are defined"));
                for (Commitment.Session s : c.sessions)
                    if (Slots.parse(s.end) <= Slots.parse(s.start))
                        out.add(new Issue(Severity.BLOCKING, "commitments", id,
                            c.name + ": a session ends before it starts"));
                if (c.away) anyAway = true;
            } else {
                anyTrip = true;
                need(c.medianMin, "commitments", c.id, c.name + ": typical travel time is not set", out);
                need(c.sigma, "commitments", c.id, c.name + ": travel variability is not set", out);
                need(c.quantile, "commitments", c.id, c.name + ": planning quantile is not set", out);
            }
        }
        if (anyAway && !anyTrip)
            out.add(new Issue(Severity.BLOCKING, "commitments", null,
                "Something happens away from home but no travel is defined, so the plan would "
                + "assume you teleport"));
    }

    /** The audit that matters most: does the work fit in the week at all. */
    private static void checkCapacity(Config cfg, LocalDate today, List<Issue> out) {
        HorizonPlanner.Plan plan = HorizonPlanner.plan(cfg, today);
        for (HorizonPlanner.Violation v : plan.violations())
            out.add(new Issue(Severity.BLOCKING, "horizon", null,
                "By " + v.by() + ", " + String.join(" and ", v.goals()) + " need "
                + String.format("%.1f", v.demandH()) + "h but only "
                + String.format("%.1f", v.supplyH()) + "h exists. You are "
                + String.format("%.1f", v.deficitH()) + "h short."));
        // The deliverable-hours test is stricter than the horizon's own
        // shortfall: it also refuses hours the day could never place. Only one
        // of the two is reported, so the user is not told the same thing twice.
        Feasibility.Report report = Feasibility.analyse(cfg, today);
        for (Feasibility.Deficit d : report.deficits())
            out.add(new Issue(Severity.WARNING, "horizon", d.goalId(),
                d.goalName() + " does not finish by " + d.deadline() + ": "
                + String.format("%.1f", d.missingH()) + "h of the "
                + String.format("%.1f", d.neededH()) + "h it needs have nowhere to go. "
                + "The Horizon tab lists what to change."));
        if (report.clean())
            for (HorizonPlanner.Shortfall s : plan.shortfalls())
                out.add(new Issue(Severity.WARNING, "horizon", null,
                    s.goal() + ": " + String.format("%.1f", s.missingH())
                    + "h could not be paced within the daily ceiling and allowed weekdays"));
    }
}
