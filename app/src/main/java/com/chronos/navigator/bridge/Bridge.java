package com.chronos.navigator.bridge;

import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums;
import com.chronos.model.Goal;
import com.chronos.model.LogEntry;
import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Requirements;
import com.chronos.navigator.store.NavEntities.AllocationRow;
import com.chronos.navigator.store.NavEntities.DayTypeRow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The two-way bridge between the Navigator and Chronos (spec, "Integration
 * with the daily scheduler"). The daily scheduler itself is not touched:
 *
 *  Chronos → Navigator: the log (adherence, bias, progress), the hourly
 *    energy ratings, the ticked day types, each goal's units and weight;
 *  Navigator → Chronos: the accepted week's hours become the weekly floors of
 *    the ongoing goals the nodes are worked on through, and a node the week
 *    gives nothing to pauses its goal (daily ceiling 0). Goals with a deadline
 *    keep running on their own deadline and units.
 *
 * Every goal the bridge changes has its own values kept first, so following
 * the Navigator can be stopped and everything put back.
 */
public final class Bridge {
    private Bridge() {}

    /** Key suffix of a link from a node straight to a Chronos activity, for nodes the graph names none for. */
    public static final String ANY = "*";

    // ------------------------------------------------------- Chronos → Navigator

    /** The Chronos activities each node's work is logged under: its named ones (after links) and direct links. */
    public static Map<String, List<String>> nodeActivities(Graph g, Config cfg, Map<String, String> links) {
        Set<String> names = new LinkedHashSet<>();
        for (Activity a : cfg.activities) if (a.name != null) names.add(Requirements.norm(a.name));
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Node n : g.nodes.values()) {
            List<String> acts = new ArrayList<>();
            for (String a : n.activities) {
                String r = Requirements.resolve(n.id, a, names, links);
                if (r != null && !acts.contains(r)) acts.add(r);
            }
            String direct = links.get(n.id + "|" + ANY);
            if (direct != null && names.contains(Requirements.norm(direct)) && !acts.contains(Requirements.norm(direct)))
                acts.add(Requirements.norm(direct));
            if (!acts.isEmpty()) out.put(n.id, acts);
        }
        return out;
    }

    public static Inputs inputs(Graph g, Config cfg, List<LogEntry> logs, Map<String, String> links, List<DayTypeRow> days) {
        Inputs in = new Inputs();
        in.nodeActivities.putAll(nodeActivities(g, cfg, links));
        for (Activity a : cfg.activities) {
            if (a.name == null) continue;
            Goal goal = a.goalId == null ? null : cfg.goal(a.goalId);
            if (goal == null) continue;
            String name = Requirements.norm(a.name);
            in.goalWeights.put(name, goal.weight);
            if (goal.mode == Enums.GoalMode.CLOSED && goal.hoursPerUnit != null)
                in.unitGoals.put(name, new Inputs.UnitGoal(goal.hoursPerUnit,
                    goal.doneUnits == null ? 0 : goal.doneUnits, goal.totalUnits));
        }
        for (LogEntry l : logs) {
            if (l.date == null || l.id == null) continue;
            if (l.kind == Enums.LogKind.CHECKIN && l.id.startsWith("energy|") && l.energy != null) {
                try {
                    in.ratings.add(new Inputs.Rating(l.date, Integer.parseInt(l.id.substring(l.id.lastIndexOf('|') + 1)), l.energy));
                } catch (NumberFormatException ignored) { }
                continue;
            }
            if (l.kind != Enums.LogKind.SESSION) continue;
            Activity a = cfg.activity(l.actId);
            if (a == null || a.name == null) continue;
            // A block of the plan has a "session|" id; entries from before ids had a shape carry a planned length.
            boolean planned = l.id.startsWith("session|") || (!l.id.contains("|") && l.planned != null);
            boolean skipped = l.at == null;
            in.sessions.add(new Inputs.Session(l.date, Requirements.norm(a.name), l.planned,
                skipped ? null : l.actual, planned, skipped, l.units, a.kind == Enums.ActivityKind.GOAL));
        }
        for (DayTypeRow d : days) {
            if (d.type == null) continue;
            in.days.add(new Inputs.DayRecord(LocalDate.parse(d.date), Kinds.DayType.valueOf(d.type), d.anomaly));
        }
        return in;
    }

    /**
     * What Chronos itself plans for goals with a deadline, which it paces on
     * its own: per node, the hours its goal gets on each coming day. Today
     * counts only what is still to come — what was logged today is already in
     * the goal's units.
     *
     * @param goalHours per date, goal id to the hours Chronos's horizon gives it that day
     */
    public static void chronosPlan(Inputs in, Graph g, Config cfg, Map<LocalDate, Map<String, Double>> goalHours,
                                   List<LogEntry> logs, LocalDate today) {
        for (Map.Entry<String, List<String>> e : in.nodeActivities.entrySet()) {
            Node n = g.node(e.getKey());
            if (n == null || !n.takesHours()) continue;
            Set<String> goals = new LinkedHashSet<>();
            Set<String> actIds = new LinkedHashSet<>();
            for (Activity a : cfg.activities) {
                if (a.name == null || a.goalId == null || !e.getValue().contains(Requirements.norm(a.name))) continue;
                Goal goal = cfg.goal(a.goalId);
                if (goal == null || goal.mode != Enums.GoalMode.CLOSED) continue;
                goals.add(goal.id);
                actIds.add(a.id);
            }
            if (goals.isEmpty()) continue;
            double doneToday = 0;
            for (LogEntry l : logs)
                if (l.kind == Enums.LogKind.SESSION && today.equals(l.date) && l.at != null && l.actual != null && actIds.contains(l.actId))
                    doneToday += l.actual / 60.0;
            Map<LocalDate, Double> plan = new java.util.TreeMap<>();
            for (Map.Entry<LocalDate, Map<String, Double>> d : goalHours.entrySet()) {
                if (d.getKey().isBefore(today)) continue;
                double h = 0;
                for (String goal : goals) h += d.getValue().getOrDefault(goal, 0.0);
                if (d.getKey().equals(today)) h = Math.max(0, h - doneToday);
                plan.put(d.getKey(), h);
            }
            if (!plan.isEmpty()) in.chronosPlan.put(n.id, plan);
        }
    }

    /** Hours of fixed commitments on a weekday (0 = Saturday), for the day-type suggestion. */
    public static double fixedHours(Config cfg, int dow) {
        double h = 0;
        for (com.chronos.model.Commitment c : cfg.commitments) {
            if (c.kind != Enums.CommitmentKind.FIXED) continue;
            for (com.chronos.model.Commitment.Session s : c.sessions) {
                if (s.day != dow || s.start == null || s.end == null) continue;
                int a = com.chronos.engine.Slots.parse(s.start), b = com.chronos.engine.Slots.parse(s.end);
                if (b > a) h += (b - a) * com.chronos.engine.Slots.SLOT_MIN / 60.0;
            }
        }
        return h;
    }

    // ------------------------------------------------------- Navigator → Chronos

    /** One goal changed by applying a week, before and after. */
    public record Change(String goalId, String goalName, Double floorBefore, Double floorAfter,
                         Double ceilingBefore, Double ceilingAfter) {}

    /** What applying needs to keep, so it can be undone: each goal's own floor and ceiling. */
    public record Backup(String goalId, Double weeklyFloorH, Double maxPerDayH) {}

    /** The ongoing Chronos goals the graph's nodes are worked on through, and the nodes for each. */
    public static Map<String, List<String>> managedGoals(Graph g, Config cfg, Map<String, List<String>> nodeActs) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : nodeActs.entrySet()) {
            Node n = g.node(e.getKey());
            if (n == null || !n.takesHours()) continue;
            for (String act : e.getValue()) {
                for (Activity a : cfg.activities) {
                    if (a.name == null || !Requirements.norm(a.name).equals(act) || a.goalId == null) continue;
                    Goal goal = cfg.goal(a.goalId);
                    if (goal == null || goal.mode != Enums.GoalMode.OPEN) continue;
                    List<String> nodes = out.computeIfAbsent(goal.id, k -> new ArrayList<>());
                    if (!nodes.contains(n.id)) nodes.add(n.id);
                }
            }
        }
        return out;
    }

    /**
     * Writes an accepted week into the configuration (which the caller saves).
     * A goal several nodes work through gets the sum of their hours; a goal
     * whose nodes get nothing this week is paused with a ceiling of 0.
     *
     * @param backups the goals' own values, kept already; new ones are added for goals touched the first time
     */
    public static List<Change> apply(Graph g, Config cfg, Map<String, List<String>> nodeActs,
                                     List<AllocationRow> week, Map<String, Backup> backups) {
        Map<String, Double> hours = new LinkedHashMap<>();
        for (AllocationRow r : week) hours.merge(r.nodeId, r.plannedH, Double::sum);
        List<Change> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : managedGoals(g, cfg, nodeActs).entrySet()) {
            Goal goal = cfg.goal(e.getKey());
            if (goal == null) continue;
            backups.putIfAbsent(goal.id, new Backup(goal.id, goal.weeklyFloorH, goal.maxPerDayH));
            Backup own = backups.get(goal.id);
            double h = 0;
            for (String node : e.getValue()) h += hours.getOrDefault(node, 0.0);
            h = Math.round(h * 4) / 4.0;
            Double floorBefore = goal.weeklyFloorH, ceilingBefore = goal.maxPerDayH;
            goal.weeklyFloorH = h;
            // Nothing this week: the goal waits (a ceiling of 0 keeps the scheduler from giving it time by weight).
            // Otherwise its own ceiling, or the node's if the goal never had one.
            if (h <= 0) goal.maxPerDayH = 0.0;
            else {
                Double ceiling = own.maxPerDayH();
                if (ceiling == null || ceiling <= 0) {
                    for (String node : e.getValue()) {
                        Node n = g.node(node);
                        if (n != null && n.maxHPerDay != null) ceiling = Math.max(ceiling == null ? 0 : ceiling, n.maxHPerDay);
                    }
                }
                goal.maxPerDayH = ceiling == null || ceiling <= 0 ? Math.max(0.5, Math.ceil(h / 7 * 2) / 2) : ceiling;
            }
            if (!java.util.Objects.equals(floorBefore, goal.weeklyFloorH) || !java.util.Objects.equals(ceilingBefore, goal.maxPerDayH))
                out.add(new Change(goal.id, goal.name, floorBefore, goal.weeklyFloorH, ceilingBefore, goal.maxPerDayH));
        }
        return out;
    }

    /** Puts every goal the bridge changed back to its own values. */
    public static List<Change> restore(Config cfg, Map<String, Backup> backups) {
        List<Change> out = new ArrayList<>();
        for (Backup b : backups.values()) {
            Goal goal = cfg.goal(b.goalId());
            if (goal == null) continue;
            out.add(new Change(goal.id, goal.name, goal.weeklyFloorH, b.weeklyFloorH(), goal.maxPerDayH, b.maxPerDayH()));
            goal.weeklyFloorH = b.weeklyFloorH();
            goal.maxPerDayH = b.maxPerDayH();
        }
        return out;
    }
}
