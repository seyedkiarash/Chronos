package com.chronos.navigator.llm;

import com.chronos.navigator.model.Edge;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Issue;
import com.chronos.navigator.model.Kinds.DayType;
import com.chronos.navigator.model.Kinds.EdgeKind;
import com.chronos.navigator.model.Kinds.NodeType;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Param;
import com.chronos.navigator.model.Regime;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * ESTIMATE_PRIOR: a rough, honestly wide answer to a number nobody can give
 * precisely — the mix of days in a stretch years ahead, the hours of a coarse
 * node, the odds of a gate. The snapshot holds dates, titles and the user's
 * stated figures; it never holds the log, and never the nodes the consent
 * policy covers.
 */
public final class EstimatePrior {
    private EstimatePrior() {}

    public static final String TASK = "ESTIMATE_PRIOR";

    static final String SYSTEM = String.join("\n",
        "You help a student's planning app put numbers on things that cannot be known precisely.",
        "You receive a compact snapshot of their goal graph (dates, titles, the figures they stated) and one question.",
        "",
        "Rules:",
        "- Your answer is a proposal. The app validates it and the student approves or rejects it; nothing you say is applied directly.",
        "- Be honest about uncertainty. The further ahead or the vaguer the thing, the wider your range and the lower your stated sureness. A confident number for something years away is a wrong answer.",
        "- Reason from the snapshot and from general knowledge (academic calendars, typical workloads, base rates). If you search the web, list the pages you relied on in `sources`.",
        "- Never propose anything that gathers private information about a specific person or arranges physical proximity to them. Anything involving another person is pursued only through the channel the graph names.",
        "- Fill `consent_self_check` truthfully: does your answer involve, target or depend on a specific other person?",
        "- Finish with exactly one JSON object matching the requested shape and nothing after it. Dates are YYYY-MM-DD. Keep `rationale` to two or three sentences.");

    /** Builds the request that answers an issue, or throws when the issue is not an estimate. */
    public static LlmRequest forIssue(Graph g, LocalDate today, Issue issue) {
        return switch (issue.fix()) {
            case REGIME_GAP -> regimeGap(g, today, issue.subject());
            case REGIME_MIX -> regimeMix(g, today, issue.subject());
            case NODE_EFFORT -> nodeEffort(g, today, issue.subject());
            case NODE_PRIOR -> nodePrior(g, today, issue.subject());
            default -> throw new IllegalArgumentException("Not something the LLM estimates: " + issue.fix());
        };
    }

    // ---------------------------------------------------------------- regimes

    static LlmRequest regimeGap(Graph g, LocalDate today, String span) {
        String[] d = span.split("\\.\\.");
        JsonObject target = new JsonObject();
        target.addProperty("kind", "REGIME_GAP");
        target.addProperty("subject", span);
        target.addProperty("from", d[0]);
        target.addProperty("to", d[1]);

        JsonObject snap = context(g, today);
        snap.add("question", target);
        String ask = String.join("\n",
            "The calendar between " + d[0] + " and " + d[1] + " has no regime. Split it into consecutive stretches "
                + "(terms, exam periods, breaks, a summer internship, the move to graduate school …) that together cover "
                + "exactly that span with no gap and no overlap — the first starts on " + d[0] + " and the last ends on " + d[1] + ".",
            "Use what the snapshot says about milestones and where the student may study. Fewer, longer stretches are fine years ahead.",
            "For each stretch give the share of IDEAL, BUSY and STRESS days (summing to 1), and `strength_days`: how many "
                + "observed days your mix is worth. Use about 8-10 only for the next term, 3-5 within a year, 1-2 beyond.",
            "",
            "Shape:",
            "{\"regimes\":[{\"id\":\"UPPER_CASE_ID\",\"from\":\"YYYY-MM-DD\",\"to\":\"YYYY-MM-DD\",\"ideal\":0.0,\"busy\":0.0,"
                + "\"stress\":0.0,\"strength_days\":0.0,\"rationale\":\"…\"}],"
                + "\"consent_self_check\":{\"involves_other_person\":false,\"explanation\":\"…\"},"
                + "\"sources\":[{\"url\":\"…\",\"used_for\":\"…\"}],\"summary\":\"…\"}");
        return new LlmRequest(TASK, target, SYSTEM, pretty(snap) + "\n\n" + ask, true);
    }

