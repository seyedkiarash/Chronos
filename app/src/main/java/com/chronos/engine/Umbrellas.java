package com.chronos.engine;

import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.GoalMode;
import com.chronos.model.Goal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Goals as umbrellas, activities as the work (your decision of 17 Oct 2026). Each goal activity
 * carries its own target — start, deadline, units, hours per unit, or hours a week and a daily
 * cap, and whether it is hard — so the planner's {@link Goal} is now the target of exactly one
 * activity, and what you call a goal is the group of them ({@link Goal#groupName}).
 *
 * This brings an older configuration over, once, and keeps it so: a goal without a group becomes
 * its own group; a goal several activities shared is split, one target per activity, with the
 * numbers left empty for you to fill in (your choice) and what the goal said kept as a note; its
 * weight and its comparisons are shared out among the new targets. Idempotent.
 */
public final class Umbrellas {
    private Umbrellas() {}

    /**
     * @param comparisons the pairwise judgements, "a|b" → ratio; changed in place for split goals
     * @return whether anything changed
     */
    public static boolean apply(Config cfg, Map<String, Double> comparisons) {
        boolean changed = false;
        for (Goal g : new ArrayList<>(cfg.goals)) {
            List<Activity> acts = new ArrayList<>();
            for (Activity a : cfg.activities) if (a.kind == ActivityKind.GOAL && g.id.equals(a.goalId)) acts.add(a);
            boolean first = g.groupName == null;
            if (first) { g.groupName = g.name; changed = true; }
            if (acts.size() > 1) { split(cfg, g, acts, comparisons); changed = true; }
            else if (first && acts.size() == 1 && acts.get(0).name != null) g.name = acts.get(0).name.trim();
        }
        return changed;
    }

    private static void split(Config cfg, Goal g, List<Activity> acts, Map<String, Double> comparisons) {
        int n = acts.size();
        String note = summary(g, acts);
        double weight = g.weight / n;
        List<Goal> made = new ArrayList<>();
        for (int k = 1; k < n; k++) {
            Activity a = acts.get(k);
            Goal t = new Goal();
            t.id = UUID.randomUUID().toString();
            t.name = a.name == null ? g.name : a.name.trim();
            t.mode = g.mode; t.hard = g.hard; t.groupName = g.groupName; t.note = note;
            t.doneUnits = 0.0; t.weight = weight;
            a.goalId = t.id;
            cfg.goals.add(cfg.goals.indexOf(g) + k, t);
            made.add(t);
        }
        // The first activity keeps the goal's own record, its numbers emptied like the others'.
        g.name = acts.get(0).name == null ? g.name : acts.get(0).name.trim();
        g.note = note; g.weight = weight;
        g.totalUnits = null; g.doneUnits = 0.0; g.unit = null; g.hoursPerUnit = null; g.deadline = null;
        g.weeklyFloorH = null; g.maxPerDayH = null;

        // Each new target is worth to every other goal what its share of the old one was.
        Map<String, Double> add = new LinkedHashMap<>();
        Map<String, Double> scaled = new HashMap<>();
        for (Map.Entry<String, Double> e : comparisons.entrySet()) {
            String[] p = e.getKey().split("\\|");
            if (p.length != 2) continue;
            String other = p[0].equals(g.id) ? p[1] : p[1].equals(g.id) ? p[0] : null;
            if (other == null) continue;
            // An hour of g was worth r hours of other; g's weight is now shared n ways.
            double r = p[0].equals(g.id) ? e.getValue() : 1 / e.getValue();
            scaled.put(g.id + "|" + other, r / n);
            for (Goal t : made) add.put(t.id + "|" + other, r / n);
        }
        comparisons.keySet().removeIf(k -> { String[] p = k.split("\\|"); return p.length == 2 && (p[0].equals(g.id) || p[1].equals(g.id)); });
        comparisons.putAll(scaled);
        comparisons.putAll(add);
        List<Goal> siblings = new ArrayList<>(made);
        siblings.add(0, g);
        for (int i = 0; i < siblings.size(); i++)
            for (int j = i + 1; j < siblings.size(); j++) comparisons.put(siblings.get(i).id + "|" + siblings.get(j).id, 1.0);
    }

    /** What the shared goal asked for, in words, to fill each activity's own target from. */
    static String summary(Goal g, List<Activity> acts) {
        StringBuilder b = new StringBuilder();
        b.append("Before, \"").append(g.groupName).append("\" was one target shared by ");
        List<String> names = new ArrayList<>();
        for (Activity a : acts) names.add(a.name == null ? "?" : a.name.trim());
        b.append(String.join(", ", names)).append(": ");
        if (g.mode == GoalMode.CLOSED)
            b.append(String.format(Locale.ROOT, "%s of %s %s done, %s h per unit, due %s",
                num(g.doneUnits), num(g.totalUnits), g.unit == null ? "units" : g.unit, num(g.hoursPerUnit), g.deadline));
        else b.append(String.format(Locale.ROOT, "%s h a week", num(g.weeklyFloorH)));
        if (g.maxPerDayH != null) b.append(String.format(Locale.ROOT, ", at most %s h a day", num(g.maxPerDayH)));
        return b.append('.').toString();
    }

    private static String num(Double v) {
        if (v == null) return "?";
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r);
    }

    /**
     * Goal work moved to another goal (your request of 27 Oct 2026). Its target was set for the goal it
     * served, so what belongs to it there — the kind of target, amount and what is done of it, unit, hours
     * per unit, deadline, weekly floor, daily ceiling, whether it is hard, and its priority — starts again
     * empty, as for new work, for you to enter for the new goal; Setup holds the planner until you do.
     * The activity keeps everything of its own: days, hours, lengths, loads, its log and what was learned.
     *
     * @return whether it moved (false when it already serves that goal)
     */
    public static boolean move(Goal t, String group) {
        String from = t.groupName == null ? t.name : t.groupName;
        if (group == null || group.isBlank() || group.equals(from)) return false;
        t.groupName = group;
        t.note = "Moved from \"" + from + "\": enter its target again for this goal.";
        t.mode = GoalMode.CLOSED; t.hard = true;
        t.totalUnits = null; t.doneUnits = 0.0; t.unit = null; t.hoursPerUnit = null;
        t.deadline = null; t.deadlineTime = null; t.weeklyFloorH = null; t.maxPerDayH = null;
        t.tier = null; t.rank = null; t.tierAuto = false;
        return true;
    }

    /** The goals as you see them: each group's name and its targets, in the order they come. */
    public static Map<String, List<Goal>> groups(Config cfg) {
        Map<String, List<Goal>> out = new LinkedHashMap<>();
        for (Goal g : cfg.goals) out.computeIfAbsent(g.groupName == null ? g.name : g.groupName, k -> new ArrayList<>()).add(g);
        return out;
    }

    /** The one goal activity a target belongs to, or null. */
    public static Activity activityOf(Config cfg, Goal g) {
        for (Activity a : cfg.activities) if (a.kind == ActivityKind.GOAL && g.id.equals(a.goalId)) return a;
        return null;
    }
}
