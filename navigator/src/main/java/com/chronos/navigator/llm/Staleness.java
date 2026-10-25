package com.chronos.navigator.llm;

import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Requirements;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Whether a proposal waiting in the inbox still fits the situation as it is
 * now (your request of 20 Oct 2026: a re-check you can run at any time). The
 * app decides this itself, without the model: a change about a node that is
 * gone or finished, a period that is over, a date already behind you, or a
 * Chronos activity or goal that no longer exists no longer applies. A proposal
 * none of whose changes still apply is outdated; one with only some of them
 * gone, or one simply old, is worth showing the model again.
 */
public final class Staleness {
    private Staleness() {}

    /**
     * @param outdated why the whole proposal no longer applies, or null while some of it still does
     * @param gone     what in it no longer applies, one line each
     * @param live     how many of its changes still apply
     */
    public record Verdict(String outdated, List<String> gone, int live) {
        public boolean partly() { return outdated == null && !gone.isEmpty(); }
    }

    /**
     * @param subject    the node ids the proposal was about, comma separated (null for a crew package)
     * @param activities the names of Chronos's activities as they are now
     * @param goals      the names of Chronos's goals (and the targets under them) as they are now
     */
    public static Verdict check(Graph g, LocalDate today, List<Op> ops, String subject,
                                Set<String> activities, Set<String> goals) {
        Set<String> acts = normAll(activities), gls = normAll(goals);
        List<String> gone = new ArrayList<>();
        int live = 0;
        for (Op op : ops) {
            String why = goneWhy(g, today, op, acts, gls);
            if (why == null) live++;
            else gone.add((op.note() == null || op.note().isBlank() ? op.key() : op.note()) + ": " + why);
        }

        // What it was about, as a whole.
        Set<String> about = new LinkedHashSet<>();
        if (subject != null) for (String s : subject.split(",")) if (!s.isBlank()) about.add(s.trim());
        List<Node> nodes = new ArrayList<>();
        boolean namedNodes = false;
        for (String id : about) {
            if (g.node(id) != null) nodes.add(g.node(id));
            else if (looksLikeNodeId(id)) namedNodes = true;
        }
        if (nodes.isEmpty() && namedNodes && live == 0)
            return new Verdict("What it was about is no longer in the goal graph", gone, live);
        if (!nodes.isEmpty() && nodes.stream().allMatch(Requirements::isDone))
            return new Verdict("What it was about is finished", gone, 0);
        boolean movesDeadline = ops.stream().anyMatch(o -> o.key() != null && o.key().endsWith(".deadline")
            || o.isChronos() && "edit_goal".equals(type(o)) && str(o.node(), "deadline") != null);
        if (!nodes.isEmpty() && !movesDeadline
                && nodes.stream().allMatch(n -> n.deadline != null && n.deadline.isBefore(today)))
            return new Verdict("Its deadline has passed", gone, live);
        if (!ops.isEmpty() && live == 0)
            return new Verdict(gone.size() == 1 ? capital(gone.get(0).substring(gone.get(0).indexOf(": ") + 2))
                : "None of its changes applies any more", gone, 0);
        return new Verdict(null, gone, live);
    }

    /** Why one change no longer applies, or null while it does. */
    static String goneWhy(Graph g, LocalDate today, Op op, Set<String> acts, Set<String> gls) {
        if (op.isChronos()) return chronosGone(today, op.node(), acts, gls);
        String key = op.key() == null ? "" : op.key();
        Node n = null;
        if (op.isStructure()) {
            JsonObject raw = op.node();
            String id = Op.NODE_EDIT.equals(op.kind()) ? str(raw, "id") : str(raw, "parent");
            if (id == null) id = nodeOf(key);
            if (id != null) {
                n = g.node(id);
                if (n == null && Op.NODE_EDIT.equals(op.kind())) return "that node is no longer in the graph";
            }
        } else {
            String id = nodeOf(key);
            if (id != null) {
                n = g.node(id);
                if (n == null) return "that node is no longer in the graph";
            }
        }
        if (n != null && Requirements.isDone(n)) return "it is finished";
        Map<String, Double> v = op.values() == null ? Map.of() : op.values();
        if (key.endsWith(".pause") || key.endsWith(".min_weekly")) {
            Double until = v.get("until");
            if (until != null && LocalDate.ofEpochDay(until.longValue()).isBefore(today)) return "the period it was for is over";
        }
        if (key.endsWith(".deadline")) {
            Double d = v.get("value");
            if (d != null && d >= 0 && LocalDate.ofEpochDay(d.longValue()).isBefore(today)) return "the new date is already behind you";
        }
        if (key.startsWith("regime.") && key.endsWith(".span")) {
            Double to = v.get("to");
            if (to != null && LocalDate.ofEpochDay(to.longValue()).isBefore(today)) return "that period is over";
        }
        if (n != null && n.type != null && n.decideBy != null && n.decideBy.isBefore(today)
                && n.type.name().equals("DECISION")) return "the decision date has passed";
        return null;
    }

    private static String chronosGone(LocalDate today, JsonObject x, Set<String> acts, Set<String> gls) {
        if (x == null) return null;
        String type = str(x, "type");
        if (type == null) return null;
        String act = str(x, "activity"), goal = str(x, "goal");
        switch (type) {
            case "edit_activity", "pause_activity", "archive_activity", "restore_activity" -> {
                if (act != null && !acts.contains(norm(act))) return "Chronos no longer has " + act;
            }
            case "edit_goal" -> {
                if (goal != null && !gls.contains(norm(goal))) return "Chronos no longer has " + goal;
            }
            default -> { }
        }
        if (past(str(x, "until"), today)) return "the period it was for is over";
        if (past(str(x, "deadline"), today)) return "its date is already behind you";
        if ("rest_day".equals(type) && past(str(x, "date"), today)) return "that day is already behind you";
        return null;
    }

    private static boolean past(String iso, LocalDate today) {
        if (iso == null) return false;
        try { return LocalDate.parse(iso.trim()).isBefore(today); } catch (Exception e) { return false; }
    }

    /** {@code node.ID.something} → ID. */
    private static String nodeOf(String key) {
        if (!key.startsWith("node.")) return null;
        String rest = key.substring(5);
        int dot = rest.lastIndexOf('.');
        return dot <= 0 ? null : rest.substring(0, dot);
    }

    /** Node ids in the graph file are upper-case words with underscores; a crew job id is not. */
    private static boolean looksLikeNodeId(String s) {
        return s.matches("[A-Z][A-Z0-9_]+");
    }

    private static String type(Op o) { return o.node() == null ? null : str(o.node(), "type"); }

    private static String str(JsonObject o, String k) {
        if (o == null) return null;
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static String norm(String s) { return s.trim().toLowerCase(Locale.ROOT); }

    private static Set<String> normAll(Set<String> in) {
        Set<String> out = new java.util.HashSet<>();
        if (in != null) for (String s : in) if (s != null) out.add(norm(s));
        return out;
    }

    private static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
