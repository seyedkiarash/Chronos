package com.chronos.engine;

import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.GoalMode;
import com.chronos.model.Goal;
import com.chronos.model.Params;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides how many hours of each goal land on each calendar day, before any
 * single day is arranged. Two things happen here that a day-at-a-time planner
 * cannot do: an exact deadline feasibility test, and pacing that serves
 * whichever goal has the least room to spare.
 */
public final class HorizonPlanner {
    private HorizonPlanner() {}

    public record DayCapacity(int index, LocalDate date, int dow,
                              double freeH, double upkeepH, double travelH,
                              double capacityH, double highFocusH) {}

    public record Violation(LocalDate by, double demandH, double supplyH,
                            double deficitH, List<String> goals) {}

    public record Shortfall(String goal, double missingH, LocalDate deadline) {}

    public record Plan(List<DayCapacity> days,
                       List<Map<String, Double>> allocation,
                       List<Violation> violations,
                       List<Shortfall> shortfalls,
                       List<Shortfall> overdue,
                       double[] usedH) {}

    /** What one goal still needs, and where it is allowed to go. */
    private static final class Need {
        Goal goal;
        double qEff, needH, minChunkH;
        Set<Integer> dows;
        List<Activity> acts;
        int deadlineIndex;

        /** Whether some activity of the goal may run on this day: its weekday, and started. */
        boolean allows(DayCapacity d) {
            for (Activity a : acts) if (a.days.contains(d.dow()) && a.startedBy(d.date())) return true;
            return false;
        }
        boolean overdue;
        double remaining;
    }

    public static DayCapacity capacityOf(Config cfg, LocalDate date, int index) {
        return capacityOf(cfg, date, index, Map.of());
    }

    /**
     * @param cut minutes each upkeep activity keeps on this day, by activity
     *            id, where it was cut to make room for a deadline (see
     *            {@link Sacrifice}). Absent means its usual length.
     */
    public static DayCapacity capacityOf(Config cfg, LocalDate date, int index, Map<String, Double> cut) {
        Params p = cfg.params;
        // The plan built from an hour: the time before it that day is not the plan's to give.
        int floorSlot = (cfg.dayFloor(date) + Slots.SLOT_MIN - 1) / Slots.SLOT_MIN;
        cfg = cfg.on(date);               // upkeep that has not started takes no time yet
        int dow = Slots.dow(date);
        FixedFrame.Frame frame = FixedFrame.of(p, cfg.commitments, dow);
        int freeSlots = frame.freeSlots();
        for (int t = 0; t < Math.min(floorSlot, Slots.PER_DAY); t++) if (frame.occupant()[t] == null) freeSlots--;

        int upkeepMin = 0;
        for (Activity a : cfg.activities)
            if (a.kind == ActivityKind.MAINTENANCE && a.days.contains(dow) && !a.restOnly && !a.leisure)
                // Every session counts: three prayers a day are three blocks of time.
                upkeepMin += Sacrifice.upkeepMinutes(a, cut) * Math.max(1, a.maxBlocks);
        int travelMin = FixedFrame.travelMinutes(cfg, dow);

        double grossMin = freeSlots * Slots.SLOT_MIN - upkeepMin - travelMin;
        double netMin = Math.max(0, grossMin * (1 - p.slackRatio));

        double[] h = Energy.homeostatic(p);
        double highMin = 0;
        for (int t = Math.min(floorSlot, Slots.PER_DAY); t < Slots.PER_DAY; t++) {
            if (frame.occupant()[t] != null) continue;
            // A typical day: both fatigues halfway to their safe lines.
            double e = Energy.capacity(p, t, h[t], p.fSafe / 2, (p.fSafeP == null ? p.fSafe : p.fSafeP) / 2);
            if (e >= 0.62) highMin += Slots.SLOT_MIN;
        }
        double highH = Math.max(0, highMin - upkeepMin - travelMin) / 60.0 * (1 - p.slackRatio);

        return new DayCapacity(index, date, dow, freeSlots * Slots.SLOT_MIN / 60.0,
                               upkeepMin / 60.0, travelMin / 60.0, netMin / 60.0, highH);
    }

