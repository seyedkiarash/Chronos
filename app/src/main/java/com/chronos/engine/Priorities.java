package com.chronos.engine;

import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums.GoalMode;
import com.chronos.model.Goal;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Priorities as levels and an order (your choice of 26 Oct 2026), in place of comparing every
 * pair of targets — which grows with the square of their number and was easy to forget for a new one.
 *
 * Each target sits in one of four levels and has a place within it. Its weight follows from both:
 * the level's base, from 1.25 times it at the top of the level down to 0.8 times at the bottom, so
 * the levels never overlap. A target nobody has placed yet is placed by the app — a level suggested
 * from its goal, its deadline and whether it may be cut, and a place by its deadline — and marked as
 * chosen by the app until you confirm, move or relevel it.
 */
public final class Priorities {
    private Priorities() {}

    public static final int CRITICAL = 0, IMPORTANT = 1, NORMAL = 2, OPTIONAL = 3;
    public static final double[] BASE = {8, 4, 2, 1};
    public static final String[] NAMES = {"Critical", "Important", "Normal", "Optional"};
    static final double TOP = 1.25, BOTTOM = 0.8;

    /** @param why the reasons, in English (the screen translates them) */
    public record Suggestion(int tier, List<String> why) {}

    /** Whether a target is still worked on: not archived, not past its deadline, not done. */
    public static boolean inPlay(Config cfg, Goal g, LocalDate day) {
        Activity a = Umbrellas.activityOf(cfg, g);
        if (a != null) return !a.archived && !cfg.over(a, day);
        if (g.mode != GoalMode.CLOSED) return true;
        if (g.deadline != null && g.deadline.isBefore(day)) return false;
        return g.totalUnits == null || g.totalUnits <= 0 || g.remainingUnits() > 0;
    }

    /** The level the app would give a target, and why. */
    public static Suggestion suggest(Config cfg, Goal g, LocalDate today) {
        List<String> why = new ArrayList<>();
        Activity a = Umbrellas.activityOf(cfg, g);
        Long days = g.mode == GoalMode.CLOSED && g.deadline != null ? ChronoUnit.DAYS.between(today, g.deadline) : null;

        // The goal's other activities, where you already chose: the same level as most of them.
        int[] sib = new int[4];
        for (Goal o : cfg.goals)
            if (o != g && o.tier != null && !o.tierAuto && o.groupName != null && o.groupName.equals(g.groupName)) sib[o.tier]++;
        int most = -1;
        for (int t = 0; t < 4; t++) if (sib[t] > 0 && (most < 0 || sib[t] > sib[most])) most = t;

        int tier;
        if (g.mode == GoalMode.CLOSED && g.hard) {
            tier = days != null && days <= 7 ? CRITICAL : IMPORTANT;
            why.add("the deadline is hard");
            if (days != null && days >= 0) why.add("the deadline is in " + days + " days");
        } else if (most >= 0) {
            tier = most;
            why.add("the other activities of its goal are " + NAMES[most].toLowerCase());
        } else if (a != null && a.sacrifice != null && a.sacrifice == Activity.SACRIFICE_NEVER) {
            tier = IMPORTANT;
            why.add("it may never be cut");
        } else if (a != null && a.sacrifice != null && a.sacrifice == Activity.SACRIFICE_FIRST) {
            tier = OPTIONAL;
            why.add("it is cut first when a deadline is at risk");
        } else if (a != null && a.sacrifice != null && a.sacrifice == Activity.SACRIFICE_LAST) {
            tier = NORMAL;
            why.add("it is cut only as a last resort");
        } else if (days != null && days >= 0 && days <= 14) {
            tier = IMPORTANT;
            why.add("the deadline is in " + days + " days");
        } else {
            tier = NORMAL;
            why.add("nothing says otherwise");
        }
        return new Suggestion(tier, why);
    }

