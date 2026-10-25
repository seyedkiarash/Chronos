package com.chronos.navigator.model;

import com.chronos.navigator.model.Kinds.DayType;
import com.chronos.navigator.model.Kinds.EdgeKind;
import com.chronos.navigator.model.Kinds.NodeType;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether a graph is well-formed: every reference resolves, nothing depends on
 * itself through a loop, the numbers are in range, and the consent rules hold.
 * Any issue here is INVALID — the graph is not stored. The same check guards
 * the import and, later, every change the LLM proposes.
 */
public final class GraphValidator {
    private GraphValidator() {}

    public static List<Issue> validate(Graph g) {
        List<Issue> out = new ArrayList<>();
        references(g, out);
        cycle(g).ifPresent(out::add);
        numbers(g, out);
        consent(g, out);
        regimes(g, out);
        return out;
    }

    // ------------------------------------------------------------- references

    private static void references(Graph g, List<Issue> out) {
        for (Edge e : g.edges) {
            String owner = g.nodes.containsKey(e.to()) && e.kind() != EdgeKind.CONTRIBUTES ? e.to() : e.from();
            if (!g.nodes.containsKey(e.from()))
                out.add(Issue.invalid(owner, owner + " refers to " + e.from() + ", which is not in the graph"));
            if (!g.nodes.containsKey(e.to()))
                out.add(Issue.invalid(owner, owner + " refers to " + e.to() + ", which is not in the graph"));
            if (e.from().equals(e.to()))
                out.add(Issue.invalid(e.from(), e.from() + " depends on itself"));
        }
        for (Node n : g.nodes.values()) {
            for (String r : n.pausedDuring)
                if (g.regime(r) == null)
                    out.add(Issue.invalid(n.id, n.id + " is paused during " + r + ", which is not a regime"));
            if (n.parent != null && g.node(n.parent) != null && g.node(n.parent).type != NodeType.EXTERNAL_TRACK)
                out.add(Issue.invalid(n.id, n.id + " has parent " + n.parent + ", which is not an external track"));
        }
        for (String id : g.consentAppliesTo)
            if (!g.nodes.containsKey(id))
                out.add(Issue.invalid("policies", "The consent policy names " + id + ", which is not in the graph"));
        for (Regime r : g.regimes) {
            if (r.overlayOn != null && g.regime(r.overlayOn) == null)
                out.add(Issue.invalid(r.id, r.id + " overlays " + r.overlayOn + ", which is not a regime"));
            for (String id : r.plannedHPerDay.keySet())
                if (!g.nodes.containsKey(id))
                    out.add(Issue.invalid(r.id, r.id + " plans hours for " + id + ", which is not in the graph"));
        }
    }

    /**
     * The first loop found, as an issue naming its members in order, or empty.
     * Iterative so a long chain cannot overflow the stack on a phone.
     */
    public static java.util.Optional<Issue> cycle(Graph g) {
        Map<String, List<String>> next = new HashMap<>();
        for (Edge e : g.edges)
            if (g.nodes.containsKey(e.from()) && g.nodes.containsKey(e.to()))
                next.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());