    static LlmRequest regimeMix(Graph g, LocalDate today, String regimeId) {
        Regime r = g.regime(regimeId);
        if (r == null) throw new IllegalArgumentException("No regime " + regimeId);
        JsonObject target = new JsonObject();
        target.addProperty("kind", "REGIME_MIX");
        target.addProperty("subject", regimeId);
        JsonObject snap = context(g, today);
        snap.add("question", target);
        String ask = String.join("\n",
            "Regime " + regimeId + " (" + r.from + " to " + r.to + ") has no mix of day types. Propose the shares of "
                + "IDEAL, BUSY and STRESS days (summing to 1) and `strength_days` as described: 8-10 for the next term, 1-2 years ahead.",
            "",
            "Shape:",
            "{\"ideal\":0.0,\"busy\":0.0,\"stress\":0.0,\"strength_days\":0.0,\"rationale\":\"…\","
                + "\"consent_self_check\":{\"involves_other_person\":false,\"explanation\":\"…\"},"
                + "\"sources\":[{\"url\":\"…\",\"used_for\":\"…\"}],\"summary\":\"…\"}");
        return new LlmRequest(TASK, target, SYSTEM, pretty(snap) + "\n\n" + ask, true);
    }

    // ------------------------------------------------------------------ nodes

    static LlmRequest nodeEffort(Graph g, LocalDate today, String nodeId) {
        Node n = require(g, nodeId);
        JsonObject target = new JsonObject();
        target.addProperty("kind", "NODE_EFFORT");
        target.addProperty("subject", nodeId);
        JsonObject snap = context(g, today);
        snap.add("node", nodeView(g, n));
        snap.add("question", target);
        String ask = n.type == NodeType.STREAM
            ? String.join("\n",
                "Node " + nodeId + " is an ongoing stream with no weekly floor. Propose a sustainable floor in hours per week.",
                "", "Shape:",
                "{\"weekly_floor_hours\":0.0,\"rationale\":\"…\",\"consent_self_check\":{\"involves_other_person\":false,"
                    + "\"explanation\":\"…\"},\"sources\":[{\"url\":\"…\",\"used_for\":\"…\"}],\"summary\":\"…\"}")
            : String.join("\n",
                "Node " + nodeId + " has no effort estimate. Estimate the total hours of the student's own work it takes: "
                    + "the median (an honest middle) and the 90th percentile (a figure they would be surprised to exceed). "
                    + (n.expandAt != null ? "It is coarse and will be broken down on " + n.expandAt + "; a wide range is right. " : "")
                    + "Use the skill levels in the snapshot.",
                "", "Shape:",
                "{\"p50_hours\":0.0,\"p90_hours\":0.0,\"rationale\":\"…\",\"consent_self_check\":{\"involves_other_person\":false,"
                    + "\"explanation\":\"…\"},\"sources\":[{\"url\":\"…\",\"used_for\":\"…\"}],\"summary\":\"…\"}");
        return new LlmRequest(TASK, target, SYSTEM, pretty(snap) + "\n\n" + ask, true);
    }

    static LlmRequest nodePrior(Graph g, LocalDate today, String nodeId) {
        Node n = require(g, nodeId);
        JsonObject target = new JsonObject();
        target.addProperty("kind", "NODE_PRIOR");
        target.addProperty("subject", nodeId);
        JsonObject snap = context(g, today);
        snap.add("node", nodeView(g, n));
        snap.add("question", target);
        String ask = String.join("\n",
            "Nothing in the graph decides whether node " + nodeId + " is reached. Estimate the probability that it is, "
                + "and `strength_cases`: how many comparable cases your estimate is worth (2 = a loose guess, 20 = a solid base rate).",
            "", "Shape:",
            "{\"probability\":0.0,\"strength_cases\":0.0,\"rationale\":\"…\",\"consent_self_check\":{\"involves_other_person\":false,"
                + "\"explanation\":\"…\"},\"sources\":[{\"url\":\"…\",\"used_for\":\"…\"}],\"summary\":\"…\"}");
        return new LlmRequest(TASK, target, SYSTEM, pretty(snap) + "\n\n" + ask, true);
    }

