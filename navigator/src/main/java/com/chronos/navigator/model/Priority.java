package com.chronos.navigator.model;

import com.chronos.navigator.model.Kinds.NodeType;

import java.util.Locale;

/**
 * How much a deadline matters, which decides how far a change may lower its
 * odds (see {@link com.chronos.navigator.forecast.Guard}). You set it on the
 * node card; until you do, a hard deadline counts as critical, a milestone or
 * the end of a path with a deadline as important, and everything else as
 * optional.
 */
public final class Priority {
    private Priority() {}

    public static final int OPTIONAL = 0, IMPORTANT = 1, CRITICAL = 2;

    public static int of(Node n) {
        if (n.priority != null) return Math.max(OPTIONAL, Math.min(CRITICAL, n.priority));
        if (n.hard || n.hardDeadline) return CRITICAL;
        if (n.deadline != null && (n.type == NodeType.MILESTONE || n.type == NodeType.TERMINAL)) return IMPORTANT;
        return OPTIONAL;
    }

    /** Whether you chose it, rather than the default. */
    public static boolean chosen(Node n) { return n.priority != null; }

    public static String name(int p) {
        return switch (p) { case CRITICAL -> "critical"; case IMPORTANT -> "important"; default -> "optional"; };
    }

    /** "critical" / "important" / "optional" (as a file or the model writes it), or null. */
    public static Integer parse(String s) {
        if (s == null) return null;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "critical", "2" -> CRITICAL;
            case "important", "1" -> IMPORTANT;
            case "optional", "0" -> OPTIONAL;
            default -> null;
        };
    }

    /** The lowest odds (low end of the 80% band) a change may leave it at; 0 when it has no floor. */
    public static double floor(Graph g, int p) {
        String key = p == CRITICAL ? "guard.floor.critical" : p == IMPORTANT ? "guard.floor.important" : null;
        if (key == null) return 0;
        Param v = g.params.get(key);
        return v == null || v.get("value") == null ? (p == CRITICAL ? 0.75 : 0.50) : v.get("value");
    }
}
