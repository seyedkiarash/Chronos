package com.chronos.navigator.llm;

import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.NodeType;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Param;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * EXPAND_NODE (spec, rolling wave): when a coarse node reaches its expand_at,
 * the LLM breaks it into smaller tasks — fine within the next six weeks,
 * medium (with their own expand_at) beyond. Approved, the children carry the
 * node's work: the node waits for them as it would for any dependency, and
 * keeps no effort of its own. Each child becomes its own goal and activity in
 * Chronos, so its hours reach your days under its own name.
 *
 * The structure is checked like a file: ids, references, no loop, dates inside
 * the parent's window, efforts that add up to roughly the parent's.
 */
public final class Expand {
    private Expand() {}

    public static final String TASK = "EXPAND_NODE";

    static final int MIN_CHILDREN = 2, MAX_CHILDREN = 8;

    static final String SYSTEM = String.join("\n",
        "You help a student's planning app break a large piece of work into smaller tasks they can schedule.",
        "You receive a compact snapshot of their goal graph (dates, titles, skill levels) and the node to break down.",
        "",
        "Rules:",
        "- Your answer is a proposal: the app checks it and the student approves or rejects it.",
        "- Produce 2 to 8 consecutive or parallel tasks that together are the whole node — nothing more. Each has a concrete deliverable a student would recognise as done.",
        "- Rolling wave: tasks starting within the next six weeks are `fine` (a few days to two weeks of work). Later ones may be `medium` and must then have their own `expand_at`, the date they will be broken down further.",
        "- Efforts are total hours of the student's own work: an honest median and a 90th percentile they would be surprised to exceed. Together they should come to about the node's own estimate; say so in the rationale if you think that estimate is wrong.",
        "- Dates stay inside the node's window. `depends_on` names only sibling tasks, by their `key`; no loops.",
        "- `activity` is a short name (at most 30 characters) under which the student's planner will show the task.",
        "- Never propose anything that gathers private information about a specific person or arranges physical proximity to them.",
        "- Fill `consent_self_check` truthfully: does your answer involve, target or depend on a specific other person?",
        "- Finish with exactly one JSON object matching the requested shape and nothing after it. Dates are YYYY-MM-DD.");

    public static LlmRequest request(Graph g, LocalDate today, String nodeId, List<String> chronosActivities) {
        Node n = g.node(nodeId);
        if (n == null) throw new IllegalArgumentException("No node " + nodeId);
        if (EstimatePrior.privateNodes(g).contains(nodeId))
            throw new IllegalArgumentException(nodeId + " involves another person; the LLM is not asked about it");
        JsonObject target = new JsonObject();
        target.addProperty("kind", "EXPAND");
        target.addProperty("subject", nodeId);
        JsonObject snap = EstimatePrior.context(g, today);
        snap.add("node", EstimatePrior.nodeView(g, n));
        JsonObject wave = new JsonObject();
        wave.addProperty("fine_until", today.plusWeeks(g.fineUntilWeeks == null ? 6 : g.fineUntilWeeks).toString());
        wave.addProperty("medium_until", today.plusMonths(g.mediumUntilMonths == null ? 6 : g.mediumUntilMonths).toString());
        snap.add("rolling_wave", wave);
        if (chronosActivities != null && !chronosActivities.isEmpty()) {
            JsonArray a = new JsonArray();
            chronosActivities.forEach(a::add);
            snap.add("planner_activities_now", a);
        }
        Param post = g.params.get("post.hours." + nodeId);
        if (post != null && post.get("hours") != null) snap.addProperty("hours_already_logged_on_it", post.get("hours"));
        String ask = String.join("\n",
            "Break node " + nodeId + " (\"" + n.title + "\") into smaller tasks."
                + (n.effortP50 != null ? String.format(Locale.ROOT, " Its own estimate is %.0f h at the median, %.0f h at the 90th percentile.",
                    n.effortP50, n.effortP90 == null ? n.effortP50 : n.effortP90) : "")
                + (n.earliest != null ? " It may start on " + n.earliest + "." : "")
                + (n.deadline != null ? " It is due on " + n.deadline + "." : ""),
            "",
            "Shape:",
            "{\"children\":[{\"key\":\"SHORT_KEY\",\"title\":\"…\",\"activity\":\"…\",\"deliverable\":\"…\","
                + "\"granularity\":\"fine\",\"expand_at\":null,\"p50_hours\":0.0,\"p90_hours\":0.0,"
                + "\"earliest\":\"YYYY-MM-DD or null\",\"deadline\":\"YYYY-MM-DD\",\"depends_on\":[\"OTHER_KEY\"]}],"
                + "\"rationale\":\"…\",\"consent_self_check\":{\"involves_other_person\":false,\"explanation\":\"…\"},"
                + "\"summary\":\"…\"}");
        return new LlmRequest(TASK, target, SYSTEM, EstimatePrior.pretty(snap) + "\n\n" + ask, false);
    }