    /** The targets of a level in their order. */
    public static List<Goal> level(Config cfg, int tier) {
        List<Goal> out = new ArrayList<>();
        for (Goal g : cfg.goals) if (g.tier != null && g.tier == tier) out.add(g);
        out.sort(Comparator.comparingInt((Goal g) -> g.rank == null ? Integer.MAX_VALUE : g.rank));
        return out;
    }

    /** Puts a target into its level by its deadline: before the first one due later; open work at the end. */
    static void place(Config cfg, Goal g) {
        List<Goal> others = level(cfg, g.tier);
        others.remove(g);
        int at = others.size();
        if (g.mode == GoalMode.CLOSED && g.deadline != null)
            for (int i = 0; i < others.size(); i++) {
                Goal o = others.get(i);
                boolean later = o.mode != GoalMode.CLOSED || o.deadline == null || o.deadline.isAfter(g.deadline);
                if (later) { at = i; break; }
            }
        others.add(at, g);
        for (int i = 0; i < others.size(); i++) others.get(i).rank = i;
    }

    /**
     * Places what has no level yet and works out every weight. Targets that all come without a level
     * at once and carry weights (from the pairwise comparisons this replaced) are leveled by those
     * weights and keep their order. Returns whether anything changed.
     */
    public static boolean settle(Config cfg, LocalDate today) {
        boolean changed = false;
        List<Goal> fresh = new ArrayList<>();
        for (Goal g : cfg.goals) if (g.tier == null) fresh.add(g);
        if (!fresh.isEmpty()) {
            changed = true;
            double max = 0;
            for (Goal g : fresh) max = Math.max(max, g.weight);
            boolean fromComparisons = fresh.size() == cfg.goals.size() && fresh.size() > 1 && max > 0;
            if (fromComparisons) {
                fresh.sort(Comparator.comparingDouble((Goal g) -> -g.weight));
                int[] next = new int[4];
                for (Goal g : fresh) {
                    double r = g.weight / max;
                    g.tier = r >= 0.6 ? CRITICAL : r >= 0.3 ? IMPORTANT : r >= 0.12 ? NORMAL : OPTIONAL;
                    g.rank = next[g.tier]++;
                    g.tierAuto = true;
                }
            } else {
                for (Goal g : fresh) {
                    g.tier = suggest(cfg, g, today).tier();
                    g.tierAuto = true;
                    place(cfg, g);
                }
            }
        }
        // Places run 0, 1, 2… within each level.
        for (int t = 0; t < 4; t++) {
            List<Goal> lv = level(cfg, t);
            for (int i = 0; i < lv.size(); i++)
                if (lv.get(i).rank == null || lv.get(i).rank != i) { lv.get(i).rank = i; changed = true; }
        }
        Map<String, Double> w = weights(cfg, today);
        for (Goal g : cfg.goals) {
            double v = w.getOrDefault(g.id, 0.0);
            if (Math.abs(v - g.weight) > 1e-9) { g.weight = v; changed = true; }
        }
        return changed;
    }

    /** Weights from levels and places, summing to one over the targets in play. */
    public static Map<String, Double> weights(Config cfg, LocalDate today) {
        Map<String, Double> raw = new HashMap<>();
        double sum = 0;
        for (int t = 0; t < 4; t++) {
            List<Goal> playing = new ArrayList<>();
            for (Goal g : level(cfg, t)) {
                if (inPlay(cfg, g, today)) playing.add(g);
                else raw.put(g.id, BASE[t] * BOTTOM);
            }
            int n = playing.size();
            for (int i = 0; i < n; i++) {
                double f = n == 1 ? 1.0 : TOP - (TOP - BOTTOM) * i / (n - 1);
                raw.put(playing.get(i).id, BASE[t] * f);
                sum += BASE[t] * f;
            }
        }
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, Double> e : raw.entrySet()) out.put(e.getKey(), sum > 0 ? e.getValue() / sum : 0);
        return out;
    }
}
