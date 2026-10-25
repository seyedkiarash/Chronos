package com.chronos.engine;

import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.CommitmentKind;
import com.chronos.model.Enums.GoalMode;
import com.chronos.model.Goal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Whether every dated goal actually finishes, and what to change when one does
 * not.
 *
 * The horizon planner already reports a shortfall — the hours it could not pace
 * before a deadline — but a bare number is not actionable, and it misses a
 * second way to fall short: hours the planner did allocate but the day can
 * never place, because the activity's allowed window on that weekday is shorter
 * than its own minimum block. Both are measured here by one quantity, the
 * <em>deliverable hours</em> of a goal:
 *
 * <pre>
 *   deliverable(g) = sum over days d up to the deadline of
 *                    min( hours the horizon gave g on d, room g has on dow(d) )
 * </pre>
 *
 * where the room is the free time inside the goal's windows on that weekday,
 * counted only in unbroken runs long enough to hold one session. Room is an
 * upper bound — upkeep and other goals compete for the same minutes — so a
 * deficit reported here is always real, in the same sense as the Horn test in
 * {@link HorizonPlanner}: necessary, not sufficient.
 *
 * Remedies are not advice. Each one is applied to a copy of the configuration,
 * the horizon is re-planned, and the deliverable hours are measured again; only
 * changes that actually close part of the gap are reported, with the hours they
 * recover. Planning a horizon costs milliseconds, so trying a dozen of them is
 * cheap.
 *
 * They are ordered by what they cost the person, not by how many hours they
 * buy. Rearranging the same work comes first, working harder second, cutting
 * scope last — the objective of section 6 is to finish the goals under the
 * least fatigue, and a suggestion to raise the daily ceiling is a suggestion to
 * spend fatigue.
 */
public final class Feasibility {
    private Feasibility() {}

    /** What a remedy costs the person, which is what orders the list. */
    public enum Cost {
        /** Same work, different hours or days. Costs nothing. */
        REARRANGE,
        /** More work per day, or less reserve. Costs fatigue. */
        INTENSIFY,
        /** Less work, or more time to do it in. Costs the goal. */
        SCOPE
    }

    /** Why a goal cannot finish, in the order the causes are worth reading. */
    public enum Cause {
        /** No room at all on the weekdays the goal is allowed, given its windows. */
        NO_ROOM,
        /** The daily ceiling stops the goal from taking what the days could give. */
        CEILING,
        /** The days before the deadline simply do not hold the hours. */
        CAPACITY
    }

    public record Deficit(String goalId, String goalName, LocalDate deadline,
                          double neededH, double deliverableH, double missingH,
                          Cause cause, String explanation) {}

    /**
     * @param fix where the change is made, for a link to it (your request of 23 Oct 2026):
     *            "when:ACTIVITY_ID" (its days, hours and block length), "target:GOAL_ID" (deadline,
     *            amount, daily ceiling), "scheduler" (slack, pessimism) or "upkeep"
     */
    public record Remedy(String goalId, Cost cost, String what, double recoveredH,
                         boolean clearsIt, String fix) {}

    /**
     * What a dated goal actually receives, measured rather than estimated: the
     * hours the horizon gave it on the days before its deadline, and the hours
     * the arranged days really placed. Anything the arrangement loses shows up
     * as placed < allocated, and anything the horizon could not pace shows up as
     * allocated < needed.
     *
     * @param worstDays the days that lost the most, for the report to name
     */
    public record Coverage(String goalId, String goalName, LocalDate deadline,
                           double neededH, double allocatedH, double placedH,
                           List<LostDay> worstDays) {
        public double missingH() { return Math.max(0, neededH - placedH); }
        public boolean finishes() { return missingH() <= 0.25; }
        /** Hours the arrangement dropped, as opposed to hours never allocated. */
        public double lostToArrangementH() { return Math.max(0, allocatedH - placedH); }
    }

    public record LostDay(LocalDate date, double hours) {}

    public record Report(List<Deficit> deficits, List<Remedy> remedies) {
        public boolean clean() { return deficits.isEmpty(); }
    }

    /** Hours a goal can actually be given before its deadline, and what it needs. */
    private record Delivery(double neededH, double deliverableH) {
        double missing() { return Math.max(0, neededH - deliverableH); }
    }

    // ------------------------------------------------------------- the report