    /** Checks the answer and turns it into NODE_ADD operations for the children and one NODE_EDIT for the node. */
    public static ProposalValidator.Result validate(Graph g, LocalDate today, LlmRequest req, String text) {
        List<String> checks = new ArrayList<>();
        JsonObject o = ProposalValidator.extractJson(text);
        if (o == null) return ProposalValidator.Result.reject("The answer contains no JSON object", checks);
        Node parent = g.node(req.subject());
        if (parent == null) return ProposalValidator.Result.reject("The node " + req.subject() + " is no longer in the graph", checks);

        // 1. shape
        JsonArray kids = o.has("children") && o.get("children").isJsonArray() ? o.getAsJsonArray("children") : null;
        if (kids == null || kids.size() < MIN_CHILDREN || kids.size() > MAX_CHILDREN)
            return ProposalValidator.Result.reject("The answer must give between " + MIN_CHILDREN + " and " + MAX_CHILDREN + " tasks", checks);
        String consent = ProposalValidator.consentSelfCheck(o);
        if (consent != null && consent.startsWith("The answer has no")) return ProposalValidator.Result.reject(consent, checks);
        checks.add("shape");

        // 2. references: keys, ids, siblings only, no loop
        Map<String, JsonObject> byKey = new LinkedHashMap<>();
        Map<String, String> idOf = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (JsonElement e : kids) {
            if (!e.isJsonObject()) return ProposalValidator.Result.reject("A task is not an object", checks);
            JsonObject c = e.getAsJsonObject();
            String key = ProposalValidator.str(c, "key");
            if (key == null || !key.matches("[A-Za-z0-9_]{1,20}")) return ProposalValidator.Result.reject("A task has no usable key", checks);
            key = key.toUpperCase(Locale.ROOT);
            if (byKey.containsKey(key)) return ProposalValidator.Result.reject("Two tasks share the key " + key, checks);
            String id = parent.id + "_" + key;
            if (g.node(id) != null) return ProposalValidator.Result.reject("The id " + id + " is already in the graph", checks);
            String title = ProposalValidator.str(c, "title"), activity = ProposalValidator.str(c, "activity");
            if (title == null || title.isBlank()) return ProposalValidator.Result.reject(id + " has no title", checks);
            if (activity == null || activity.isBlank()) activity = title;
            activity = activity.trim();
            if (activity.length() > 30) activity = activity.substring(0, 30).trim();
            if (!names.add(activity.toLowerCase(Locale.ROOT))) return ProposalValidator.Result.reject("Two tasks share the activity name " + activity, checks);
            c.addProperty("activity", activity);
            byKey.put(key, c);
            idOf.put(key, id);
        }
        for (Map.Entry<String, JsonObject> e : byKey.entrySet())
            for (String d : ProposalValidator.strings(e.getValue(), "depends_on")) {
                String k = d.toUpperCase(Locale.ROOT);
                if (!byKey.containsKey(k) && !idOf.containsValue(d))
                    return ProposalValidator.Result.reject(e.getKey() + " depends on " + d + ", which is not one of the tasks", checks);
                if (k.equals(e.getKey())) return ProposalValidator.Result.reject(e.getKey() + " depends on itself", checks);
            }
        checks.add("references");

        // 3. consent
        if (consent != null) return ProposalValidator.Result.reject(consent, checks);
        Set<String> hidden = EstimatePrior.privateNodes(g);
        for (JsonObject c : byKey.values())
            if (Replan.mentions(c.toString(), hidden))
                return ProposalValidator.Result.reject("A task is about a node that involves another person", checks);
        if (!parent.plannable()) return ProposalValidator.Result.reject(parent.id + " cannot be planned", checks);
        checks.add("consent");

        // 4. ownership
        if (parent.owner == com.chronos.navigator.model.Kinds.Owner.ADVISOR
                || parent.owner == com.chronos.navigator.model.Kinds.Owner.EXTERNAL)
            return ProposalValidator.Result.reject(parent.id + " is not restructured here: its scope belongs to "
                + String.valueOf(parent.owner).toLowerCase(Locale.ROOT), checks);
        if (parent.type != NodeType.TASK && parent.type != NodeType.MILESTONE)
            return ProposalValidator.Result.reject(parent.id + " is a " + parent.type + ", which is not broken down", checks);
        checks.add("ownership");

        // 5. numbers and dates
        double sum50 = 0;
        LocalDate mediumUntil = today.plusMonths(g.mediumUntilMonths == null ? 6 : g.mediumUntilMonths);
        List<Op> ops = new ArrayList<>();
        List<String> childIds = new ArrayList<>();
        for (Map.Entry<String, JsonObject> e : byKey.entrySet()) {
            JsonObject c = e.getValue();
            String id = idOf.get(e.getKey());
            Double p50 = ProposalValidator.num(c, "p50_hours"), p90 = ProposalValidator.num(c, "p90_hours");
            if (p50 == null || p90 == null || p50 <= 0) return ProposalValidator.Result.reject(id + ": p50_hours must be above zero", checks);
            if (p90 < p50) return ProposalValidator.Result.reject(id + ": p90_hours is below p50_hours", checks);
            sum50 += p50;
            LocalDate earliest = ProposalValidator.date(c, "earliest"), deadline = ProposalValidator.date(c, "deadline");
            if (deadline == null) deadline = parent.deadline;
            if (deadline == null) return ProposalValidator.Result.reject(id + " has no deadline", checks);
            if (deadline.isBefore(today)) return ProposalValidator.Result.reject(id + ": its deadline has passed", checks);
            if (parent.deadline != null && deadline.isAfter(parent.deadline))
                return ProposalValidator.Result.reject(id + " is due after " + parent.id + " itself (" + parent.deadline + ")", checks);
            if (parent.earliest != null && earliest != null && earliest.isBefore(parent.earliest)) earliest = parent.earliest;
            if (earliest != null && earliest.isAfter(deadline)) return ProposalValidator.Result.reject(id + " starts after its deadline", checks);
            String gran = ProposalValidator.str(c, "granularity");
            gran = "medium".equalsIgnoreCase(gran) ? "medium" : "fine";
            LocalDate expandAt = ProposalValidator.date(c, "expand_at");
            if (gran.equals("medium")) {
                if (expandAt == null) return ProposalValidator.Result.reject(id + " is medium and needs its own expand_at", checks);
                if (!expandAt.isAfter(today) || expandAt.isAfter(deadline))
                    return ProposalValidator.Result.reject(id + ": expand_at must fall after today and by its deadline", checks);
            } else {
                expandAt = null;
                LocalDate starts = earliest == null ? today : earliest;
                if (starts.isAfter(mediumUntil))
                    return ProposalValidator.Result.reject(id + " starts far ahead; it should be medium with its own expand_at", checks);
            }

            JsonObject node = new JsonObject();
            node.addProperty("id", id);
            node.addProperty("type", "TASK");
            node.addProperty("owner", String.valueOf(parent.owner).toLowerCase(Locale.ROOT));
            node.addProperty("title", ProposalValidator.str(c, "title").trim());
            if (parent.phase != null) node.addProperty("phase", parent.phase);
            node.addProperty("granularity", gran);
            if (expandAt != null) node.addProperty("expand_at", expandAt.toString());
            JsonObject effort = new JsonObject();
            effort.addProperty("total_h_p50", p50);
            effort.addProperty("total_h_p90", p90);
            node.add("effort", effort);
            JsonObject window = new JsonObject();
            if (earliest != null) window.addProperty("earliest", earliest.toString());
            window.addProperty("deadline", deadline.toString());
            node.add("window", window);
            JsonArray deps = new JsonArray();
            for (String d : ProposalValidator.strings(c, "depends_on")) {
                String k = d.toUpperCase(Locale.ROOT);
                deps.add(idOf.getOrDefault(k, d));
            }
            if (deps.isEmpty()) {
                // A first task waits for what the node itself waited for.
                for (String d : ProposalValidator.strings(parent.raw, "depends_on")) deps.add(d);
                if (parent.raw.has("any_of")) node.add("any_of", parent.raw.get("any_of").deepCopy());
            }
            if (!deps.isEmpty()) node.add("depends_on", deps);
            JsonObject chronos = new JsonObject();
            chronos.addProperty("activity", c.get("activity").getAsString());
            if (parent.needsHighFocus != null) chronos.addProperty("needs_high_focus", parent.needsHighFocus);
            node.add("chronos", chronos);
            if (parent.standaloneValue != null) node.addProperty("standalone_value", parent.standaloneValue);
            if (parent.role != null) node.addProperty("role", parent.role);
            if (!parent.pausedDuring.isEmpty()) {
                JsonArray pd = new JsonArray();
                parent.pausedDuring.forEach(pd::add);
                node.add("paused_during", pd);
            }
            String deliverable = ProposalValidator.str(c, "deliverable");
            if (deliverable != null) node.addProperty("deliverable", deliverable);
            node.addProperty("parent", parent.id);
            node.addProperty("expanded_from", parent.id);
            node.addProperty("source", "llm, approved by you");
            ops.add(Op.structure(Op.NODE_ADD, id, node, "New task " + id + ": " + node.get("title").getAsString()));
            childIds.add(id);
        }
        if (parent.effortP50 != null && (sum50 < 0.4 * parent.effortP50 || sum50 > 2.5 * parent.effortP50))
            return ProposalValidator.Result.reject(String.format(Locale.ROOT,
                "The tasks add up to %.0f h at the median, too far from the node's own %.0f h", sum50, parent.effortP50), checks);

        JsonObject edit = new JsonObject();
        edit.addProperty("id", parent.id);
        JsonObject set = new JsonObject();
        set.addProperty("expanded", true);
        set.addProperty("granularity", "expanded");
        edit.add("set", set);
        JsonArray add = new JsonArray();
        childIds.forEach(add::add);
        edit.add("add_depends_on", add);
        if (parent.raw.has("effort")) {
            JsonObject rename = new JsonObject();
            rename.addProperty("effort", "effort_before_expand");
            edit.add("rename", rename);
        }
        ops.add(Op.structure(Op.NODE_EDIT, parent.id, edit, parent.id + " waits for its " + childIds.size() + " tasks and keeps no effort of its own"));

        // The result must still be a graph the app accepts: no loop.
        List<JsonObject> rows = new ArrayList<>();
        for (Node n : g.nodes.values()) rows.add(n.raw);
        Optional<String> broken = Structure.check(rows, ops);
        if (broken.isPresent()) return ProposalValidator.Result.reject(broken.get(), checks);
        checks.add("numbers");
        return new ProposalValidator.Result(true, ops, ProposalValidator.str(o, "summary"),
            ProposalValidator.str(o, "rationale"), List.of(), null, checks, "Break down " + parent.id, List.of());
    }
}
