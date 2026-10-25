package com.chronos.navigator.trigger;

import com.chronos.navigator.llm.EstimatePrior;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.NodeType;
import com.chronos.navigator.model.Kinds.Owner;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Param;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The trigger layer (spec, "LLM layer"): the only way the LLM is woken. Run
 * after each night's forecast, without the network; what fires becomes one
 * question each, sent when there is a network and the budget allows.
 *
 *   prob_drop        a deadline's P falls below 0.60; armed again only above 0.70
 *   prob_jump        a deadline's P rises by more than 0.15 within 7 days
 *   manual_change    goals, constraints or deadlines were changed by you
 *   expand_due       a coarse node has reached its expand_at
 *   external_window  a gate's window opens, or its known deadline changes
 *   monthly_review   the first Wednesday of each month
 *
 * Each has a cooldown (24 h unless stated), kept per trigger and node in
 * {@link State}, so the same thing is not asked twice in a row.
 */
public final class Triggers {
    private Triggers() {}

    public static final String PROB_DROP = "prob_drop", PROB_JUMP = "prob_jump", MANUAL_CHANGE = "manual_change",
        EXPAND_DUE = "expand_due", EXTERNAL_WINDOW = "external_window", MONTHLY_REVIEW = "monthly_review";

    /** In the order they are sent when several fire on one night (the daily cap may stop the rest). */
    public static final List<String> ORDER =
        List.of(PROB_DROP, EXPAND_DUE, EXTERNAL_WINDOW, MANUAL_CHANGE, PROB_JUMP, MONTHLY_REVIEW);

    /** Nodes one prob_drop or prob_jump question covers at most; the rest wait for the next night. */
    public static final int GROUP = 4;

    /** One trigger's memory, for one node ("*" when it is about the whole graph). Stored as nav_triggers. */
    public static final class State {
        public String id, kind, node;
        public Double value;
        public long firedAt, cooldownUntil;
        public boolean armed = true;

        public State() {}

        State(String kind, String node) {
            this.id = kind + "|" + node; this.kind = kind; this.node = node;
        }
    }

    /**
     * @param nodes  what it is about, most urgent first; empty for the whole graph
     * @param detail what happened, in words, for the question and for the record
     */
    public record Fired(String kind, List<String> nodes, String detail) {}

    /**
     * What the night knows.
     *
     * @param pNow         each node's P(by deadline) in the newest forecast
     * @param pLowest      each node's lowest P over the last prob_jump window (the newest included)
     * @param changeHash   a fingerprint of your goals, constraints and answers; null to skip manual_change
     * @param changeDetail what changed, in words, if known
     * @param busy         nodes with an expansion already waiting for you or approved
     */
    public record Night(LocalDate today, long now, Map<String, Double> pNow, Map<String, Double> pLowest,
                        String changeHash, String changeDetail, Set<String> busy) {}

