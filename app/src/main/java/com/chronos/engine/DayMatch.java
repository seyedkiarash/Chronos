package com.chronos.engine;

import com.chronos.model.Activity;
import com.chronos.model.Block;
import com.chronos.model.Config;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.LogEntry;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How closely a lived day followed its plan, read from what was logged against
 * it. Four parts, each 0..1 and each null when the log says nothing about it:
 *
 *  - duration: for every reported block, minutes done against minutes planned
 *    (a skipped block scores 0);
 *  - start:    minutes between the planned and the actual start, an hour off
 *    or more scoring 0;
 *  - energy:   the capacity the plan expected in each rated hour against the
 *    rating (rating / 5, the scale the body-clock fit uses);
 *  - fatigue:  the mental and the physical fatigue the plan expected at the
 *    end of each reported session against how tired you said you were (/ 5);
 *    an older single "drained" rating is set against the two mixed by how
 *    physical the activity is.
 *
 * The overall score is the mean of the parts that exist. Blocks nobody
 * reported are counted, not guessed: they lower nothing, and the screen says
 * how many there were.
 */
public final class DayMatch {
    private DayMatch() {}

    /** Being an hour off the planned start scores nothing; on time scores everything. */
    public static final int START_TOLERANCE_MIN = 60;

    /** One planned block and what the log says happened to it. */
    public record Row(String name, int plannedStart, int plannedMin,
                      String actualAt, Integer actualMin, boolean skipped,
                      Double duration, Double start) {}

    /** One rated hour: what the plan expected and what you said, both 0..1. */
    public record Hour(int hour, double expected, double reported) {}

    public record Result(Double duration, Double start, Double energy, Double fatigue, Double overall,
                         List<Row> rows, List<Hour> hours, int reported, int skipped, int unreported) {

        public boolean empty() { return overall == null; }
    }

    /**
     * @param blocks   the day's plan as it was kept (placed blocks; gaps are ignored)
     * @param capacity the plan's capacity trace, one value per slot (may be null)
     * @param fatigue  the plan's mental fatigue trace, one value per slot (may be null)
     * @param physical the plan's physical fatigue trace (may be null)
     */
    public static Result of(Config cfg, LocalDate day, List<Block> blocks, double[] capacity,
                            double[] fatigue, double[] physical, List<LogEntry> logs) {
        Map<String, LogEntry> byId = new HashMap<>();
        for (LogEntry l : logs) if (day.equals(l.date) && l.id != null) byId.put(l.id, l);

        List<Row> rows = new ArrayList<>();
        List<Double> durations = new ArrayList<>(), starts = new ArrayList<>(), drains = new ArrayList<>();
        int reported = 0, skipped = 0, unreported = 0;

        for (Block b : blocks) {
            if (b.gap || b.fixed || b.start < 0) continue;
            Activity a = activityOf(cfg, b);
            if (a == null) continue;
            int plannedMin = b.dur * Slots.SLOT_MIN;
            LogEntry l = byId.get("session|" + day + "|" + a.id + "|" + b.start);
            if (l == null) {
                unreported++;
                rows.add(new Row(b.name, b.start, plannedMin, null, null, false, null, null));
                continue;
            }
            reported++;
            boolean skip = l.at == null;
            Double dur, st = null;
            if (skip) {
                skipped++;
                dur = 0.0;
            } else {
                int actual = l.actual == null ? plannedMin : l.actual;
                dur = plannedMin <= 0 ? null : Slots.clamp(1 - Math.abs(actual - plannedMin) / (double) plannedMin, 0, 1);
                int off = Math.abs(Slots.parse(l.at) - b.start) * Slots.SLOT_MIN;
                off = Math.min(off, 1440 - off);           // across midnight, the short way round
                st = Slots.clamp(1 - off / (double) START_TOLERANCE_MIN, 0, 1);
                starts.add(st);
                int end = Math.min(Slots.PER_DAY - 1, b.start + b.dur - 1);
                if (fatigue != null && l.mentalAfter != null)
                    drains.add(1 - Math.abs(Slots.clamp(fatigue[end], 0, 1) - Slots.clamp(l.mentalAfter / 5.0, 0, 1)));
                if (physical != null && l.physicalAfter != null)
                    drains.add(1 - Math.abs(Slots.clamp(physical[end], 0, 1) - Slots.clamp(l.physicalAfter / 5.0, 0, 1)));
                if (fatigue != null && l.mentalAfter == null && l.physicalAfter == null && l.fatigueAfter != null) {
                    double share = FatigueSplit.physicalShare(a.load, a.physLoad);
                    double expected = (1 - share) * fatigue[end] + share * (physical == null ? 0 : physical[end]);
                    drains.add(1 - Math.abs(Slots.clamp(expected, 0, 1) - Slots.clamp(l.fatigueAfter / 5.0, 0, 1)));
                }
            }
            if (dur != null) durations.add(dur);
            rows.add(new Row(b.name, b.start, plannedMin, l.at, skip ? null : l.actual, skip, dur, st));
        }

        List<Hour> hours = new ArrayList<>();
        List<Double> energies = new ArrayList<>();
        if (capacity != null) {
            int perHour = 60 / Slots.SLOT_MIN;
            for (LogEntry l : byId.values()) {
                if (l.kind != LogKind.CHECKIN || l.energy == null || !l.id.startsWith("energy|")) continue;
                int hour;
                try { hour = Integer.parseInt(l.id.substring(l.id.lastIndexOf('|') + 1)); }
                catch (NumberFormatException e) { continue; }
                double sum = 0; int n = 0;
                for (int t = hour * perHour; t < (hour + 1) * perHour && t < capacity.length; t++)
                    if (capacity[t] > 0) { sum += capacity[t]; n++; }
                if (n == 0) continue;
                double expected = sum / n, said = Slots.clamp(l.energy / 5.0, 0, 1);
                hours.add(new Hour(hour, expected, said));
                energies.add(1 - Math.abs(expected - said));
            }
            hours.sort((x, y) -> Integer.compare(x.hour(), y.hour()));
        }

        Double d = mean(durations), s = mean(starts), e = mean(energies), f = mean(drains);
        List<Double> parts = new ArrayList<>();
        for (Double p : new Double[]{d, s, e, f}) if (p != null) parts.add(p);
        rows.sort((x, y) -> Integer.compare(x.plannedStart(), y.plannedStart()));
        return new Result(d, s, e, f, mean(parts), rows, hours, reported, skipped, unreported);
    }

    private static Double mean(List<Double> xs) {
        if (xs.isEmpty()) return null;
        double s = 0;
        for (double x : xs) s += x;
        return s / xs.size();
    }

    /** The activity a stored block came from: blocks carry the name and goal, not the object. */
    static Activity activityOf(Config cfg, Block b) {
        for (Activity a : cfg.activities)
            if (a.name != null && a.name.equals(b.name) && (b.goalId == null || b.goalId.equals(a.goalId))) return a;
        return null;
    }
}
