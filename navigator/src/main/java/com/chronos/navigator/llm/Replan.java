package com.chronos.navigator.llm;

import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Kinds.Owner;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Overrides;
import com.chronos.navigator.model.Requirements;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * REPLAN_SUGGEST (prob_drop, prob_jump) and the reviews (monthly_review,
 * manual_change): the LLM proposes a few ways of working differently, each
 * made of actions from a small, closed vocabulary:
 *
 *   extra_hours    at least H hours a week on a node between two dates   → node.ID.min_weekly
 *   pause          no hours for a node between two dates                 → node.ID.pause
 *   move_deadline  a new deadline for a node you own                     → node.ID.deadline
 *   discuss        something to raise with the advisor (or anyone else)   → nothing is changed
 *
 * The LLM never states what an option would do to the odds: the app measures
 * that itself, by forecasting with and without it (principle 4: the LLM does
 * not write numbers into the graph unchecked). Each option is its own proposal;
 * you approve the ones you want.
 */
public final class Replan {
    private Replan() {}

    public static final String TASK = "REPLAN_SUGGEST", REVIEW = "REVIEW";

    /** Options one answer may offer. */
    static final int MAX_OPTIONS = 4;

    /** One node's forecast, as the question shows it. */
    public record Outlook(String id, Double p, LocalDate likely, Double pWeekAgo, double critical) {}

    /** The week the allocation planned: its expected goal hours and each node's share. */
    public record Week(LocalDate start, double capacityH, Map<String, Double> hours, boolean accepted) {}

    /** An input whose true value would move the forecast most (see InfoValue), for the review. */
    public record Question(String key, String text, double impact) {}

    static final String SYSTEM = String.join("\n",
        "You help a student's planning app decide how to respond when the forecast for their goals changes.",
        "You receive a compact snapshot: the goal graph's dates and owners, the forecast for every deadline (the share of simulated futures in which it is met), the hours planned this week, and what happened.",
        "",
        "Rules:",
        "- Propose at most four options. Each option is a short list of actions from this vocabulary only:",
        "  {\"type\":\"extra_hours\",\"node\":\"ID\",\"hours_per_week\":3,\"from\":\"YYYY-MM-DD\",\"until\":\"YYYY-MM-DD\"}  at least that many hours a week on the node between the dates;",
        "  {\"type\":\"pause\",\"node\":\"ID\",\"from\":\"YYYY-MM-DD\",\"until\":\"YYYY-MM-DD\"}  no hours for the node between the dates, which frees them for the rest;",
        "  {\"type\":\"move_deadline\",\"node\":\"ID\",\"new_deadline\":\"YYYY-MM-DD\"}  only for nodes owned by the student (owner USER) whose deadline is not hard;",
        "  {\"type\":\"discuss\",\"node\":\"ID\",\"topic\":\"…\"}  something to raise with whoever owns the node.",
        "- Ownership: ADVISOR nodes have a fixed scope and deadline. Their hours may be raised, but any change of scope or deadline is only a `discuss` action (the student talks to the advisor). SYSTEM_SUGGESTED nodes are support work and are the first to pause. EXTERNAL nodes (gates) are decided by others: never pause or move them.",
        "- Hours are finite: the week holds about the expected goal hours shown. Extra hours somewhere usually need a pause elsewhere; say so in the same option.",
        "- Do not state probabilities or how much an option would help. The app forecasts every option itself and shows the measured effect next to it.",
        "- Never propose anything that gathers private information about a specific person or arranges physical proximity to them. Anything involving another person is pursued only through the channel the graph names.",
        "- Fill `consent_self_check` truthfully: does your answer involve, target or depend on a specific other person?",
        "- Finish with exactly one JSON object matching the requested shape and nothing after it. Dates are YYYY-MM-DD. Keep each `rationale` to two sentences.");

    // ---------------------------------------------------------------- requests

