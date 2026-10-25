package com.chronos.navigator.model;

import com.chronos.navigator.model.Kinds.DayType;
import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Kinds.Source;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Answers given in the app to what the file left out, kept as parameters so
 * they carry a source like every other number and survive a re-import:
 *
 *   node.ID.effort   {p50, p90}                total hours of a task or skill
 *   node.ID.floor    {value}                   weekly floor of a stream
 *   node.ID.success  {a, b}                    Beta prior of a gate or milestone
 *   regime.ID.mix    {IDEAL, BUSY, STRESS, strength}  shares of day types, and how many days they are worth
 *   regime.ID.span   {from, to}                epoch days; a regime added in the app
 *
 * and decisions taken later, in the app (an approved proposal, a better answer):
 *
 *   node.ID.min_weekly {value, from, until}    at least this many hours a week between two dates
 *   node.ID.pause      {from, until}           no hours between two dates
 *   node.ID.deadline   {value, was}            a new deadline (epoch day); was = the file's, -1 if none
 *   node.ID.units      {total, was_total}      a new number of units (taken from Chronos)
 *   node.ID.priority   {value}                 2 critical, 1 important, 0 optional (see {@link Priority})
 *
 * An answer fills a gap; it never overrides what the file states. When a newer
 * file fills the same gap, the answer is dropped at import ({@link #superseded}).
 * The exception is a value marked {@code override = 1}: a later decision that
 * replaces what the file said. It records what it replaced ({@code was_…}), and
 * is dropped once a newer file changes that value itself — the file is then
 * the later decision again.
 */
public final class Overrides {
    private Overrides() {}

    public static final String NODE = "node.", REGIME = "regime.";

    public static String nodeKey(String id, String what) { return NODE + id + "." + what; }
    public static String regimeKey(String id, String what) { return REGIME + id + "." + what; }

    public static void apply(Graph g) {
        for (Param p : g.params.values()) {
            String[] k = split(p.key);
            if (k == null) continue;
            if (k[0].equals("node")) {
                Node n = g.node(k[1]);
                if (n == null) continue;
                boolean over = p.get("override") != null;
                switch (k[2]) {
                    case "effort" -> {
                        if (over && n.perPointP50 != null) {
                            // A skill counts hours per level point: the new total is spread over its levels.
                            double levels = n.levelFrom != null && n.levelTo != null ? Math.max(1, n.levelTo - n.levelFrom) : 1;
                            n.perPointP50 = p.get("p50") / levels; n.perPointP90 = p.get("p90") / levels;
                        } else if (over || (n.effortP50 == null && n.perPointP50 == null)) {
                            n.effortP50 = p.get("p50"); n.effortP90 = p.get("p90");
                        }
                    }
                    case "floor" -> { if (over || n.weeklyFloorH == null) n.weeklyFloorH = p.get("value"); }
                    case "success" -> {
                        if (over || n.successA == null) { n.successA = p.get("a"); n.successB = p.get("b"); }
                    }
                    case "deadline" -> {
                        if (p.get("value") != null) n.deadline = LocalDate.ofEpochDay(p.get("value").longValue());
                    }
                    case "pause" -> {
                        if (p.get("from") != null && p.get("until") != null)
                            n.pausedSpans.add(new LocalDate[]{LocalDate.ofEpochDay(p.get("from").longValue()),
                                LocalDate.ofEpochDay(p.get("until").longValue())});
                    }
                    case "units" -> { if (p.get("total") != null) n.unitsTotal = p.get("total"); }
                    case "priority" -> { if (p.get("value") != null) n.priority = p.get("value").intValue(); }
                    case "min_weekly" -> {
                        if (p.get("value") != null && p.get("from") != null && p.get("until") != null) {
                            n.minWeeklyH = p.get("value");
                            n.minWeeklyFrom = LocalDate.ofEpochDay(p.get("from").longValue());
                            n.minWeeklyUntil = LocalDate.ofEpochDay(p.get("until").longValue());
                        }
                    }
                    default -> { }
                }
            } else if (k[2].equals("span")) {
                if (g.regime(k[1]) != null || p.get("from") == null || p.get("to") == null) continue;
                Regime r = new Regime();
                r.id = k[1];
                r.from = LocalDate.ofEpochDay(p.get("from").longValue());
                r.to = LocalDate.ofEpochDay(p.get("to").longValue());
                r.note = "Added in the app";
                g.regimes.add(r);
            }
        }
        // Mixes after spans, so a regime added in the app gets its mix too.
        for (Param p : g.params.values()) {
            String[] k = split(p.key);
            if (k == null || !k[0].equals("regime") || !k[2].equals("mix")) continue;
            Regime r = g.regime(k[1]);
            boolean over = p.get("override") != null;
            if (r == null || (r.mix != null && !over)) continue;
            r.mix = new EnumMap<>(DayType.class);
            for (DayType t : DayType.values()) if (p.get(t.name()) != null) r.mix.put(t, p.get(t.name()));
            if (over || r.strength == null) r.strength = p.get("strength");
        }
        g.regimes.sort((a, b) -> a.from == null || b.from == null ? 0 : a.from.compareTo(b.from));
    }

    /** Answers the new file now gives itself, or that point at what it no longer has. */
    public static List<String> superseded(Graph fresh, Iterable<Param> stored) {
        List<String> out = new ArrayList<>();
        for (Param p : stored) {
            String[] k = split(p.key);
            if (k == null) continue;
            if (k[0].equals("node")) {
                Node n = fresh.node(k[1]);
                if (n != null && changedSince(n, k[2], p)) { out.add(p.key); continue; }
                if (n != null && (p.get("override") != null || DECISIONS.contains(k[2]))) continue;
                boolean gone = n == null
                    || (k[2].equals("effort") && (n.effortP50 != null || n.perPointP50 != null))
                    || (k[2].equals("floor") && n.weeklyFloorH != null)
                    || (k[2].equals("success") && n.successA != null);
                if (gone) out.add(p.key);
            } else {
                Regime r = fresh.regime(k[1]);
                if (k[2].equals("span") && r != null) out.add(p.key);
                if (k[2].equals("mix") && r != null && r.mix != null) {
                    if (p.get("override") == null) { out.add(p.key); continue; }
                    // A later answer over the file's mix: kept until the file changes that mix.
                    for (DayType t : DayType.values()) {
                        Double was = p.get("was_" + t.name());
                        if (was != null && (was < 0 || Math.abs(r.mix.getOrDefault(t, 0.0) - was) > 1e-9)) { out.add(p.key); break; }
                    }
                }
            }
        }
        return out;
    }

    /** Keys that are decisions rather than answers to a gap: kept over a re-import while their node is. */
    static final java.util.Set<String> DECISIONS = java.util.Set.of("deadline", "pause", "min_weekly", "units", "priority");

    /**
     * Whether the file changed, since a decision was taken, the very value the
     * decision replaced: then the file is the later word and the decision goes.
     */
    static boolean changedSince(Node n, String what, Param p) {
        switch (what) {
            case "deadline" -> {
                Double was = p.get("was");
                long now = n.deadline == null ? -1 : n.deadline.toEpochDay();
                return was != null && was.longValue() != now;
            }
            case "effort" -> {
                if (p.get("override") == null) return false;
                return differs(p.get("was_p50"), n.perPointP50 != null ? null : n.effortP50)
                    || differs(p.get("was_p90"), n.perPointP50 != null ? null : n.effortP90);
            }
            case "success" -> {
                if (p.get("override") == null) return false;
                return differs(p.get("was_a"), n.successA) || differs(p.get("was_b"), n.successB);
            }
            case "floor" -> {
                return p.get("override") != null && differs(p.get("was_value"), n.weeklyFloorH);
            }
            case "units" -> {
                return differs(p.get("was_total"), n.unitsTotal);
            }
            default -> { return false; }
        }
    }

    private static boolean differs(Double was, Double now) {
        if (was == null) return false;           // nothing was recorded: keep the decision
        if (was < 0) return now != null;         // -1: the file had none then
        return now == null || Math.abs(now - was) > 1e-9;
    }

    /**
     * The values a later decision records about what it replaces, so a newer
     * file that changes that value can be recognised ({@link #changedSince}).
     */
    public static java.util.Map<String, Double> was(Node n, String what) {
        java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
        switch (what) {
            case "effort" -> {
                boolean perPoint = n.perPointP50 != null;
                out.put("was_p50", perPoint || n.effortP50 == null ? -1 : n.effortP50);
                out.put("was_p90", perPoint || n.effortP90 == null ? -1 : n.effortP90);
            }
            case "success" -> {
                out.put("was_a", n.successA == null ? -1 : n.successA);
                out.put("was_b", n.successB == null ? -1 : n.successB);
            }
            case "floor" -> out.put("was_value", n.weeklyFloorH == null ? -1 : n.weeklyFloorH);
            case "deadline" -> out.put("was", n.deadline == null ? -1.0 : (double) n.deadline.toEpochDay());
            default -> { }
        }
        return out;
    }

    /** What a later answer about a regime's mix replaces: the file's shares, -1 when it had none. */
    public static java.util.Map<String, Double> was(Regime r) {
        java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
        for (DayType t : DayType.values())
            out.put("was_" + t.name(), r.mix == null || r.mix.get(t) == null ? -1 : r.mix.get(t));
        return out;
    }

    public static Param answer(String key, Dist dist, String note, long now, Object... nameValuePairs) {
        Param p = new Param(key, dist, Source.USER, note, nameValuePairs);
        p.updatedAt = now;
        return p;
    }

    /** {"node"|"regime", id, what} or null for an ordinary parameter. Ids may contain dots. */
    static String[] split(String key) {
        String kind = key.startsWith(NODE) ? "node" : key.startsWith(REGIME) ? "regime" : null;
        if (kind == null) return null;
        String rest = key.substring(kind.length() + 1);
        int dot = rest.lastIndexOf('.');
        if (dot <= 0) return null;
        return new String[]{kind, rest.substring(0, dot), rest.substring(dot + 1)};
    }
}
