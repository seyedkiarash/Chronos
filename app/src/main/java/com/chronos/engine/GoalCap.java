package com.chronos.engine;

import com.chronos.model.Activity;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.LogEntry;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A plan no bigger than what you have been doing (your request of 8 Nov 2026). Planning fifty hours
 * a week against twenty-seven worked measures the plan, not you: from the last four weeks of log,
 * a day is given no more goal work than your good days held — the top fifth of logged days — and
 * a tenth more, so there is room to grow and the cap rises as you do.
 */
public final class GoalCap {
    private GoalCap() {}

    /** Days looked back on. */
    public static final int WINDOW = 28;
    /** Logged days needed before the log says anything. */
    public static final int LEAST = 7;
    /** Which of your days counts as a good one: the 80th percentile. */
    public static final double GOOD = 0.8;
    /** Room to grow above it. */
    public static final double STRETCH = 1.1;

    /**
     * @param capH     the most goal work planned on a day
     * @param goodDayH what your good days held
     * @param days     the logged days it was learned from
     */
    public record Cap(double capH, double goodDayH, int days) {}

    /**
     * The cap from the log before {@code today}, or null with too few logged days. A day counts as
     * logged when it has at least one session entry; {@code skip} (unusual and rest days) are left out.
     */
    public static Cap of(Config cfg, List<LogEntry> logs, LocalDate today, Set<LocalDate> skip) {
        Set<String> goalWork = new HashSet<>();
        for (Activity a : cfg.activities) if (a.kind == ActivityKind.GOAL && !a.leisure) goalWork.add(a.id);
        LocalDate from = today.minusDays(WINDOW);
        Map<LocalDate, Double> worked = new TreeMap<>();
        for (LogEntry l : logs) {
            if (l.kind != LogKind.SESSION || l.date == null || l.date.isBefore(from) || !l.date.isBefore(today)) continue;
            if (skip.contains(l.date)) continue;
            double m = worked.getOrDefault(l.date, 0.0);
            // A skipped block has no time of day; only what was worked counts.
            if (l.actId != null && goalWork.contains(l.actId) && l.at != null && l.actual != null && l.actual > 0) m += l.actual;
            worked.put(l.date, m);
        }
        if (worked.size() < LEAST) return null;
        List<Double> hours = new ArrayList<>();
        for (double m : worked.values()) hours.add(m / 60.0);
        Collections.sort(hours);
        double good = hours.get(Math.max(0, (int) Math.ceil(GOOD * hours.size()) - 1));
        double cap = Math.max(1.0, Math.ceil(good * STRETCH * 4) / 4.0);
        return new Cap(cap, good, hours.size());
    }
}