    public static Report analyse(Config cfg, LocalDate start) {
        List<Deficit> deficits = new ArrayList<>();
        List<Remedy> remedies = new ArrayList<>();
        if (cfg.params == null || cfg.params.horizonDays == null) return new Report(deficits, remedies);

        HorizonPlanner.Plan plan = HorizonPlanner.plan(cfg, start);
        for (Goal g : cfg.goals) {
            if (g.mode != GoalMode.CLOSED || g.remainingUnits() <= 0) continue;
            Delivery d = delivery(cfg, plan, start, g.id);
            if (d.missing() <= 0.05) continue;

            Cause cause = causeOf(cfg, plan, start, g);
            deficits.add(new Deficit(g.id, g.name, g.deadline, d.neededH(), d.deliverableH(),
                                     d.missing(), cause, explain(cause, cfg, g)));
            remedies.addAll(remediesFor(cfg, start, g, d.missing()));
        }
        remedies.sort(Comparator.<Remedy, Integer>comparing(r -> r.cost().ordinal())
                                .thenComparing(Comparator.comparingDouble(Remedy::recoveredH).reversed()));
        return new Report(deficits, remedies);
    }

    /** The same measurement the remedies are scored against. */
    private static Delivery delivery(Config cfg, HorizonPlanner.Plan plan, LocalDate start, String goalId) {
        Goal g = cfg.goal(goalId);
        if (g == null) return new Delivery(0, 0);

        double needed = neededHours(cfg, g);
        int last = deadlineIndex(cfg, start, g, plan.days().size());
        double deliverable = 0;
        for (int i = 0; i <= last && i < plan.days().size(); i++) {
            double given = plan.allocation().get(i).getOrDefault(goalId, 0.0);
            if (given <= 0) continue;
            // What is left by then may be less than a session; a stretch that holds it is room for it.
            double leftMin = Math.max(0, needed - deliverable) * 60;
            deliverable += Math.min(given, roomH(cfg.on(plan.days().get(i).date()), goalId, plan.days().get(i).dow(), leftMin));
        }
        return new Delivery(needed, deliverable);
    }

    /** Hours the goal needs, discounted for pessimism exactly as the planner does. */
    public static double neededHours(Config cfg, Goal g) {
        if (g.mode != GoalMode.CLOSED || g.hoursPerUnit == null) return 0;
        List<Activity> acts = cfg.activitiesFor(g.id);
        double qSum = 0;
        for (Activity a : acts) qSum += Math.max(0.05, a.q - cfg.params.lcbBeta * a.qSigma);
        double qEff = acts.isEmpty() ? 1 : qSum / acts.size();
        return g.remainingUnits() * g.hoursPerUnit / qEff;
    }

    private static int deadlineIndex(Config cfg, LocalDate start, Goal g, int horizon) {
        if (g.deadline == null) return horizon - 1;
        long raw = java.time.temporal.ChronoUnit.DAYS.between(start, g.deadline);
        return (int) Slots.clamp((int) raw, 0, horizon - 1);
    }

    // --------------------------------------------------------------- the room

    /**
     * Hours on one weekday that could hold a session of this goal: minutes
     * inside its activities' windows, free of sleep and fixed commitments, in
     * unbroken runs of at least the activity's minimum block. Runs shorter than
     * that are not room — a fifty-minute session does not fit in forty minutes.
     */
    public static double roomH(Config cfg, String goalId, int dow) {
        return roomH(cfg, goalId, dow, Double.MAX_VALUE);
    }

    /** @param leftMin what is left of the goal's work, in minutes: a stretch that holds it all counts even if short */
    public static double roomH(Config cfg, String goalId, int dow, double leftMin) {
        boolean[] busy = occupied(cfg, dow);
        double minutes = 0;
        for (Activity a : cfg.activitiesFor(goalId)) {
            if (!a.days.contains(dow)) continue;
            minutes += freeMinutesInWindows(a, busy, leftMin);
        }
        // Two activities of the same goal may share the same hours; the room is
        // at most the free time of the day, whatever the windows say.
        double dayFree = 0;
        for (boolean b : busy) if (!b) dayFree += Slots.SLOT_MIN;
        return Math.min(minutes, dayFree) / 60.0;
    }