    /** Evaluates every trigger. {@code state} is read and updated in place; new entries are added to it. */
    public static List<Fired> evaluate(Graph g, Night night, Map<String, State> state) {
        List<Fired> out = new ArrayList<>();
        long cooldown = (long) (value(g, "trigger.cooldown_h", "value") * 3_600_000L);
        long retry = (long) (value(g, "trigger.retry_days", "value") * 86_400_000L);
        Set<String> hidden = EstimatePrior.privateNodes(g);

        // prob_drop, with hysteresis
        double below = value(g, "trigger.prob_drop", "below"), reset = value(g, "trigger.prob_drop", "reset");
        List<String> dropped = new ArrayList<>();
        for (Node n : deadlines(g, night, hidden)) {
            double p = night.pNow().get(n.id);
            State s = state.computeIfAbsent(PROB_DROP + "|" + n.id, k -> new State(PROB_DROP, n.id));
            if (!s.armed && p > reset) s.armed = true;
            if (s.armed && p < below && night.now() >= s.cooldownUntil) dropped.add(n.id);
            s.value = p;
        }
        dropped.sort(Comparator.comparingDouble(id -> night.pNow().get(id)));
        if (dropped.size() > GROUP) dropped = new ArrayList<>(dropped.subList(0, GROUP));
        for (String id : dropped) {
            State s = state.get(PROB_DROP + "|" + id);
            s.armed = false; s.firedAt = night.now(); s.cooldownUntil = night.now() + cooldown;
        }
        if (!dropped.isEmpty()) out.add(new Fired(PROB_DROP, dropped, describe(g, night, dropped, "fell below", below)));

        // expand_due
        for (Node n : g.nodes.values()) {
            if (n.expandAt == null || n.expandAt.isAfter(night.today()) || n.expanded || done(n)) continue;
            if (n.type != NodeType.TASK && n.type != NodeType.MILESTONE) continue;
            if (hidden.contains(n.id) || n.owner == Owner.ADVISOR || n.owner == Owner.EXTERNAL) continue;
            if (night.busy().contains(n.id)) continue;
            State s = state.computeIfAbsent(EXPAND_DUE + "|" + n.id, k -> new State(EXPAND_DUE, n.id));
            if (night.now() < s.cooldownUntil) continue;
            s.firedAt = night.now(); s.cooldownUntil = night.now() + retry; s.value = (double) n.expandAt.toEpochDay();
            out.add(new Fired(EXPAND_DUE, List.of(n.id), n.id + " reached its expand_at (" + n.expandAt + ")"));
        }

        // external_window: a gate's window opens, or its deadline moves
        for (Node n : g.nodes.values()) {
            if (n.type != NodeType.GATE || done(n) || hidden.contains(n.id)) continue;
            double deadline = n.deadline == null ? -1 : n.deadline.toEpochDay();
            State s = state.get(EXTERNAL_WINDOW + "|" + n.id);
            String why = null;
            if (s == null) {
                s = new State(EXTERNAL_WINDOW, n.id);
                s.value = deadline;            // first sight: remembered, nothing has changed yet
                state.put(s.id, s);
            } else if (s.value != null && s.value != deadline) {
                why = n.id + ": its deadline changed from " + date(s.value) + " to " + date(deadline);
            }
            State open = state.computeIfAbsent(EXTERNAL_WINDOW + "|" + n.id + "|open",
                k -> { State o = new State(EXTERNAL_WINDOW, n.id); o.id = k; return o; });
            if (why == null && open.armed && n.earliest != null && !n.earliest.isAfter(night.today())) {
                why = n.id + ": its window opened on " + n.earliest;
                open.armed = false;
            }
            if (why == null || night.now() < s.cooldownUntil) continue;
            s.value = deadline; s.firedAt = night.now(); s.cooldownUntil = night.now() + cooldown;
            out.add(new Fired(EXTERNAL_WINDOW, List.of(n.id), why));
        }

        // manual_change: the fingerprint moved since the last look
        if (night.changeHash() != null) {
            double h = night.changeHash().hashCode();
            State s = state.get(MANUAL_CHANGE + "|*");
            if (s == null) {
                s = new State(MANUAL_CHANGE, "*");
                s.value = h;
                state.put(s.id, s);
            } else if (s.value == null || s.value != h) {
                // Kept unchanged while cooling down, so the change is still seen once it may fire.
                if (night.now() >= s.cooldownUntil) {
                    s.value = h; s.firedAt = night.now(); s.cooldownUntil = night.now() + cooldown;
                    out.add(new Fired(MANUAL_CHANGE, List.of(),
                        night.changeDetail() == null ? "Your goals, constraints or answers changed" : night.changeDetail()));
                }
            }
        }

        // prob_jump
        double delta = value(g, "trigger.prob_jump", "delta"), days = value(g, "trigger.prob_jump", "days");
        List<String> jumped = new ArrayList<>();
        for (Node n : deadlines(g, night, hidden)) {
            Double low = night.pLowest().get(n.id);
            if (low == null) continue;
            State s = state.computeIfAbsent(PROB_JUMP + "|" + n.id, k -> new State(PROB_JUMP, n.id));
            if (night.pNow().get(n.id) - low > delta && night.now() >= s.cooldownUntil) jumped.add(n.id);
        }
        jumped.sort(Comparator.comparingDouble(id -> night.pLowest().get(id) - night.pNow().get(id)));
        if (jumped.size() > GROUP) jumped = new ArrayList<>(jumped.subList(0, GROUP));
        for (String id : jumped) {
            State s = state.get(PROB_JUMP + "|" + id);
            // Quiet for the whole window: the low value that made the jump stays in it that long.
            s.firedAt = night.now(); s.cooldownUntil = night.now() + (long) (days * 86_400_000L);
            s.value = night.pNow().get(id);
        }
        if (!jumped.isEmpty()) {
            StringBuilder d = new StringBuilder();
            for (String id : jumped)
                d.append(d.length() == 0 ? "" : "; ").append(String.format(Locale.ROOT, "%s rose from %.0f%% to %.0f%% within %.0f days",
                    id, 100 * night.pLowest().get(id), 100 * night.pNow().get(id), days));
            out.add(new Fired(PROB_JUMP, jumped, d.toString()));
        }

        // monthly_review: the first Wednesday of the month
        LocalDate t = night.today();
        if (t.getDayOfWeek() == DayOfWeek.WEDNESDAY && t.getDayOfMonth() <= 7) {
            double month = t.getYear() * 100 + t.getMonthValue();
            State s = state.computeIfAbsent(MONTHLY_REVIEW + "|*", k -> new State(MONTHLY_REVIEW, "*"));
            if (s.value == null || s.value != month) {
                s.value = month; s.firedAt = night.now(); s.cooldownUntil = night.now() + cooldown;
                out.add(new Fired(MONTHLY_REVIEW, List.of(), "The first Wednesday of " + t.getMonth() + " " + t.getYear()));
            }
        }

        out.sort(Comparator.comparingInt(f -> ORDER.indexOf(f.kind())));
        return out;
    }

    /** The nodes with a deadline still ahead and a probability in the forecast. */
    private static List<Node> deadlines(Graph g, Night night, Set<String> hidden) {
        List<Node> out = new ArrayList<>();
        for (Node n : g.nodes.values()) {
            if (n.deadline == null || n.deadline.isBefore(night.today()) || done(n) || hidden.contains(n.id)) continue;
            if (night.pNow().get(n.id) == null) continue;
            out.add(n);
        }
        return out;
    }

    private static String describe(Graph g, Night night, List<String> ids, String verb, double line) {
        StringBuilder d = new StringBuilder();
        for (String id : ids)
            d.append(d.length() == 0 ? "" : "; ").append(String.format(Locale.ROOT, "P(%s by %s) %s %.0f%%: now %.0f%%",
                id, g.node(id).deadline, verb, 100 * line, 100 * night.pNow().get(id)));
        return d.toString();
    }

    static boolean done(Node n) {
        if (n.status == null) return false;
        String s = n.status.toLowerCase(Locale.ROOT);
        return s.startsWith("done") || s.startsWith("reached") || s.startsWith("dropped");
    }

    private static String date(double epochDay) {
        return epochDay < 0 ? "none" : LocalDate.ofEpochDay((long) epochDay).toString();
    }

    static double value(Graph g, String key, String field) {
        Param p = g.params.get(key);
        if (p == null || p.get(field) == null) throw new IllegalStateException("Parameter " + key + " has no " + field);
        return p.get(field);
    }
}
