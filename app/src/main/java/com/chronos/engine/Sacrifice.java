package com.chronos.engine;

import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.GoalMode;
import com.chronos.model.Goal;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What gives way when a dated goal does not fit.
 *
 * The user marks the activities that may be cut — upkeep such as a reward
 * hour or a workout, and the work of ongoing goals such as a language — and
 * whether each goes first or only as a last resort. When the plan leaves a
 * dated goal short, this picks cuts on the days before its deadline, adding
 * roughly as many hours as are missing and no more. The caller re-plans and
 * keeps a round of cuts only if it really reduced what is missing.
 *
 * A cut is recorded per day as activity id to minutes kept: upkeep keeps its
 * {@link Activity#keepMin} (zero drops it that day), an ongoing goal's
 * activity keeps nothing.
 *
 * Days are cut latest first. The plan is rebuilt every night, so a cut three
 * days out that is no longer needed by then simply never happens; spreading
 * one activity per day per round keeps any single day from losing everything.
 */
public final class Sacrifice {
    private Sacrifice() {}

    /** One day's cut, for the screens. */
    public record Cut(LocalDate date, String activityId, String name, int fromMin, int toMin) {}

    /** Minutes an upkeep activity takes on a day with these cuts. */
    public static int upkeepMinutes(Activity a, Map<String, Double> cut) {
        int full = a.minBlock == null ? 0 : a.minBlock;
        Double kept = cut == null ? null : cut.get(a.id);
        if (kept == null) return full;
        return (int) Math.max(0, Math.min(full, Math.round(kept)));
    }

    /** True when an activity is the kind that may be cut and the user allowed it. */
    public static boolean cuttable(Config cfg, Activity a) {
        if (a.sacrificeRank() == Activity.SACRIFICE_NEVER || a.restOnly || a.leisure) return false;
        if (a.kind == ActivityKind.MAINTENANCE) return true;
        Goal g = a.goalId == null ? null : cfg.goal(a.goalId);
        return g != null && g.mode == GoalMode.OPEN;
    }

    public static boolean anyCuttable(Config cfg) {
        for (Activity a : cfg.activities) if (cuttable(cfg, a)) return true;
        return false;
    }

    private record Candidate(int day, Activity act, double keep, double gainH) {}

    /**
     * The given cuts plus enough new ones to cover the plan's shortfalls, as
     * estimated from the capacity model. Returns a new list; the input is not
     * changed. Equal to the input when there is nothing left worth cutting.
     *
     * @param goalName the one short goal to cut for. One at a time, so the
     *                 caller can tell which cuts helped: a goal held back by
     *                 its own daily ceiling gains nothing from freed time, and
     *                 cutting for it would be a sacrifice for nothing.
     */
    public static List<Map<String, Double>> extend(Config cfg, LocalDate start, HorizonPlanner.Plan plan,
                                                   List<Map<String, Double>> cuts, String goalName) {
        int n = plan.days().size();
        List<Map<String, Double>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            out.add(new HashMap<>(i < cuts.size() ? cuts.get(i) : Map.of()));
        double slack = cfg.params.slackRatio == null ? 0 : cfg.params.slackRatio;

        for (HorizonPlanner.Shortfall s : plan.shortfalls()) {
            if (!s.goal().equals(goalName)) continue;
            Goal g = goalNamed(cfg, s.goal());
            if (g == null || g.deadline == null) continue;
            int last = (int) Math.min(n - 1, ChronoUnit.DAYS.between(start, g.deadline));
            if (last < 0) continue;

            // Freed time only helps on days the goal's own work may happen.
            Set<Integer> dows = new HashSet<>();
            for (Activity a : cfg.activitiesFor(g.id)) dows.addAll(a.days);

            List<Candidate> first = new ArrayList<>(), later = new ArrayList<>();
            int rounds = 0;
            List<List<Candidate>> byDayFirst = new ArrayList<>(), byDayLast = new ArrayList<>();
            for (int i = last; i >= 0; i--) {
                int dow = plan.days().get(i).dow();
                List<Candidate> f = new ArrayList<>(), l = new ArrayList<>();
                if (dows.contains(dow)) {
                    for (Activity a : cfg.activities) {
                        if (!cuttable(cfg, a) || !a.days.contains(dow) || out.get(i).containsKey(a.id)) continue;
                        Candidate c = candidate(cfg, plan, i, a, slack);
                        if (c == null) continue;
                        (a.sacrificeRank() == Activity.SACRIFICE_FIRST ? f : l).add(c);
                    }
                }
                byDayFirst.add(f);
                byDayLast.add(l);
                rounds = Math.max(rounds, Math.max(f.size(), l.size()));
            }
            // Round-robin: one activity per day per round, latest day first.
            for (int r = 0; r < rounds; r++) for (List<Candidate> d : byDayFirst) if (r < d.size()) first.add(d.get(r));
            for (int r = 0; r < rounds; r++) for (List<Candidate> d : byDayLast) if (r < d.size()) later.add(d.get(r));

            double need = s.missingH();
            for (List<Candidate> tier : List.of(first, later)) {
                for (Candidate c : tier) {
                    if (need <= 1e-6) break;
                    if (out.get(c.day()).containsKey(c.act().id)) continue;
                    out.get(c.day()).put(c.act().id, c.keep());
                    need -= c.gainH();
                }
            }
        }
        return out;
    }

    private static Candidate candidate(Config cfg, HorizonPlanner.Plan plan, int day, Activity a, double slack) {
        if (a.kind == ActivityKind.MAINTENANCE) {
            int full = a.minBlock == null ? 0 : a.minBlock;
            int keep = a.keepMin == null ? 0 : Math.max(0, a.keepMin);
            if (keep >= full) return null;
            return new Candidate(day, a, keep, (full - keep) * Math.max(1, a.maxBlocks) / 60.0 * (1 - slack));
        }
        // An ongoing goal's activity: what it was given that day, shared with its peers.
        double given = plan.allocation().get(day).getOrDefault(a.goalId, 0.0);
        if (given <= 0) return null;
        int dow = plan.days().get(day).dow();
        int peers = 0;
        for (Activity o : cfg.activitiesFor(a.goalId)) if (o.days.contains(dow)) peers++;
        return new Candidate(day, a, 0, given / Math.max(1, peers));
    }

    private static Goal goalNamed(Config cfg, String name) {
        for (Goal g : cfg.goals)
            if (g.mode == GoalMode.CLOSED && g.name != null && g.name.equals(name)) return g;
        return null;
    }

    /** The cuts as the screens show them, in date order. */
    public static List<Cut> describe(Config cfg, LocalDate start, List<Map<String, Double>> cuts) {
        List<Cut> out = new ArrayList<>();
        for (int i = 0; i < cuts.size(); i++) {
            for (Map.Entry<String, Double> e : cuts.get(i).entrySet()) {
                Activity a = cfg.activity(e.getKey());
                if (a == null) continue;
                int from = a.kind == ActivityKind.MAINTENANCE && a.minBlock != null ? a.minBlock : 0;
                int to = a.kind == ActivityKind.MAINTENANCE ? upkeepMinutes(a, cuts.get(i)) : 0;
                out.add(new Cut(start.plusDays(i), a.id, a.name, from, to));
            }
        }
        out.sort((x, y) -> x.date().compareTo(y.date()));
        return out;
    }

    /** Hours still missing for one goal, by name. */
    public static double missingH(HorizonPlanner.Plan plan, String goalName) {
        double s = 0;
        for (HorizonPlanner.Shortfall f : plan.shortfalls()) if (f.goal().equals(goalName)) s += f.missingH();
        return s;
    }

    public static boolean isEmpty(List<Map<String, Double>> cuts) {
        for (Map<String, Double> m : cuts) if (!m.isEmpty()) return false;
        return true;
    }
}