    private static double freeMinutesInWindows(Activity a, boolean[] busy, double leftMin) {
        int min = a.minBlock == null ? Slots.SLOT_MIN : a.minBlock;
        if (leftMin < min) min = Math.max(Slots.SLOT_MIN, (int) Math.ceil(leftMin / Slots.SLOT_MIN) * Slots.SLOT_MIN);
        boolean[] allowed = windowMask(a);
        double minutes = 0;
        int run = 0;
        for (int t = 0; t < Slots.PER_DAY; t++) {
            if (allowed[t] && !busy[t]) run++;
            else { minutes += runMinutes(run, min); run = 0; }
        }
        minutes += runMinutes(run, min);
        return minutes;
    }

    private static double runMinutes(int slots, int minBlockMin) {
        double m = slots * (double) Slots.SLOT_MIN;
        return m >= minBlockMin ? m : 0;
    }

    private static boolean[] windowMask(Activity a) {
        boolean[] mask = new boolean[Slots.PER_DAY];
        if (a.windows == null || a.windows.isEmpty()) {
            java.util.Arrays.fill(mask, true);
            return mask;
        }
        for (Activity.Window w : a.windows) {
            int[] span = Slots.span(w.from, w.to);
            if (span == null) continue;
            for (int t = span[0]; t < span[1]; t++) mask[Slots.wrap(t)] = true;
        }
        return mask;
    }

    /** Sleep, fixed commitments and travel: the slots a goal can never have. */
    private static boolean[] occupied(Config cfg, int dow) {
        FixedFrame.Frame frame = FixedFrame.of(cfg.params, cfg.commitments, dow);
        boolean[] busy = new boolean[Slots.PER_DAY];
        for (int t = 0; t < Slots.PER_DAY; t++) busy[t] = frame.occupant()[t] != null;
        return busy;
    }

    // ------------------------------------------------------------- the cause

    private static Cause causeOf(Config cfg, HorizonPlanner.Plan plan, LocalDate start, Goal g) {
        int last = deadlineIndex(cfg, start, g, plan.days().size());

        double allocated = 0, room = 0;
        for (int i = 0; i <= last && i < plan.days().size(); i++) {
            allocated += plan.allocation().get(i).getOrDefault(g.id, 0.0);
            room += roomH(cfg.on(plan.days().get(i).date()), g.id, plan.days().get(i).dow());
        }
        double needed = neededHours(cfg, g);

        // Allocated hours the day cannot hold: the windows, not the weeks.
        if (room + 0.05 < Math.min(needed, allocated)) return Cause.NO_ROOM;
        if (room + 0.05 < needed) return Cause.NO_ROOM;

        double ceiling = g.maxPerDayH == null ? Double.MAX_VALUE : g.maxPerDayH;
        int usableDays = 0;
        for (int i = 0; i <= last && i < plan.days().size(); i++)
            if (roomH(cfg.on(plan.days().get(i).date()), g.id, plan.days().get(i).dow()) > 0) usableDays++;
        if (ceiling * usableDays + 0.05 < needed) return Cause.CEILING;

        return Cause.CAPACITY;
    }

    private static String explain(Cause cause, Config cfg, Goal g) {
        return switch (cause) {
            case NO_ROOM -> "The hours this goal is allowed, on the weekdays it is allowed, are "
                + "already taken by sleep or a class — or what is left comes in stretches shorter "
                + "than one session of it.";
            case CEILING -> "The days before the deadline have the room, but the ceiling of "
                + trim(g.maxPerDayH) + "h a day will not let the goal use it.";
            case CAPACITY -> "There are not enough free hours before the deadline, once sleep, "
                + "classes, travel, upkeep and the slack reserve are taken out.";
        };
    }

    // ----------------------------------------------------------- the remedies

    /**
     * The same measured search, for a gap the arranged days revealed rather than
     * the capacity model.
     *
     * The candidates are still scored by re-planning the horizon, which does not
     * arrange days, so the hours they claim are a direction rather than a
     * promise — the caller says so. Scoring them against real arrangements would
     * mean sequencing a fortnight per candidate, seconds of the user's time for
     * a list they will act on one item at a time anyway.
     */
    public static List<Remedy> remediesForGap(Config cfg, LocalDate start, String goalId,
                                              double missingH) {
        Goal g = cfg.goal(goalId);
        if (g == null || missingH <= GRID_H) return List.of();
        List<Remedy> out = new ArrayList<>(remediesFor(cfg, start, g, missingH));
        out.sort(Comparator.<Remedy, Integer>comparing(r -> r.cost().ordinal())
                           .thenComparing(Comparator.comparingDouble(Remedy::recoveredH).reversed()));
        return out;
    }

