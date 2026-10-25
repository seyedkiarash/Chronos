package com.chronos.navigator.forecast;

import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Regime;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The forecast as the weekly allocation needs it: built once, then asked again
 * and again "with these hours next week, what are the chances?" — always with
 * the same seed, so two candidates differ only by their hours (common random
 * numbers), and only as far ahead as the deadlines that count.
 */
public final class Probe {
    private final Model m;
    private final int runs;
    private final long seed;
    private final int from;              // the week's first day, as an offset from today
    private final int[] targets;         // nodes whose deadline falls inside the horizon

    public Probe(Graph g, Inputs in, LocalDate today, LocalDate weekStart, int weeks, int runs, long seed) {
        this.m = new Model(g, in, today, weeks + 1 + (int) (ChronoUnit.DAYS.between(today, weekStart) / 7));
        this.runs = runs;
        this.seed = seed;
        this.from = (int) ChronoUnit.DAYS.between(today, weekStart);
        List<Integer> t = new ArrayList<>();
        for (int i = 0; i < m.n; i++)
            if (m.deadline[i] != Model.NONE && m.deadline[i] >= from && m.deadline[i] <= weeks * 7 && !m.doneAtStart[i]
                    && m.nodes[i].plannable())
                t.add(i);
        targets = t.stream().mapToInt(Integer::intValue).toArray();
    }

    public int size() { return m.n; }
    public Node node(int i) { return m.nodes[i]; }
    public String title(int i) { return m.nodes[i].title; }
    public int target(int k) { return targets[k]; }
    public double targetWeight(int k) { return m.weight[targets[k]]; }
    public double value(int i) { return m.value[i]; }

    /** Probability of each target by its deadline with these hours in the week. */
    public double[] score(double[] week) {
        Forecaster.Result r = Forecaster.run(m, runs, seed, week, from);
        double[] p = new double[targets.length];
        for (int k = 0; k < targets.length; k++) {
            Double v = r.nodes().get(m.nodes[targets[k]].id).pByDeadline();
            p[k] = v == null ? 0 : v;
        }
        return p;
    }

    private boolean depsDoneNow(int i) {
        for (int d : m.and[i]) if (!m.stream[d] && !m.doneAtStart[d]) return false;
        if (m.or[i].length == 0) return true;
        for (int[] grp : m.or[i]) {
            boolean all = true;
            for (int d : grp) if (!m.doneAtStart[d]) all = false;
            if (all) return true;
        }
        return false;
    }

    /** Whether the node can take hours this week at all: schedulable, started, with work left. */
    public boolean eligible(int i) {
        if (!m.schedulable(i) || m.doneAtStart[i] || !depsDoneNow(i)) return false;
        if (m.earliest[i] != Model.NONE && m.earliest[i] > from + 6) return false;
        return m.stream[i] || m.effort[i] != Model.Effort.NONE;
    }

    public boolean finite(int i) { return !m.stream[i] && m.effort[i] != Model.Effort.NONE; }

    private boolean pausedOn(int i, int day) {
        return m.pausedOn(i, day) || (m.earliest[i] != Model.NONE && day < m.earliest[i]);
    }

    public boolean pausedAllWeek(int i) {
        for (int d = from; d < from + 7; d++) if (!pausedOn(i, d)) return false;
        return true;
    }

    /** Most hours the node may take this week: its daily cap on the days it is not paused. */
    public double cap(int i) {
        int days = 0;
        for (int d = from; d < from + 7; d++) if (!pausedOn(i, d)) days++;
        return m.capPerDay[i] * days;
    }

    /** Hours an overlay's plan sets for the node this week. */
    public double overlayHours(int i) {
        double h = 0;
        for (int d = from; d < from + 7; d++) {
            Map<Integer, Double> plan = m.planned.get(d);
            if (plan != null && plan.get(i) != null) h += plan.get(i);
        }
        return h;
    }

    public String overlayName(int i) {
        for (int d = from; d < from + 7; d++)
            for (Regime o : m.graph.overlays(m.today.plusDays(d)))
                if (o.plannedHPerDay.containsKey(m.nodes[i].id)) return o.id;
        return "";
    }

    /** The node's weekly floor over the week's days that no overlay plans and do not pause it. */
    public double floorHours(int i) {
        double h = 0;
        for (int d = from; d < from + 7; d++) {
            if (pausedOn(i, d)) continue;
            Map<Integer, Double> plan = m.planned.get(d);
            if (plan != null) continue;          // an overlay's plan replaces the floors on its days
            h += m.floorOn(i, d);
        }
        return h;
    }

    /** Planned hours that would finish the node's work at the median, at your mean rate of doing what is planned. */
    public double workLeft(int i) {
        double bias = Math.exp(m.biasMu[i]);
        double work = switch (m.effort[i]) {
            case UNITS -> m.unitsLeft[i] * m.hoursPerUnit[i] * bias;
            case TOTAL -> Math.max(0, m.p50[i] * bias - m.hoursDone[i]);
            case SKILL -> Math.max(0, m.levelsLeft[i] * m.p50[i] * bias - m.hoursDone[i]);
            case NONE -> 0;
        };
        return work / Math.max(0.05, m.adhMean[i]);
    }

    /** Weeks from the week's start to the node's deadline (at least 1), or 0 when it has none. */
    public double weeksToDeadline(int i) {
        if (m.deadline[i] == Model.NONE) return 0;
        return Math.max(1, (m.deadline[i] - from + 1) / 7.0);
    }

    /** Goal hours to share out: the expected hours of the week's days, times your mean adherence. */
    public double expectedCapacity() {
        double h = 0;
        for (int d = from; d < from + 7 && d < m.horizonDays; d++) {
            int reg = m.regimeOfDay[d];
            if (reg < 0) continue;
            double[] a = m.dayTypeAlpha.get(reg);
            double s = a[0] + a[1] + a[2];
            for (int t = 0; t < 3; t++) h += a[t] / s * Math.max(0, m.capMean[t]);
        }
        return h * m.adhPooled;
    }
}
