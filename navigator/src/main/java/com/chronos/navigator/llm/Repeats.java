package com.chronos.navigator.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Whether a proposal says again what one waiting in the inbox already says (your report of 29 Oct
 * 2026: the same question filed several times, the same topics proposed again and again).
 *
 * Two change sets are the same when they make the same changes: each one's key and its values,
 * rounded. A newer one covers an older one when it changes everything the older changes, and
 * perhaps more: the older is then out of date, not a second thing to decide. Options offered
 * together in one answer are alternatives, never repeats of each other.
 */
public final class Repeats {
    private Repeats() {}

    /** What a change set changes: its keys (a parameter, a node, a Chronos activity or goal). */
    public static TreeSet<String> topics(List<Op> ops) {
        TreeSet<String> out = new TreeSet<>();
        for (Op op : ops) out.add(op.key());
        return out;
    }

    /** What a change set changes, and to what: equal for the same changes in any order. */
    public static String signature(List<Op> ops) {
        List<String> parts = new ArrayList<>();
        for (Op op : ops) {
            StringBuilder b = new StringBuilder(op.key());
            if (op.values() != null)
                for (Map.Entry<String, Double> e : new TreeMap<>(op.values()).entrySet())
                    b.append('|').append(e.getKey()).append('=').append(String.format(Locale.ROOT, "%.2f", e.getValue()));
            if (op.node() != null) {
                // What it changes to, without its words: the same pause with another note is the same pause.
                com.google.gson.JsonObject n = op.node().deepCopy();
                n.remove("note"); n.remove("why"); n.remove("title");
                TreeMap<String, String> fields = new TreeMap<>();
                for (Map.Entry<String, com.google.gson.JsonElement> e : n.entrySet()) fields.put(e.getKey(), e.getValue().toString());
                b.append('|').append(fields);
            }
            parts.add(b.toString());
        }
        parts.sort(null);
        return String.join("\n", parts);
    }

    /** The same changes. Nothing is the same as nothing: an empty set repeats nothing. */
    public static boolean same(List<Op> a, List<Op> b) {
        return !a.isEmpty() && !b.isEmpty() && signature(a).equals(signature(b));
    }

    /** Whether a newer change set changes everything an older one changes. */
    public static boolean covers(List<Op> newer, List<Op> older) {
        TreeSet<String> o = topics(older);
        return !o.isEmpty() && topics(newer).containsAll(o);
    }
}