    /**
     * Each candidate is applied to a copy, re-planned, and kept only if it
     * actually delivers hours the original could not.
     */
    private static List<Remedy> remediesFor(Config cfg, LocalDate start, Goal goal, double missingH) {
        List<Remedy> out = new ArrayList<>();
        String id = goal.id;

        // --- rearranging: the same work, somewhere else -----------------
        for (Activity a : cfg.activitiesFor(id)) {
            int slot = index(cfg.activities, a.id);
            if (slot < 0) continue;

            // Widen the allowed hours to the whole waking day.
            if (a.windows != null && !a.windows.isEmpty()) {
                Config c = cfg.copy();
                c.activities.get(slot).windows.clear();
                add(out, id, Cost.REARRANGE, "Widen the allowed hours of \"" + a.name
                    + "\" — its windows are what is turning the day away", c, cfg, start, missingH, "when:" + a.id);
            }

            // Allow it on a weekday it currently is not.
            TreeSet<Integer> missingDows = new TreeSet<>();
            for (int d = 0; d < 7; d++) if (!a.days.contains(d)) missingDows.add(d);
            if (!missingDows.isEmpty()) {
                Config c = cfg.copy();
                c.activities.get(slot).days.addAll(missingDows);
                StringBuilder names = new StringBuilder();
                for (int d : missingDows) {
                    if (names.length() > 0) names.append(", ");
                    names.append(Slots.DOW[d]);
                }
                add(out, id, Cost.REARRANGE, "Allow \"" + a.name + "\" on " + names
                    + " as well", c, cfg, start, missingH, "when:" + a.id);
            }

            // A shorter minimum session fits into the gaps that are left.
            if (a.minBlock != null && a.minBlock > 30) {
                int shorter = Math.max(30, a.minBlock / 2);
                Config c = cfg.copy();
                c.activities.get(slot).minBlock = shorter;
                add(out, id, Cost.REARRANGE, "Shorten the shortest useful block of \"" + a.name
                    + "\" from " + a.minBlock + " to " + shorter + " min", c, cfg, start, missingH, "effort:" + a.id);
            }
        }

        // --- intensifying: more per day, or less reserve ------------------
        int gslot = index(cfg.goals, id);
        if (gslot >= 0 && goal.maxPerDayH != null) {
            Config c = cfg.copy();
            c.goals.get(gslot).maxPerDayH = goal.maxPerDayH + 1;
            add(out, id, Cost.INTENSIFY, "Raise the daily ceiling for this goal from "
                + trim(goal.maxPerDayH) + "h to " + trim(goal.maxPerDayH + 1) + "h",
                c, cfg, start, missingH, "target:" + id);
        }
        if (cfg.params.slackRatio != null && cfg.params.slackRatio > 0.02) {
            double lower = Math.max(0.02, cfg.params.slackRatio - 0.05);
            Config c = cfg.copy();
            c.params.slackRatio = lower;
            add(out, id, Cost.INTENSIFY, "Lower the slack kept free from "
                + trim(cfg.params.slackRatio) + " to " + trim(lower)
                + " — this is the reserve that absorbs a day running long", c, cfg, start, missingH, "scheduler");
        }
        if (cfg.params.lcbBeta != null && cfg.params.lcbBeta > 0.05) {
            double lower = Math.max(0, cfg.params.lcbBeta - 0.3);
            Config c = cfg.copy();
            c.params.lcbBeta = lower;
            add(out, id, Cost.INTENSIFY, "Lower pessimism from " + trim(cfg.params.lcbBeta)
                + " to " + trim(lower) + " — the planner then reserves fewer hours per unit, "
                + "which only helps if your estimate was already honest", c, cfg, start, missingH, "scheduler");
        }
        if (hasUpkeepToTrim(cfg)) {
            Config c = cfg.copy();
            for (Activity a : c.activities)
                if (a.kind == ActivityKind.MAINTENANCE && !a.leisure && a.minBlock != null && a.minBlock > 30)
                    a.minBlock = Math.max(30, (int) Math.round(a.minBlock * 0.75));
            add(out, id, Cost.INTENSIFY, "Shorten your upkeep sessions by a quarter — upkeep is "
                + "subtracted from every day before goals are served", c, cfg, start, missingH, "upkeep");
        }

        // --- scope: the honest last resort --------------------------------
        if (gslot >= 0 && goal.deadline != null) {
            int bestDays = 0;
            double bestRecovered = 0;
            for (int extra : new int[]{3, 7, 14, 30}) {
                Config c = cfg.copy();
                c.goals.get(gslot).deadline = goal.deadline.plusDays(extra);
                double recovered = measure(c, id, start, missingH);
                if (recovered > bestRecovered + GRID_H) { bestRecovered = recovered; bestDays = extra; }
                if (missingH - recovered <= GRID_H) { bestRecovered = recovered; bestDays = extra; break; }
            }
            if (bestDays > 0 && bestRecovered > GRID_H)
                out.add(new Remedy(id, Cost.SCOPE, "Move the deadline " + bestDays + " days later, to "
                    + goal.deadline.plusDays(bestDays), bestRecovered,
                    missingH - bestRecovered <= GRID_H, "target:" + id));
        }
        if (gslot >= 0 && goal.totalUnits != null && goal.hoursPerUnit != null && goal.hoursPerUnit > 0) {
            double dropUnits = Math.ceil(missingH / goal.hoursPerUnit);
            double done = goal.doneUnits == null ? 0 : goal.doneUnits;
            double target = goal.totalUnits - dropUnits;
            // Cutting a goal to nothing is not a suggestion. When the gap is
            // that wide, no single change fixes it and the panel says so.
            if (target > done && target >= 1 && target >= goal.totalUnits * 0.2) {
                Config c = cfg.copy();
                c.goals.get(gslot).totalUnits = target;
                add(out, id, Cost.SCOPE, "Cut the target from " + trim(goal.totalUnits) + " to "
                    + trim(target) + " " + (goal.unit == null ? "units" : goal.unit),
                    c, cfg, start, missingH, "target:" + id);
            }
        }
        return out;
    }