        Map<String, Integer> state = new HashMap<>();   // 1 on the path, 2 done
        Map<String, String> cameFrom = new HashMap<>();
        for (String start : g.nodes.keySet()) {
            if (state.containsKey(start)) continue;
            Deque<String[]> stack = new ArrayDeque<>();  // {node, index of next child}
            stack.push(new String[]{start, "0"});
            state.put(start, 1);
            while (!stack.isEmpty()) {
                String[] top = stack.peek();
                List<String> kids = next.getOrDefault(top[0], List.of());
                int i = Integer.parseInt(top[1]);
                if (i >= kids.size()) { state.put(top[0], 2); stack.pop(); continue; }
                top[1] = Integer.toString(i + 1);
                String k = kids.get(i);
                Integer s = state.get(k);
                if (s == null) {
                    state.put(k, 1);
                    cameFrom.put(k, top[0]);
                    stack.push(new String[]{k, "0"});
                } else if (s == 1) {
                    List<String> loop = new ArrayList<>();
                    loop.add(k);
                    for (String at = top[0]; at != null && !at.equals(k); at = cameFrom.get(at)) loop.add(1, at);
                    loop.add(k);
                    return java.util.Optional.of(Issue.invalid(k, "The graph has a loop: " + String.join(" → ", loop)));
                }
            }
        }
        return java.util.Optional.empty();
    }

    // ---------------------------------------------------------------- numbers

    private static void numbers(Graph g, List<Issue> out) {
        LocalDate end = g.horizonEnd;
        for (Node n : g.nodes.values()) {
            String id = n.id;
            pair(n.effortP50, n.effortP90, id, "effort p50", "effort p90", out);
            pair(n.perPointP50, n.perPointP90, id, "hours per point p50", "hours per point p90", out);
            positive(n.effortP50, id, "effort p50", out);
            positive(n.perUnitPrior, id, "hours per unit", out);
            positive(n.perUnitSigma, id, "effort sigma", out);
            positive(n.perPointP50, id, "hours per point", out);
            nonNegative(n.weeklyFloorH, id, "weekly floor", out);
            positive(n.maxHPerDay, id, "hours per day cap", out);
            if (n.maxHPerDay != null && n.maxHPerDay > 24)
                out.add(Issue.invalid(id, id + ": more than 24 hours a day"));
            if (n.weeklyFloorH != null && n.maxHPerDay != null && n.weeklyFloorH > 7 * n.maxHPerDay + 1e-9)
                out.add(Issue.invalid(id, id + ": the weekly floor cannot fit under the daily cap"));
            if (n.levelFrom != null && n.levelTo != null && n.levelFrom > n.levelTo)
                out.add(Issue.invalid(id, id + ": level from is above level to"));
            for (Double lv : new Double[]{n.levelFrom, n.levelTo})
                if (lv != null && (lv < 0 || lv > 10)) out.add(Issue.invalid(id, id + ": levels run 0 to 10"));
            if (n.successA != null && (n.successA <= 0 || n.successB <= 0))
                out.add(Issue.invalid(id, id + ": success prior Beta(a, b) needs a and b above zero"));
            if (n.standaloneValue != null && (n.standaloneValue < 0 || n.standaloneValue > 1))
                out.add(Issue.invalid(id, id + ": standalone value must be between 0 and 1"));
            if (n.unitsTotal != null && n.unitsDone != null && n.unitsDone > n.unitsTotal + 1e-9)
                out.add(Issue.invalid(id, id + ": more units done than there are"));
            if (n.earliest != null && n.deadline != null && n.earliest.isAfter(n.deadline))
                out.add(Issue.invalid(id, id + ": earliest start is after the deadline"));
            for (LocalDate d : new LocalDate[]{n.earliest, n.deadline, n.expandAt, n.decideBy})
                if (d != null && end != null && d.isAfter(end))
                    out.add(Issue.invalid(id, id + ": " + d + " is past the graph's horizon " + end));
        }
        for (Param p : g.params.values()) {
            if (p.dist == Kinds.Dist.BETA)
                for (Double v : p.values.values())
                    if (v == null || v <= 0) out.add(Issue.invalid(p.key, p.key + ": Beta parameters must be above zero"));
            Double sigma = p.get("sigma");
            if (sigma != null && sigma <= 0) out.add(Issue.invalid(p.key, p.key + ": sigma must be above zero"));
            Double median = p.get("median");
            if (p.dist == Kinds.Dist.LOGNORMAL && median != null && median <= 0)
                out.add(Issue.invalid(p.key, p.key + ": a lognormal median must be above zero"));
        }
        if (g.riskTolerance != null && (g.riskTolerance < 1 || g.riskTolerance > 5))
            out.add(Issue.invalid("policies", "Risk tolerance runs 1 to 5"));
    }

    private static void pair(Double p50, Double p90, String id, String a, String b, List<Issue> out) {
        if (p50 != null && p90 != null && p50 > p90)
            out.add(Issue.invalid(id, id + ": " + a + " is above " + b));
    }

    private static void positive(Double v, String id, String what, List<Issue> out) {
        if (v != null && v <= 0) out.add(Issue.invalid(id, id + ": " + what + " must be above zero"));
    }

    private static void nonNegative(Double v, String id, String what, List<Issue> out) {
        if (v != null && v < 0) out.add(Issue.invalid(id, id + ": " + what + " cannot be negative"));
    }

    // ---------------------------------------------------------------- consent

    /**
     * Nodes that involve another person are pursued only through the channel
     * written on them. Anything the policy names must say so; a node marked
     * unplannable ("none") may have nothing placed under it.
     */
    private static void consent(Graph g, List<Issue> out) {
        for (String id : g.consentAppliesTo) {
            Node n = g.node(id);
            if (n == null) continue;
            if (!n.consentRequired || n.consentChannel == null || n.consentChannel.isBlank())
                out.add(Issue.invalid(id, id + " is covered by the consent policy but does not name its channel"));
        }
        for (Node n : g.nodes.values()) {
            if (n.consentRequired && (n.consentChannel == null || n.consentChannel.isBlank()))
                out.add(Issue.invalid(n.id, n.id + " involves another person but names no channel"));
            if (n.plannable()) continue;
            for (Edge e : g.into(n.id))
                if (e.kind() == EdgeKind.CHILD)
                    out.add(Issue.invalid(n.id, n.id + " cannot be planned, so " + e.from() + " may not sit under it"));
            if (n.takesHours())
                out.add(Issue.invalid(n.id, n.id + " cannot be planned, so it cannot be work that takes hours"));
        }
    }

    // ---------------------------------------------------------------- regimes

    private static void regimes(Graph g, List<Issue> out) {
        Map<String, Integer> seen = new HashMap<>();
        for (Regime r : g.regimes) {
            if (seen.merge(r.id, 1, Integer::sum) > 1) out.add(Issue.invalid(r.id, "Two regimes share the id " + r.id));
            if (r.from != null && r.to != null && r.from.isAfter(r.to))
                out.add(Issue.invalid(r.id, r.id + " ends before it starts"));
            if (r.dataWeight != null && (r.dataWeight < 0 || r.dataWeight > 1))
                out.add(Issue.invalid(r.id, r.id + ": data weight must be between 0 and 1"));
            if (r.mix != null) {
                double sum = 0;
                for (DayType t : DayType.values()) {
                    Double v = r.mix.get(t);
                    if (v == null) { out.add(Issue.invalid(r.id, r.id + ": the mix has no share for " + t)); continue; }
                    if (v < 0) out.add(Issue.invalid(r.id, r.id + ": a share of days cannot be negative"));
                    sum += v;
                }
                if (r.mix.size() == DayType.values().length && Math.abs(sum - 1) > 0.01)
                    out.add(Issue.invalid(r.id, r.id + ": the shares of day types add up to "
                        + String.format(java.util.Locale.ROOT, "%.3f", sum) + ", not 1"));
            }
        }
        // Calendar regimes may not overlap: a date must have one mix of days.
        List<Regime> cal = new ArrayList<>();
        for (Regime r : g.regimes) if (!r.isOverlay() && r.from != null && r.to != null) cal.add(r);
        cal.sort((a, b) -> a.from.compareTo(b.from));
        for (int i = 1; i < cal.size(); i++)
            if (!cal.get(i).from.isAfter(cal.get(i - 1).to))
                out.add(Issue.invalid(cal.get(i).id, cal.get(i - 1).id + " and " + cal.get(i).id + " overlap"));
    }
}
