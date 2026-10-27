package com.chronos.model;

import java.util.ArrayList;
import java.util.List;

/** Everything the engine reads. Starts genuinely empty: no seeded examples. */
public class Config {
    public Params params = new Params();
    public List<Goal> goals = new ArrayList<>();
    public List<Activity> activities = new ArrayList<>();
    public List<Commitment> commitments = new ArrayList<>();

    public Goal goal(String id) {
        for (Goal g : goals) if (g.id.equals(id)) return g;
        return null;
    }
    public Activity activity(String id) {
        for (Activity a : activities) if (a.id.equals(id)) return a;
        return null;
    }
    public List<Activity> activitiesFor(String goalId) {
        List<Activity> out = new ArrayList<>();
        for (Activity a : activities)
            if (a.kind == Enums.ActivityKind.GOAL && goalId.equals(a.goalId)) out.add(a);
        return out;
    }

    /**
     * The configuration as one date sees it: activities that have not started
     * by then are left out. Shares everything else with this one, so it is
     * cheap and read-only — never edit through it.
     */
    public Config on(java.time.LocalDate date) {
        int floor = dayFloor(date);
        boolean all = true;
        // An archived activity is not planned on any day, so it is left out even without a date.
        for (Activity a : activities)
            if (!a.startedBy(date) || a.archived || hoursOn(a, date, floor) != null || a.windowsByDay()) { all = false; break; }
        if (all) return this;
        Config c = new Config();
        c.params = params;
        c.goals = goals;
        c.commitments = commitments;
        c.planFrom = planFrom;
        int dow = date == null ? -1 : com.chronos.engine.Slots.dow(date);
        for (Activity a : activities) {
            if (!a.startedBy(date) || a.archived) continue;
            if (a.windowsByDay() && dow >= 0) {
                // Hours of its own on this weekday: only the windows that hold today. With none, it is not planned today.
                List<Activity.Window> today = a.windowsOn(dow);
                if (today.isEmpty()) continue;
                Activity b = a.copy();
                b.windows.clear();
                for (Activity.Window w : today) b.windows.add(new Activity.Window(w.from, w.to));
                a = b;
            }
            int[] span = hoursOn(a, date, floor);
            if (span == null) { c.activities.add(a); continue; }
            Activity narrowed = narrowed(a, span[0], span[1], date);
            if (narrowed != null) c.activities.add(narrowed);
        }
        return c;
    }

    /**
     * Where the plan begins, when it was built from a given hour (your request of 22 Oct 2026):
     * nothing is placed earlier on that day. Not part of the stored configuration; the
     * repository fills it in from the app's state.
     */
    public java.time.LocalDateTime planFrom;

    /**
     * Whether an activity's time is over on a day (26 Oct 2026): upkeep past its last day, goal work
     * whose target's deadline has passed or whose amount is done. What the log says about it is no
     * longer a reason to change anything; its sessions still say how the days that held them tired you.
     */
    public boolean over(Activity a, java.time.LocalDate day) {
        if (a.endedBy(day)) return true;
        if (a.kind != com.chronos.model.Enums.ActivityKind.GOAL) return false;
        Goal g = goal(a.goalId);
        if (g == null || g.mode != com.chronos.model.Enums.GoalMode.CLOSED) return false;
        if (g.deadline != null && g.deadline.isBefore(day)) return true;
        return g.totalUnits != null && g.totalUnits > 0 && g.remainingUnits() <= 0;
    }

    /** The first minute of a date the plan may use: past the plan's start hour on its first day, else 0. */
    public int dayFloor(java.time.LocalDate date) {
        if (planFrom == null || date == null || !planFrom.toLocalDate().equals(date)) return 0;
        return planFrom.getHour() * 60 + planFrom.getMinute();
    }

    private static int minutes(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
    }

    /**
     * The minutes [from, to) an activity is held to on a date beyond its own windows: after
     * its start time on its first day, before its goal's deadline time on the deadline day,
     * after the plan's start hour. Null when nothing narrows it that day.
     */
    int[] hoursOn(Activity a, java.time.LocalDate date, int floor) {
        int from = floor;
        if (a.startTime != null && date != null && date.equals(a.startDate)) from = Math.max(from, minutes(a.startTime));
        int to = 1440;
        if (a.goalId != null && date != null) {
            Goal g = goal(a.goalId);
            if (g != null && g.deadlineTime != null && date.equals(g.deadline)) to = minutes(g.deadlineTime);
        }
        return from <= 0 && to >= 1440 ? null : new int[]{from, to};
    }

    /** A copy of the activity whose windows and pins keep only [from, to); null when nothing is left. */
    static Activity narrowed(Activity a, int from, int to, java.time.LocalDate date) {
        Activity b = a.copy();
        b.windows.clear();
        List<int[]> spans = new ArrayList<>();
        if (a.windows.isEmpty()) spans.add(new int[]{0, 1440});
        for (Activity.Window w : a.windows) {
            int[] s = com.chronos.engine.Slots.span(w.from, w.to);
            if (s != null) spans.add(new int[]{s[0] * com.chronos.engine.Slots.SLOT_MIN, s[1] * com.chronos.engine.Slots.SLOT_MIN});
        }
        for (int[] s : spans) {
            int lo = Math.max(s[0], from), hi = Math.min(s[1], to);
            if (hi - lo >= com.chronos.engine.Slots.SLOT_MIN)
                b.windows.add(new Activity.Window(com.chronos.engine.Slots.formatMinutes(lo), com.chronos.engine.Slots.formatMinutes(hi)));
        }
        if (b.windows.isEmpty()) return null;
        int dow = com.chronos.engine.Slots.dow(date);
        b.pins.removeIf(p -> {
            if (p.day != dow || p.start == null) return false;
            int m = minutes(p.start);
            return m < from || m >= to;
        });
        return b;
    }

    /**
     * A deep copy. The feasibility search perturbs one setting at a time and
     * re-plans, so it must never touch the configuration the user is looking at.
     */
    public Config copy() {
        Config c = new Config();
        c.params = params == null ? null : params.copy();
        for (Goal g : goals) c.goals.add(g.copy());
        for (Activity a : activities) c.activities.add(a.copy());
        for (Commitment m : commitments) c.commitments.add(m.copy());
        c.planFrom = planFrom;
        return c;
    }
}