    /**
     * external_window: a gate's window opened or its deadline moved, so its odds
     * are looked at again — with a web search for the reference class. The answer
     * replaces the stated prior once you approve it.
     */
    public static LlmRequest gateUpdate(Graph g, LocalDate today, String nodeId, String what) {
        Node n = require(g, nodeId);
        JsonObject target = new JsonObject();
        target.addProperty("kind", "NODE_PRIOR");
        target.addProperty("subject", nodeId);
        target.addProperty("update", true);
        JsonObject snap = context(g, today);
        snap.add("node", nodeView(g, n));
        snap.add("question", target);
        String now = n.successA != null && n.successB != null
            ? String.format(java.util.Locale.ROOT, "The current prior is Beta(%.1f, %.1f), a mean of %.0f%%.",
                n.successA, n.successB, 100 * n.successA / (n.successA + n.successB))
            : "It has no prior yet.";
        String ask = String.join("\n",
            "Something changed about gate " + nodeId + ": " + what + ". " + now,
            "Look again at the probability that it opens for this student, from the base rate of comparable cases "
                + "(search the web for acceptance rates, programme sizes, typical timelines) adjusted by what the snapshot says. "
                + "Give `strength_cases`: how many comparable cases your estimate is worth (2 = a loose guess, 20 = a solid base rate).",
            "", "Shape:",
            "{\"probability\":0.0,\"strength_cases\":0.0,\"rationale\":\"…\",\"consent_self_check\":{\"involves_other_person\":false,"
                + "\"explanation\":\"…\"},\"sources\":[{\"url\":\"…\",\"used_for\":\"…\"}],\"summary\":\"…\"}");
        return new LlmRequest(TASK, target, SYSTEM, pretty(snap) + "\n\n" + ask, true);
    }

    /**
     * A value the forecast is sensitive to and nobody can know well — often
     * something years away — asked of the LLM to refine. The key is the
     * parameter's: node.ID.effort, node.ID.success or regime.ID.mix. The answer
     * replaces the current value once you approve it.
     */
    public static LlmRequest refine(Graph g, LocalDate today, String key, String why) {
        String[] k = key.split("\\.");
        String what = k[k.length - 1];
        String id = key.substring(key.indexOf('.') + 1, key.lastIndexOf('.'));
        String note = "The forecast is sensitive to this value" + (why == null || why.isBlank() ? "" : " (" + why + ")")
            + ", and it is uncertain. Refine it: reason from base rates and the snapshot, and widen the range "
            + "(lower the sureness) the further ahead it is.";
        LlmRequest base = switch (what) {
            case "effort" -> nodeEffort(g, today, id);
            case "success" -> nodePrior(g, today, id);
            case "mix" -> regimeMix(g, today, id);
            default -> throw new IllegalArgumentException("Nothing to refine in " + key);
        };
        JsonObject target = base.target().deepCopy();
        target.addProperty("update", true);
        String now = switch (what) {
            case "effort" -> g.node(id).effortP50 == null ? "" : String.format(java.util.Locale.ROOT,
                " Its current estimate is %.0f h at the median and %.0f h at the 90th percentile.",
                g.node(id).effortP50, g.node(id).effortP90 == null ? g.node(id).effortP50 : g.node(id).effortP90);
            case "success" -> g.node(id).successA == null ? "" : String.format(java.util.Locale.ROOT,
                " Its current prior is Beta(%.1f, %.1f), a mean of %.0f%%.", g.node(id).successA, g.node(id).successB,
                100 * g.node(id).successA / (g.node(id).successA + g.node(id).successB));
            default -> g.regime(id) == null || g.regime(id).mix == null ? "" : " Its current mix is " + g.regime(id).mix + ".";
        };
        // The question's own wording assumed the value was missing: say it is a refinement.
        String user = base.user().replace("has no effort estimate", "has an effort estimate to refine")
            .replace("Nothing in the graph decides whether node " + id + " is reached", "The probability that node " + id + " is reached should be looked at again")
            .replace("has no mix of day types", "has a mix of day types to refine")
            + "\n\n" + note + now;
        return new LlmRequest(base.task(), target, base.system(), user, base.webSearch());
    }

    private static Node require(Graph g, String id) {
        Node n = g.node(id);
        if (n == null) throw new IllegalArgumentException("No node " + id);
        if (privateNodes(g).contains(id))
            throw new IllegalArgumentException(id + " involves another person; the LLM is not asked about it");
        return n;
    }

    // --------------------------------------------------------------- snapshot

    /** Nodes the consent policy covers, or that involve another person: never sent. */
    public static Set<String> privateNodes(Graph g) {
        Set<String> out = new HashSet<>(g.consentAppliesTo);
        for (Node n : g.nodes.values()) if (n.consentRequired || !n.plannable()) out.add(n.id);
        return out;
    }