    /** prob_drop or prob_jump, for the nodes that fired. */
    public static LlmRequest replan(Graph g, LocalDate today, String trigger, List<String> nodes, String detail,
                                    Map<String, Outlook> outlook, Week week) {
        JsonObject target = new JsonObject();
        target.addProperty("kind", "REPLAN");
        target.addProperty("trigger", trigger);
        target.addProperty("subject", String.join(",", nodes));
        JsonObject snap = snapshot(g, today, outlook, week);
        JsonArray focus = new JsonArray();
        Set<String> hidden = EstimatePrior.privateNodes(g);
        for (String id : nodes) if (g.node(id) != null && !hidden.contains(id)) focus.add(EstimatePrior.nodeView(g, g.node(id)));
        snap.add("concerned_nodes", focus);
        snap.addProperty("what_happened", detail);
        String ask = trigger.equals("prob_jump")
            ? "These deadlines became much more likely. Is there an opportunity to pull other work forward or start "
                + "something earlier with the slack? Propose up to four options; an option that changes nothing is fine "
                + "if the slack is better kept as rest."
            : "These deadlines fell below 60%. Propose up to four ways to recover them, from the cheapest to the most "
                + "drastic. For an advisor's node, the deadline itself can only be discussed with the advisor.";
        return new LlmRequest(TASK, target, SYSTEM, EstimatePrior.pretty(snap) + "\n\n" + ask + "\n\n" + SHAPE, false);
    }

    /** monthly_review or manual_change: a look at the whole picture. */
    public static LlmRequest review(Graph g, LocalDate today, String trigger, String detail,
                                    Map<String, Outlook> outlook, Week week, List<Question> questions) {
        JsonObject target = new JsonObject();
        target.addProperty("kind", "REVIEW");
        target.addProperty("trigger", trigger);
        target.addProperty("subject", trigger);
        JsonObject snap = snapshot(g, today, outlook, week);
        snap.addProperty("what_happened", detail);
        if (questions != null && !questions.isEmpty()) {
            JsonArray qs = new JsonArray();
            for (Question q : questions) {
                JsonObject o = new JsonObject();
                o.addProperty("input", q.key());
                o.addProperty("what", q.text());
                o.addProperty("moves_weighted_deadline_odds_by", Math.round(q.impact() * 1000) / 1000.0);
                qs.add(o);
            }
            snap.add("most_uncertain_inputs", qs);
        }
        String ask = String.join("\n",
            trigger.equals("manual_change")
                ? "The student changed their goals, constraints or answers. Check the graph for inconsistencies the change "
                    + "may have caused (a deadline before its prerequisites can be done, a node no hours can reach, "
                    + "a floor that no longer fits the week) and propose fixes."
                : "This is the monthly review. Look at the whole picture: which deadlines are at risk, where hours are "
                    + "spent on what matters little, what should be decided soon.",
            "Give up to five short `findings` (each naming the node ids it is about), up to three `questions` worth asking "
                + "the student — prefer the most uncertain inputs listed, phrased as a question they can answer — and up "
                + "to four options as described. Options may be empty.",
            "",
            "Shape:",
            "{\"findings\":[{\"text\":\"…\",\"nodes\":[\"ID\"]}],\"questions\":[{\"text\":\"…\",\"input\":\"…\"}],"
                + "\"options\":[{\"title\":\"…\",\"actions\":[…],\"rationale\":\"…\"}],"
                + "\"consent_self_check\":{\"involves_other_person\":false,\"explanation\":\"…\"},\"summary\":\"…\"}");
        return new LlmRequest(REVIEW, target, SYSTEM, EstimatePrior.pretty(snap) + "\n\n" + ask, false);
    }

    static final String SHAPE = "Shape:\n"
        + "{\"options\":[{\"title\":\"…\",\"actions\":[{\"type\":\"…\",\"node\":\"ID\"}],\"rationale\":\"…\"}],"
        + "\"consent_self_check\":{\"involves_other_person\":false,\"explanation\":\"…\"},\"summary\":\"…\"}";

