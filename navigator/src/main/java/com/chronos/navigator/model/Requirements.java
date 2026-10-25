package com.chronos.navigator.model;

import com.chronos.navigator.model.Issue.Fix;
import com.chronos.navigator.model.Issue.Severity;
import com.chronos.navigator.model.Kinds.EdgeKind;
import com.chronos.navigator.model.Kinds.NodeType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a valid graph still lacks before the layers can run on it — the
 * "no hidden defaults" rule made concrete. Each issue names the thing, says
 * why it is needed, and carries the form that clears it.
 */
public final class Requirements {
    private Requirements() {}

    /** How far ahead a node counts as "in play" for the activity-link check. */
    public static final int IN_PLAY_DAYS = 7;

    /**
     * @param chronosActivities Chronos activity names, through {@link #norm}
     * @param links             a node's activity name the user linked elsewhere: key {@link #linkKey}, value a Chronos activity name
     */
    public static List<Issue> check(Graph g, LocalDate today, Set<String> chronosActivities,
                                    Map<String, String> links) {
        List<Issue> out = new ArrayList<>();
        nodes(g, today, chronosActivities, links, out);
        calendar(g, today, out);
        params(g, out);
        return out;
    }

    public static boolean canRun(List<Issue> issues) {
        for (Issue i : issues) if (i.severity() != Severity.WARNING) return false;
        return true;
    }