    /** What every estimate may see: dates, the calendar, milestones, stated figures. */
    public static JsonObject context(Graph g, LocalDate today) {
        JsonObject s = new JsonObject();
        s.addProperty("today", today.toString());
        if (g.timezone != null) s.addProperty("timezone", g.timezone);
        if (g.horizonEnd != null) s.addProperty("graph_horizon_end", g.horizonEnd.toString());

        JsonArray regimes = new JsonArray();
        for (Regime r : g.regimes) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id);
            o.addProperty("from", String.valueOf(r.from));
            o.addProperty("to", String.valueOf(r.to));
            if (r.isOverlay()) o.addProperty("overlay_on", r.overlayOn);
            if (r.mix != null) {
                JsonObject mix = new JsonObject();
                for (DayType t : DayType.values()) if (r.mix.get(t) != null) mix.addProperty(t.name(), r.mix.get(t));
                o.add("mix", mix);
            }
            if (r.assumption) o.addProperty("dates_are_assumption", true);
            if (r.note != null) o.addProperty("note", r.note);
            regimes.add(o);
        }
        s.add("regimes", regimes);

        JsonObject cap = new JsonObject();
        for (DayType t : DayType.values()) {
            Param p = g.params.get("capacity." + t.name());
            if (p != null && p.get("mean") != null) cap.addProperty(t.name(), p.get("mean"));
        }
        s.add("goal_hours_per_day_by_day_type", cap);

        Set<String> hidden = privateNodes(g);
        JsonArray dated = new JsonArray();
        for (Node n : g.nodes.values()) {
            if (hidden.contains(n.id)) continue;
            if (n.earliest == null && n.deadline == null && n.decideBy == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", n.id);
            o.addProperty("title", n.title);
            o.addProperty("type", String.valueOf(n.type));
            o.addProperty("phase", n.phase);
            if (n.earliest != null) o.addProperty("earliest", n.earliest.toString());
            if (n.deadline != null) o.addProperty("deadline", n.deadline.toString());
            if (n.decideBy != null) o.addProperty("decide_by", n.decideBy.toString());
            dated.add(o);
        }
        s.add("dated_nodes", dated);

        Param paths = g.params.get("path_prior_weights");
        if (paths != null) {
            JsonObject w = new JsonObject();
            paths.values.forEach(w::addProperty);
            s.add("graduate_path_weights", w);
        }
        JsonObject state = g.raw == null ? null : g.raw.getAsJsonObject("user_state");
        if (state != null && state.has("skills_now")) s.add("skill_levels_0_to_10", state.get("skills_now"));
        return s;
    }

    /** One node and its neighbours by title, leaving out what involves another person. */
    public static JsonObject nodeView(Graph g, Node n) {
        Set<String> hidden = privateNodes(g);
        JsonObject o = n.raw.deepCopy();
        o.remove("consent");
        // References to private nodes go too, wherever the file put them.
        for (String key : new String[]{"depends_on", "soft_depends_on", "children"}) {
            JsonArray a = o.getAsJsonArray(key);
            if (a != null) for (int i = a.size() - 1; i >= 0; i--)
                if (a.get(i).isJsonPrimitive() && hidden.contains(a.get(i).getAsString())) a.remove(i);
        }
        JsonArray contrib = o.getAsJsonArray("contributes_to");
        if (contrib != null) for (int i = contrib.size() - 1; i >= 0; i--) {
            JsonObject c = contrib.get(i).isJsonObject() ? contrib.get(i).getAsJsonObject() : null;
            if (c != null && c.has("node") && hidden.contains(c.get("node").getAsString())) contrib.remove(i);
        }
        JsonArray before = new JsonArray(), after = new JsonArray();
        for (Edge e : g.into(n.id))
            if (e.kind() != EdgeKind.CREDIT && !hidden.contains(e.from()) && g.node(e.from()) != null)
                before.add(e.from() + ": " + g.node(e.from()).title + " (" + e.kind() + ")");
        for (Edge e : g.outOf(n.id))
            if (!hidden.contains(e.to()) && g.node(e.to()) != null)
                after.add(e.to() + ": " + g.node(e.to()).title + " (" + e.kind() + ")");
        o.add("comes_after", before);
        o.add("leads_to", after);
        return o;
    }

    static String pretty(JsonObject o) {
        return "Snapshot:\n" + new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o);
    }
}