    private static JsonObject snapshot(Graph g, LocalDate today, Map<String, Outlook> outlook, Week week) {
        JsonObject snap = EstimatePrior.context(g, today);
        Set<String> hidden = EstimatePrior.privateNodes(g);
        JsonArray f = new JsonArray();
        for (Node n : g.nodes.values()) {
            Outlook o = outlook.get(n.id);
            if (o == null || o.p() == null || hidden.contains(n.id) || n.deadline == null || n.deadline.isBefore(today)) continue;
            JsonObject x = new JsonObject();
            x.addProperty("id", n.id);
            x.addProperty("title", n.title);
            x.addProperty("type", String.valueOf(n.type));
            x.addProperty("owner", String.valueOf(n.owner));
            x.addProperty("deadline", n.deadline.toString());
            if (n.hard || n.hardDeadline) x.addProperty("hard", true);
            x.addProperty("p_by_deadline", round(o.p()));
            if (o.pWeekAgo() != null) x.addProperty("p_a_week_ago", round(o.pWeekAgo()));
            if (o.likely() != null) x.addProperty("likely_finish", o.likely().toString());
            if (o.critical() > 0.05) x.addProperty("critical_share", round(o.critical()));
            f.add(x);
        }
        snap.add("forecast", f);
        JsonArray streams = new JsonArray();
        for (Node n : g.nodes.values()) {
            if (hidden.contains(n.id) || !n.takesHours() || n.deadline != null || done(n)) continue;
            JsonObject x = new JsonObject();
            x.addProperty("id", n.id);
            x.addProperty("title", n.title);
            x.addProperty("type", String.valueOf(n.type));
            x.addProperty("owner", String.valueOf(n.owner));
            if (n.weeklyFloorH != null) x.addProperty("weekly_floor_h", n.weeklyFloorH);
            streams.add(x);
        }
        snap.add("ongoing_without_deadline", streams);
        if (week != null) {
            JsonObject w = new JsonObject();
            w.addProperty("starts", week.start().toString());
            w.addProperty("expected_goal_hours", Math.round(week.capacityH() * 2) / 2.0);
            w.addProperty("accepted_by_student", week.accepted());
            JsonObject h = new JsonObject();
            week.hours().forEach((id, v) -> { if (!hidden.contains(id)) h.addProperty(id, Math.round(v * 4) / 4.0); });
            w.add("hours_by_node", h);
            snap.add("this_week", w);
        }
        return snap;
    }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }

    // -------------------------------------------------------------- validation

    /**
     * Validates the answer. For a review the first result is the review itself
     * (findings and questions, nothing to apply); then one result per option,
     * each passing or failing on its own.
     */
    public static List<ProposalValidator.Result> validate(Graph g, LocalDate today, LlmRequest req, String text) {
        List<ProposalValidator.Result> out = new ArrayList<>();
        List<String> checks = new ArrayList<>();
        JsonObject o = ProposalValidator.extractJson(text);
        if (o == null) { out.add(ProposalValidator.Result.reject("The answer contains no JSON object", checks)); return out; }
        boolean review = REVIEW.equals(req.task());
        if (!review && (!o.has("options") || !o.get("options").isJsonArray())) {
            out.add(ProposalValidator.Result.reject("The answer has no options", checks)); return out;
        }
        String consent = ProposalValidator.consentSelfCheck(o);
        checks.add("shape");
        if (consent != null) { out.add(ProposalValidator.Result.reject(consent, checks)); return out; }
        String summary = ProposalValidator.str(o, "summary");

        if (review) {
            StringBuilder body = new StringBuilder();
            Set<String> hidden = EstimatePrior.privateNodes(g);
            JsonArray findings = o.has("findings") && o.get("findings").isJsonArray() ? o.getAsJsonArray("findings") : new JsonArray();
            int k = 0;
            for (JsonElement e : findings) {
                if (!e.isJsonObject() || k++ >= 5) continue;
                String t = ProposalValidator.str(e.getAsJsonObject(), "text");
                if (t == null || mentions(t, hidden)) continue;
                body.append("• ").append(t.trim()).append('\n');
            }
            JsonArray questions = o.has("questions") && o.get("questions").isJsonArray() ? o.getAsJsonArray("questions") : new JsonArray();
            k = 0;
            for (JsonElement e : questions) {
                if (!e.isJsonObject() || k++ >= 3) continue;
                String t = ProposalValidator.str(e.getAsJsonObject(), "text");
                if (t == null || mentions(t, hidden)) continue;
                body.append("? ").append(t.trim()).append('\n');
            }
            List<String> c = new ArrayList<>(checks);
            c.add("consent");
            out.add(new ProposalValidator.Result(true, List.of(), summary, body.toString().trim(), List.of(), null, c,
                req.target().get("trigger").getAsString(), List.of()));
        }

        JsonArray options = o.has("options") && o.get("options").isJsonArray() ? o.getAsJsonArray("options") : new JsonArray();
        int k = 0;
        for (JsonElement e : options) {
            if (k++ >= MAX_OPTIONS) break;
            out.add(option(g, today, e, summary, new ArrayList<>(checks)));
        }
        if (out.isEmpty()) out.add(ProposalValidator.Result.reject("The answer offers no option", checks));
        return out;
    }

    private static ProposalValidator.Result option(Graph g, LocalDate today, JsonElement e, String summary, List<String> checks) {
        if (!e.isJsonObject()) return ProposalValidator.Result.reject("An option is not an object", checks);
        JsonObject opt = e.getAsJsonObject();
        String title = ProposalValidator.str(opt, "title");
        String why = ProposalValidator.str(opt, "rationale");
        JsonArray actions = opt.has("actions") && opt.get("actions").isJsonArray() ? opt.getAsJsonArray("actions") : null;
        if (title == null || actions == null || actions.isEmpty())
            return titled(ProposalValidator.Result.reject("An option needs a title and at least one action", checks), title);
        Set<String> hidden = EstimatePrior.privateNodes(g);

        // 2. references
        for (JsonElement a : actions) {
            if (!a.isJsonObject()) return titled(ProposalValidator.Result.reject("An action is not an object", checks), title);
            String id = ProposalValidator.str(a.getAsJsonObject(), "node");
            if (id == null || g.node(id) == null)
                return titled(ProposalValidator.Result.reject("An action names " + id + ", which is not in the graph", checks), title);
        }
        checks.add("references");
        // 3. consent
        for (JsonElement a : actions) {
            String id = ProposalValidator.str(a.getAsJsonObject(), "node");
            if (hidden.contains(id))
                return titled(ProposalValidator.Result.reject(id + " involves another person and is outside what the LLM may change", checks), title);
        }
        if (mentions(title + " " + (why == null ? "" : why), hidden))
            return titled(ProposalValidator.Result.reject("The option is about a node that involves another person", checks), title);
        checks.add("consent");

        // 4. ownership and 5. numbers, action by action
        List<Op> ops = new ArrayList<>();
        boolean discussed = false;
        for (JsonElement a : actions) {
            JsonObject x = a.getAsJsonObject();
            String type = ProposalValidator.str(x, "type");
            Node n = g.node(ProposalValidator.str(x, "node"));
            String failure = type == null ? "An action has no type" : switch (type) {
                case "extra_hours" -> extraHours(g, today, n, x, ops, title);
                case "pause" -> pause(g, today, n, x, ops, title);
                case "move_deadline" -> moveDeadline(g, today, n, x, ops, title);
                case "discuss" -> {
                    String topic = ProposalValidator.str(x, "topic");
                    if (topic == null || topic.isBlank()) yield "A discuss action has no topic";
                    ops.add(Op.discuss(n.id, topic.trim()));
                    yield null;
                }
                default -> "Unknown action type " + type;
            };
            if (failure != null) return titled(ProposalValidator.Result.reject(failure, checks), title);
            discussed |= ops.get(ops.size() - 1).discussOnly();
        }
        checks.add(discussed ? "ownership (some actions: to discuss only)" : "ownership");
        checks.add("numbers");
        return new ProposalValidator.Result(true, ops, summary, why, List.of(), null, checks, title, List.of());
    }

    private static ProposalValidator.Result titled(ProposalValidator.Result r, String title) {
        return new ProposalValidator.Result(r.ok(), r.ops(), r.summary(), r.rationale(), r.sources(), r.reason(),
            r.checks(), title, r.effects());
    }

    static String extraHours(Graph g, LocalDate today, Node n, JsonObject x, List<Op> ops, String title) {
        if (!n.takesHours() || !Requirements.schedulable(n.type) || n.expanded || done(n))
            return n.id + " takes no hours of its own";
        if (!n.plannable()) return n.id + " cannot be planned";
        Double h = ProposalValidator.num(x, "hours_per_week");
        if (h == null || h <= 0 || h > 30) return n.id + ": hours_per_week must be above 0 and at most 30";
        if (n.maxHPerDay != null && h > 7 * n.maxHPerDay) return n.id + ": more hours than its daily cap allows";
        LocalDate from = ProposalValidator.date(x, "from"), until = ProposalValidator.date(x, "until");
        String dates = span(g, today, from, until, n.id);
        if (dates != null) return dates;
        if (from.isBefore(today)) from = today;
        Map<String, Double> v = new LinkedHashMap<>();
        v.put("value", h);
        v.put("from", (double) from.toEpochDay());
        v.put("until", (double) until.toEpochDay());
        ops.add(new Op(Overrides.nodeKey(n.id, "min_weekly"), Dist.POINT, v,
            ProposalValidator.before(g, Overrides.nodeKey(n.id, "min_weekly")), note(title, String.format(Locale.ROOT,
                "At least %s h a week on %s from %s to %s", trim(h), n.id, from, until)), false));
        return null;
    }

    static String pause(Graph g, LocalDate today, Node n, JsonObject x, List<Op> ops, String title) {
        if (n.owner == Owner.EXTERNAL) return n.id + " is decided by others and cannot be paused";
        if (!n.takesHours()) return n.id + " takes no hours, so there is nothing to pause";
        LocalDate from = ProposalValidator.date(x, "from"), until = ProposalValidator.date(x, "until");
        String dates = span(g, today, from, until, n.id);
        if (dates != null) return dates;
        if (from.isBefore(today)) from = today;
        if (n.owner == Owner.ADVISOR) {
            ops.add(Op.discuss(n.id, "Ask your advisor whether " + n.id + " can wait from " + from + " to " + until));
            return null;
        }
        Map<String, Double> v = new LinkedHashMap<>();
        v.put("from", (double) from.toEpochDay());
        v.put("until", (double) until.toEpochDay());
        ops.add(new Op(Overrides.nodeKey(n.id, "pause"), Dist.POINT, v,
            ProposalValidator.before(g, Overrides.nodeKey(n.id, "pause")),
            note(title, "No hours for " + n.id + " from " + from + " to " + until), false));
        return null;
    }

    static String moveDeadline(Graph g, LocalDate today, Node n, JsonObject x, List<Op> ops, String title) {
        LocalDate d = ProposalValidator.date(x, "new_deadline");
        if (d == null) return n.id + ": new_deadline is missing or not YYYY-MM-DD";
        if (!d.isAfter(today)) return n.id + ": the new deadline is not in the future";
        if (g.horizonEnd != null && d.isAfter(g.horizonEnd)) return n.id + ": the new deadline is past the graph's horizon";
        if (n.earliest != null && d.isBefore(n.earliest)) return n.id + ": the new deadline is before it may start";
        if (n.owner == Owner.ADVISOR) {
            ops.add(Op.discuss(n.id, "Ask your advisor whether the deadline of " + n.id + " can move to " + d));
            return null;
        }
        if (n.owner != Owner.USER) return n.id + " is not yours: only your own deadlines can move";
        if (n.hard || n.hardDeadline) return n.id + " has a hard deadline";
        Map<String, Double> v = new LinkedHashMap<>();
        v.put("value", (double) d.toEpochDay());
        v.putAll(ProposalValidator.was(g, n, "deadline"));
        ops.add(new Op(Overrides.nodeKey(n.id, "deadline"), Dist.POINT, v,
            n.deadline == null ? Map.of() : Map.of("value", (double) n.deadline.toEpochDay()),
            note(title, "Deadline of " + n.id + " moves to " + d), false));
        return null;
    }

    private static String span(Graph g, LocalDate today, LocalDate from, LocalDate until, String id) {
        if (from == null || until == null) return id + ": from and until must be dates (YYYY-MM-DD)";
        if (until.isBefore(today)) return id + ": the span is already over";
        if (until.isBefore(from)) return id + ": until is before from";
        if (from.isBefore(today.minusDays(7))) return id + ": from is in the past";
        if (g.horizonEnd != null && until.isAfter(g.horizonEnd)) return id + ": the span runs past the graph's horizon";
        return null;
    }

    private static String note(String title, String what) {
        return what + " — proposed by the LLM" + (title == null ? "" : " (" + title + ")");
    }

    /** Whether free text names one of the given node ids. */
    static boolean mentions(String text, Set<String> ids) {
        if (text == null) return false;
        for (String id : ids) if (text.contains(id)) return true;
        return false;
    }

    static boolean done(Node n) {
        if (n.status == null) return false;
        String s = n.status.toLowerCase(Locale.ROOT);
        return s.startsWith("done") || s.startsWith("reached") || s.startsWith("dropped");
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