    private static Need needOf(Config cfg, Goal g, LocalDate start, int horizon) {
        Need n = new Need();
        n.goal = g;
        List<Activity> acts = cfg.activitiesFor(g.id);
        n.acts = acts;
        double qSum = 0;
        n.dows = new HashSet<>();
        double minChunk = Double.MAX_VALUE;
        for (Activity a : acts) {
            qSum += Math.max(0.05, a.q - cfg.params.lcbBeta * a.qSigma);
            n.dows.addAll(a.days);
            minChunk = Math.min(minChunk, a.minBlock / 60.0);
        }
        n.qEff = acts.isEmpty() ? 1 : qSum / acts.size();
        n.minChunkH = acts.isEmpty() ? 0.5 : minChunk;
        n.needH = g.mode == GoalMode.CLOSED ? g.remainingUnits() * g.hoursPerUnit / n.qEff : 0;
        n.remaining = n.needH;
        if (g.mode == GoalMode.CLOSED && g.deadline != null) {
            long raw = ChronoUnit.DAYS.between(start, g.deadline);
            n.overdue = raw < 0;
            n.deadlineIndex = (int) Slots.clamp((int) raw, 0, horizon - 1);
        } else {
            n.deadlineIndex = horizon - 1;
        }
        return n;
    }

    /**
     * Horn's condition for preemptive scheduling with deadlines: for every
     * deadline, the work due on or before it must fit in the capacity that
     * exists on or before it. Necessary and sufficient, so a pass here is a
     * real guarantee and a failure names exactly which cluster is impossible.
     */
    private static List<Violation> certify(List<DayCapacity> days, List<Need> closed) {
        Set<Integer> deadlines = new LinkedHashSet<>();
        closed.stream().map(n -> n.deadlineIndex).sorted().forEach(deadlines::add);
        List<Violation> out = new ArrayList<>();
        for (int tau : deadlines) {
            double demand = 0;
            List<String> names = new ArrayList<>();
            for (Need n : closed) if (n.deadlineIndex <= tau) { demand += n.needH; names.add(n.goal.name); }
            double supply = 0;
            for (int i = 0; i <= tau && i < days.size(); i++) supply += days.get(i).capacityH();
            if (demand > supply + 1e-6)
                out.add(new Violation(days.get(tau).date(), demand, supply, demand - supply, names));
        }
        return out;
    }

    public static Plan plan(Config cfg, LocalDate start) {
        return plan(cfg, start, Map.of());
    }

    /**
     * @param dayCaps an upper bound, in hours, on what a goal may be given on a
     *                particular day index — what the day sequencer showed it can
     *                actually place there. The capacity model of §7.1 counts
     *                free hours, not whether an activity's allowed window has an
     *                unbroken stretch left in it after a class, a meal and the
     *                trip home, so a day can be given hours it cannot arrange.
     *                Feeding the measurement back lets the water-filling of §7.6
     *                move those hours to a day that can hold them, instead of
     *                losing them.
     */
    public static Plan plan(Config cfg, LocalDate start, Map<Integer, Map<String, Double>> dayCaps) {
        return plan(cfg, start, dayCaps, List.of());
    }

    /** @param cuts per day index, the cuts {@link Sacrifice} made; missing days have none. */
    public static Plan plan(Config cfg, LocalDate start, Map<Integer, Map<String, Double>> dayCaps,
                            List<Map<String, Double>> cuts) {
        return plan(cfg, start, dayCaps, cuts, new double[0]);
    }

    /**
     * @param scale per day index, the share of a normal day's capacity it may
     *              be given: 0 for a rest day, a fraction for the days of
     *              coming back from one. Missing days are 1.
     */
    public static Plan plan(Config cfg, LocalDate start, Map<Integer, Map<String, Double>> dayCaps,
                            List<Map<String, Double>> cuts, double[] scale) {
        int n = Math.max(1, cfg.params.horizonDays);
        List<DayCapacity> days = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            DayCapacity d = capacityOf(cfg, start.plusDays(i), i, i < cuts.size() ? cuts.get(i) : Map.of());
            double s = i < scale.length ? Math.max(0, scale[i]) : 1;
            if (s != 1) d = new DayCapacity(d.index(), d.date(), d.dow(), d.freeH(), d.upkeepH(), d.travelH(),
                                            d.capacityH() * s, d.highFocusH() * s);
            days.add(d);
        }

        List<Need> closed = new ArrayList<>(), open = new ArrayList<>();
        for (Goal g : cfg.goals) {
            Need need = needOf(cfg, g, start, n);
            if (g.mode == GoalMode.CLOSED) { if (need.needH > 0) closed.add(need); }
            else open.add(need);
        }