    public static String norm(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static String linkKey(String nodeId, String activityName) {
        return nodeId + "|" + norm(activityName);
    }

    /** The Chronos activity a node's named activity resolves to, or null. */
    public static String resolve(String nodeId, String name, Set<String> chronosActivities, Map<String, String> links) {
        String linked = links.get(linkKey(nodeId, name));
        if (linked != null && chronosActivities.contains(norm(linked))) return norm(linked);
        return chronosActivities.contains(norm(name)) ? norm(name) : null;
    }

    // ------------------------------------------------------------------ nodes

    private static void nodes(Graph g, LocalDate today, Set<String> acts, Map<String, String> links, List<Issue> out) {
        for (Node n : g.nodes.values()) {
            if (n.type == null) continue;
            if (isDone(n)) continue;
            String name = n.id + " (" + n.title + ")";

            switch (n.type) {
                case TASK -> {
                    if (n.expanded) break;      // its children carry the work and their own estimates
                    boolean total = n.effortP50 != null && n.effortP90 != null;
                    boolean perUnit = n.perUnitPrior != null && n.perUnitSigma != null && n.unitsTotal != null;
                    if (!total && !perUnit)
                        out.add(new Issue(Severity.BLOCKING, Fix.NODE_EFFORT, n.id,
                            name + " has no effort estimate (hours at the median and at the 90th percentile)"));
                }
                case SKILL -> {
                    if (n.levelFrom == null || n.levelTo == null)
                        out.add(new Issue(Severity.BLOCKING, Fix.NODE_EFFORT, n.id,
                            name + " has no current or target level"));
                    boolean perPoint = n.perPointP50 != null && n.perPointP90 != null;
                    boolean total = n.effortP50 != null && n.effortP90 != null;
                    if (!perPoint && !total)
                        out.add(new Issue(Severity.BLOCKING, Fix.NODE_EFFORT, n.id,
                            name + " has no estimate of hours per level"));
                }
                case STREAM -> {
                    if (n.weeklyFloorH == null)
                        out.add(new Issue(Severity.BLOCKING, Fix.NODE_EFFORT, n.id,
                            name + " has no weekly floor"));
                }
                case GATE -> {
                    if (n.successA == null || n.successB == null)
                        out.add(new Issue(Severity.BLOCKING, Fix.NODE_PRIOR, n.id,
                            name + " has no success prior: how likely is it to open?"));
                }
                case MILESTONE -> {
                    boolean decided = !g.into(n.id, EdgeKind.AND).isEmpty() || !g.into(n.id, EdgeKind.OR_GROUP).isEmpty()
                        || (n.raw != null && n.raw.has("condition") && !n.raw.get("condition").isJsonNull());
                    if (!decided && (n.successA == null || n.successB == null))
                        out.add(new Issue(Severity.BLOCKING, Fix.NODE_PRIOR, n.id,
                            name + " depends on nothing in the graph and has no condition, so nothing decides "
                                + "whether it is reached. Give it a probability (success prior)."));
                }
                default -> { }
            }

            for (String a : n.activities) {
                if (resolve(n.id, a, acts, links) != null) continue;
                boolean inPlay = inPlay(g, n, today);
                out.add(new Issue(inPlay ? Severity.BLOCKING : Severity.WARNING, Fix.LINK_ACTIVITY,
                    linkKey(n.id, a),
                    name + " counts the Chronos activity \"" + a.trim() + "\", which does not exist. "
                        + "Create it in Setup or link an existing one."
                        + (inPlay ? "" : " Not needed yet: the node is paused or has not started.")));
            }
        }
    }

    public static boolean isDone(Node n) {
        if (n.status == null) return false;
        String s = n.status.toLowerCase(Locale.ROOT);
        return s.startsWith("done") || s.startsWith("reached") || s.startsWith("dropped");
    }

    /** Whether a node may get hours in the coming week: not paused today, and its window has opened. */
    public static boolean inPlay(Graph g, Node n, LocalDate today) {
        for (String r : n.pausedDuring) {
            Regime regime = g.regime(r);
            if (regime != null && regime.covers(today)) return false;
        }
        return n.earliest == null || !n.earliest.isAfter(today.plusDays(IN_PLAY_DAYS));
    }

    // --------------------------------------------------------------- calendar

    private static void calendar(Graph g, LocalDate today, List<Issue> out) {
        if (g.horizonEnd == null) return;
        LocalDate gapStart = null;
        for (LocalDate d = today; !d.isAfter(g.horizonEnd); d = d.plusDays(1)) {
            boolean covered = g.calendarRegime(d) != null;
            if (!covered && gapStart == null) gapStart = d;
            if (covered && gapStart != null) { gap(gapStart, d.minusDays(1), out); gapStart = null; }
        }
        if (gapStart != null) gap(gapStart, g.horizonEnd, out);

        for (Regime r : g.regimes) {
            if (r.isOverlay() || r.to == null || r.to.isBefore(today)) continue;
            if (r.mix == null)
                out.add(new Issue(Severity.BLOCKING, Fix.REGIME_MIX, r.id,
                    r.id + " has no mix of ideal, busy and stress days"));
            if (r.assumption && r.askWhen != null && !r.askWhen.isAfter(today))
                out.add(new Issue(Severity.WARNING, Fix.REGIME_CONFIRM, r.id,
                    r.id + " (" + r.from + " to " + r.to + ") was an assumption. Are these dates right?"));
        }
    }

    private static void gap(LocalDate from, LocalDate to, List<Issue> out) {
        out.add(new Issue(Severity.BLOCKING, Fix.REGIME_GAP, from + ".." + to,
            "No regime covers " + from + " to " + to + ": the forecast does not know what kind of days these are"));
    }

    // ----------------------------------------------------------------- params

    private static void params(Graph g, List<Issue> out) {
        for (ParamRegistry.Spec s : ParamRegistry.ALL) {
            if (s.stage() > ParamRegistry.BUILT_STAGE) continue;
            List<String> missing = ParamRegistry.missing(g, s);
            if (missing.isEmpty()) continue;
            out.add(new Issue(Severity.BLOCKING, Fix.PARAM, s.key(),
                "Parameter " + s.key() + " has no value for " + String.join(", ", missing)));
        }
    }

    /** Nodes a regime pauses, for the screens. */
    public static boolean pausedOn(Graph g, Node n, LocalDate d) {
        for (String r : n.pausedDuring) {
            Regime regime = g.regime(r);
            if (regime != null && regime.covers(d)) return true;
        }
        return false;
    }

    /** Whether a node type ever takes hours from the week. */
    public static boolean schedulable(NodeType t) {
        return t == NodeType.STREAM || t == NodeType.SKILL || t == NodeType.TASK;
    }
}