    /** The allocation is quantised to a quarter hour, so nothing finer is a real gap. */
    private static final double GRID_H = 0.25;

    /** Measures one candidate and records it if it delivers hours. Returns whether it clears the gap. */
    private static boolean add(List<Remedy> out, String goalId, Cost cost, String what,
                               Config candidate, Config original, LocalDate start, double missingH, String fix) {
        double recovered = measure(candidate, goalId, start, missingH);
        if (recovered <= GRID_H) return false;
        boolean clears = missingH - recovered <= GRID_H;
        out.add(new Remedy(goalId, cost, what, recovered, clears, fix));
        return clears;
    }

    /** Hours this candidate recovers, against the gap the real configuration has. */
    private static double measure(Config candidate, String goalId, LocalDate start, double missingH) {
        HorizonPlanner.Plan plan = HorizonPlanner.plan(candidate, start);
        return missingH - delivery(candidate, plan, start, goalId).missing();
    }

    private static boolean hasUpkeepToTrim(Config cfg) {
        for (Activity a : cfg.activities)
            if (a.kind == ActivityKind.MAINTENANCE && !a.leisure && a.minBlock != null && a.minBlock > 30) return true;
        return false;
    }

    private static int index(List<? extends Object> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            String other = o instanceof Goal g ? g.id : o instanceof Activity a ? a.id : null;
            if (id.equals(other)) return i;
        }
        return -1;
    }

    static String trim(Double v) {
        if (v == null) return "?";
        return v == Math.rint(v) ? String.valueOf((long) (double) v) : String.format("%.2f", v);
    }

    /** Whether a weekday holds any fixed commitment at all, for the UI's wording. */
    public static boolean hasClass(Config cfg, int dow) {
        return cfg.commitments.stream().anyMatch(c -> c.kind == CommitmentKind.FIXED
            && c.sessions.stream().anyMatch(s -> s.day == dow));
    }

    /** Room per weekday, for the UI to show where a goal actually has space. */
    public static Map<Integer, Double> roomByWeekday(Config cfg, String goalId) {
        Map<Integer, Double> out = new java.util.LinkedHashMap<>();
        for (int d = 0; d < 7; d++) out.put(d, roomH(cfg, goalId, d));
        return out;
    }
}