        List<Violation> violations = certify(days, closed);

        List<Map<String, Double>> alloc = new ArrayList<>(n);
        double[] used = new double[n];
        for (int i = 0; i < n; i++) alloc.add(new HashMap<>());

        double openWeight = open.stream().mapToDouble(o -> o.goal.weight).sum();
        if (openWeight <= 0) openWeight = 1;

        for (int d = 0; d < n; d++) {
            final int day = d;
            double capacity = days.get(d).capacityH();

            List<Need> live = new ArrayList<>();
            for (Need c : closed)
                if (c.remaining > 1e-6 && day <= c.deadlineIndex && c.allows(days.get(day)))
                    live.add(c);
            live.sort(Comparator.comparingDouble(c -> slack(days, c, day)));

            for (Need c : live) {
                if (capacity <= 1e-6) break;
                double reachable = reachableCapacity(days, c, day);
                double ideal = reachable > 0 ? c.remaining * (days.get(day).capacityH() / reachable) : c.remaining;
                double cap = Math.min(c.goal.maxPerDayH == null ? 99 : c.goal.maxPerDayH,
                                      dayCap(dayCaps, day, c.goal.id));
                double take = Math.min(Math.min(capacity, c.remaining), Math.min(Math.max(ideal, 0), cap));
                if (take < c.minChunkH) take = Math.min(Math.min(capacity, c.remaining), c.minChunkH);
                // The last of the work may be shorter than a session: 25 minutes that finish the target are
                // worth placing. Skipping it left the goal "short" with hours free (seen 23 Oct 2026).
                boolean finishing = c.remaining <= c.minChunkH + 1e-6 && take >= c.remaining - 1e-6;
                if (take < c.minChunkH * 0.6 && !finishing) continue;
                take = finishing ? Math.min(Math.min(capacity, cap), Math.ceil(c.remaining * 4) / 4.0)
                                 : Math.round(take * 4) / 4.0;
                if (take <= 0) continue;
                alloc.get(day).merge(c.goal.id, take, Double::sum);
                c.remaining -= take;
                capacity -= take;
            }

            for (Need o : open) {
                if (capacity <= 1e-6) break;
                if (!o.allows(days.get(day))) continue;
                double daily = o.goal.weeklyFloorH == null ? 0 : o.goal.weeklyFloorH / 7.0;
                double cap = Math.min(o.goal.maxPerDayH == null ? 99 : o.goal.maxPerDayH,
                                      dayCap(dayCaps, day, o.goal.id));
                double take = Math.min(Math.min(capacity, cap),
                                       Math.max(daily, capacity * (o.goal.weight / openWeight) * 0.5));
                take = Math.round(Math.min(take, cap) * 4) / 4.0;
                if (take < o.minChunkH * 0.6) continue;
                alloc.get(day).put(o.goal.id, take);
                capacity -= take;
            }
            used[d] = days.get(d).capacityH() - capacity;
        }

        List<Shortfall> shortfalls = new ArrayList<>(), overdue = new ArrayList<>();
        for (Need c : closed) {
            if (c.overdue) overdue.add(new Shortfall(c.goal.name, c.needH, c.goal.deadline));
            else if (c.remaining > 1e-6) shortfalls.add(new Shortfall(c.goal.name, c.remaining, c.goal.deadline));
        }
        return new Plan(days, alloc, violations, shortfalls, overdue, used);
    }

    private static double dayCap(Map<Integer, Map<String, Double>> dayCaps, int day, String goalId) {
        Map<String, Double> forDay = dayCaps.get(day);
        if (forDay == null) return Double.MAX_VALUE;
        Double cap = forDay.get(goalId);
        return cap == null ? Double.MAX_VALUE : Math.max(0, cap);
    }

    private static double reachableCapacity(List<DayCapacity> days, Need c, int from) {
        double s = 0;
        for (int i = from; i <= c.deadlineIndex && i < days.size(); i++)
            if (c.allows(days.get(i))) s += days.get(i).capacityH();
        return s;
    }

    /** Capacity still reachable before the deadline, minus the work left. */
    private static double slack(List<DayCapacity> days, Need c, int from) {
        return reachableCapacity(days, c, from) - c.remaining;
    }
}
