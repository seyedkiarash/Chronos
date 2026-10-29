package com.chronos.engine;

import com.chronos.model.LogEntry;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Where a day's saved entries belong among its blocks now (your report of 28 Oct 2026). An entry is
 * saved under the block it was written for, by that block's start: "session|day|activity|slot".
 * When the day is arranged again after that and the block moves, the entry stays saved but is left
 * under a block that is no longer there: out of sight in the log, so it gets filled in a second time
 * and counted twice. Here each entry finds its block again — the same block where it still stands,
 * else the nearest block of the same activity (trip, class) that has no entry of its own.
 */
public final class LogHomes {
    private LogHomes() {}

    /** One block of the day: what it is ("act|ID", "trip|ID", "class|ID") and where it starts. */
    public record Place(String key, int start) {}

    /**
     * @return for each block, as {@code key + "|" + start}, the slot its entries are saved under —
     *         its own start unless an entry of a moved block was given to it
     */
    public static Map<String, Integer> of(LocalDate day, List<Place> blocks, List<LogEntry> logs) {
        Map<String, TreeSet<Integer>> saved = new HashMap<>();
        String d = day.toString();
        for (LogEntry l : logs) {
            if (l.id == null || !d.equals(l.date == null ? null : l.date.toString())) continue;
            String[] p = l.id.split("\\|");
            if (p.length < 4 || !p[1].equals(d)) continue;
            String kind = switch (p[0]) {
                case "session", "instead" -> "act";
                case "trip", "during" -> "trip";
                case "class" -> "class";
                default -> null;
            };
            if (kind == null) continue;
            try { saved.computeIfAbsent(kind + "|" + p[2], k -> new TreeSet<>()).add(Integer.parseInt(p[3])); }
            catch (NumberFormatException ignored) { }
        }
        Map<String, List<Integer>> starts = new LinkedHashMap<>();
        for (Place b : blocks) starts.computeIfAbsent(b.key(), k -> new ArrayList<>()).add(b.start());

        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, List<Integer>> e : starts.entrySet()) {
            TreeSet<Integer> left = new TreeSet<>(saved.getOrDefault(e.getKey(), new TreeSet<>()));
            List<Integer> open = new ArrayList<>();
            for (int s : e.getValue()) {
                out.put(e.getKey() + "|" + s, s);
                if (!left.remove(s)) open.add(s);
            }
            // The entries of blocks that are gone, each to the nearest block still without one.
            while (!left.isEmpty() && !open.isEmpty()) {
                int bestLog = -1, bestBlock = -1, gap = Integer.MAX_VALUE;
                for (int logged : left)
                    for (int s : open)
                        if (Math.abs(logged - s) < gap) { gap = Math.abs(logged - s); bestLog = logged; bestBlock = s; }
                out.put(e.getKey() + "|" + bestBlock, bestLog);
                left.remove(bestLog);
                open.remove(Integer.valueOf(bestBlock));
            }
        }
        return out;
    }

    /**
     * The entries of a day no block shows, even after {@link #of}: an activity no longer planned that
     * day at all. Listed in the log so they can be read, corrected or deleted, never left out of sight.
     */
    public static List<LogEntry> unseen(LocalDate day, List<Place> blocks, List<LogEntry> logs) {
        Map<String, Integer> homes = of(day, blocks, logs);
        java.util.Set<String> shown = new java.util.HashSet<>();
        for (Map.Entry<String, Integer> h : homes.entrySet()) {
            String key = h.getKey().substring(0, h.getKey().lastIndexOf('|'));
            shown.add(key + "|" + h.getValue());
        }
        List<LogEntry> out = new ArrayList<>();
        String d = day.toString();
        for (LogEntry l : logs) {
            if (l.id == null || l.date == null || !d.equals(l.date.toString())) continue;
            String[] p = l.id.split("\\|");
            if (p.length < 4) continue;
            String kind = switch (p[0]) {
                case "session", "instead" -> "act";
                case "trip", "during" -> "trip";
                case "class" -> "class";
                default -> null;
            };
            if (kind != null && !shown.contains(kind + "|" + p[2] + "|" + p[3])) out.add(l);
        }
        return out;
    }
}
