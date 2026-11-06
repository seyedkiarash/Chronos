package com.chronos.navigator.forecast;

import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.Owner;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Priority;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether what you have set out to do fits the time you have (your point of 7 Nov 2026): priorities
 * decide where work goes, but when the work is too dense for the hours, something has to give, and
 * that should be your choice, offered knowingly. Every deadline counts, however far: each is checked
 * against the expected goal hours up to it, earliest first, after the standing hours. Where the work
 * does not fit, the families of work with the least importance are named, with the date each would
 * really reach. Nothing here changes anything.
 */
public final class Density {
    private Density() {}

    /**
     * Something with hours and a date.
     *
     * @param id          a node's id, or a Chronos goal's name
     * @param family      what it moves with: work done through the same activities
     * @param importance  0 optional, 1 important, 2 critical
     * @param fixed       its date cannot be yours to move or give up
     * @param chronos     a Chronos goal rather than a node of the graph
     */
    public record Load(String id, String title, String family, double hours, LocalDate due, int importance,
                       boolean fixed, boolean chronos) {}

    /** Where the work is furthest beyond the hours: everything due by [until] needs [need] hours, and [have] are there. */
    public record Crunch(LocalDate until, double need, double have) {}

    /**
     * Work that could give way.
     *
     * @param members    its loads, earliest first
     * @param reachable  the day the hours would really cover it if everything before it is kept
     */
    public record Giving(String family, List<Load> members, double hours, int importance, LocalDate reachable) {
        public LocalDate lastDue() { return members.get(members.size() - 1).due(); }
    }

    public record Report(Crunch crunch, List<Giving> giving, double weeklyHave, double weeklyStanding) {
        public boolean fits() { return crunch == null; }
    }

    /** How far past the hours counts as too dense; small gaps are what the estimates cannot tell apart. */
    private static final double TOO_DENSE = 1.1, AT_LEAST_H = 5;
    private static final int MOST_NEAR = 2, MOST_FAR = 3, NEAR_DAYS = 14;

    public static Report of(Graph g, Inputs in, LocalDate today, List<Load> extra) {
        Model m = new Model(g, in, today, 0);
        List<Load> loads = new ArrayList<>(extra);
        for (int i = 0; i < m.n; i++) {
            Node n = m.nodes[i];
            if (m.stream[i] || !m.schedulable(i) || m.doneAtStart[i] || n.deadline == null || n.deadline.isBefore(today)) continue;
            double h = medianWork(m, i);
            if (h <= 0.05) continue;
            List<String> acts = in.activitiesOf(n.id);
            String family = acts.isEmpty() ? n.id : acts.stream().sorted().findFirst().get();
            boolean fixed = n.hard || n.hardDeadline || n.owner != Owner.USER;
            loads.add(new Load(n.id, n.shortName(), family, h, n.deadline, Priority.of(n), fixed, false));
        }
        loads.sort(Comparator.comparing(Load::due));

        // Expected hours by the end of each day, after what stands every week whatever else happens.
        double[] have = new double[m.horizonDays];
        double standing = 0;
        for (int i = 0; i < m.n; i++)
            if (m.schedulable(i) && !m.doneAtStart[i] && (m.stream[i] || m.deadline[i] == Model.NONE)) standing += m.floorPerDay[i];
        double sum = 0, first = 0;
        for (int d = 0; d < m.horizonDays; d++) {
            double cap = 0;
            int reg = m.regimeOfDay[d];
            if (reg >= 0) {
                double[] a = m.dayTypeAlpha.get(reg);
                double s = a[0] + a[1] + a[2];
                for (int t = 0; t < 3; t++) cap += a[t] / s * Math.max(0, m.capMean[t]);
            }
            sum += Math.max(0, cap - standing);
            have[d] = sum;
            if (d == 6) first = sum;
        }

        Crunch worst = null;
        double need = 0, worstRatio = TOO_DENSE;
        List<Load> over = new ArrayList<>();
        int upTo = 0;
        for (int k = 0; k < loads.size(); k++) {
            Load l = loads.get(k);
            need += l.hours();
            double h = have[Math.min(m.horizonDays - 1, Math.max(0, m.dayOffset(l.due())))];
            if (need - h < AT_LEAST_H) continue;
            upTo = k;
            double ratio = need / Math.max(1, h);
            if (ratio > worstRatio) { worstRatio = ratio; worst = new Crunch(l.due(), need, h); }
        }
        if (worst == null) return new Report(null, List.of(), first, standing * 7);
        over.addAll(loads.subList(0, upTo + 1));

        // Whatever is not fixed, gathered by family, the least important and the largest first.
        Map<String, List<Load>> families = new LinkedHashMap<>();
        for (Load l : loads) families.computeIfAbsent(l.family(), x -> new ArrayList<>()).add(l);
        List<Giving> giving = new ArrayList<>();
        for (Map.Entry<String, List<Load>> f : families.entrySet()) {
            List<Load> members = f.getValue();
            if (members.stream().noneMatch(over::contains) || members.stream().allMatch(Load::fixed)) continue;
            List<Load> open = members.stream().filter(l -> !l.fixed()).toList();
            double hours = open.stream().mapToDouble(Load::hours).sum();
            if (hours < 2) continue;
            int importance = open.stream().mapToInt(Load::importance).max().orElse(0);
            giving.add(new Giving(f.getKey(), open, hours, importance, reachable(have, loads, open, today, m.horizonDays)));
        }
        giving.sort(Comparator.comparingInt(Giving::importance).thenComparing(Comparator.comparingDouble(Giving::hours).reversed()));
        // What could lighten the coming days, and what could lighten the months ahead: neither crowds out the other.
        LocalDate near = today.plusDays(NEAR_DAYS);
        List<Giving> offered = new ArrayList<>();
        offered.addAll(giving.stream().filter(x -> !x.members().get(0).due().isAfter(near)).limit(MOST_NEAR).toList());
        offered.addAll(giving.stream().filter(x -> x.members().get(0).due().isAfter(near)).limit(MOST_FAR).toList());
        return new Report(worst, offered, first, standing * 7);
    }

    /** The day the hours cover this work and everything due before its end, with the rest kept where it is. */
    private static LocalDate reachable(double[] have, List<Load> loads, List<Load> mine, LocalDate today, int days) {
        LocalDate last = mine.get(mine.size() - 1).due();
        double need = 0;
        for (Load l : loads) if (!l.due().isAfter(last)) need += l.hours();
        for (int d = 0; d < days; d++) if (have[d] >= need) return today.plusDays(d);
        return null;
    }

    private static double medianWork(Model m, int i) {
        double bias = Math.exp(m.biasMu[i]);
        return switch (m.effort[i]) {
            case UNITS -> m.unitsLeft[i] * m.hoursPerUnit[i] * bias;
            case TOTAL -> Math.max(0, m.p50[i] * bias - m.hoursDone[i]);
            case SKILL -> Math.max(0, m.levelsLeft[i] * m.p50[i] * bias - m.hoursDone[i]);
            case NONE -> 0;
        };
    }
}
