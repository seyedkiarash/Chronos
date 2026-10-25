package com.chronos.navigator.llm;

import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Kinds.NodeType;
import com.chronos.navigator.model.Kinds.Owner;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Overrides;
import com.chronos.navigator.model.Priority;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the Navigator may change, in one closed vocabulary — the goal graph and
 * Chronos alike (your decision of 13 Oct 2026: the Navigator commands the
 * goals, the activities, the upkeep and the levers of the week; Chronos
 * arranges the days inside them). A change set is a few named changes, each a
 * short list of actions that belong together and are approved together:
 *
 * <pre>
 * graph    add_task, set_priority, extra_hours, pause, move_deadline, discuss
 * Chronos  add_activity, edit_activity, archive_activity, restore_activity, edit_goal
 * levers   pressure (slack, safe lines), sleep (bed and wake), rest_day
 * </pre>
 *
 * Every action is checked here against the graph and against a snapshot of
 * Chronos the app supplies ({@code chronos}, see {@link #chronosShape}); one
 * that fails rejects its change, never the others. A new task becomes a node
 * and its own Chronos goal and activity, set up like an activity you already
 * have. Activities are archived, never deleted, so their log stays.
 */
public final class ChangeSet {
    private ChangeSet() {}

    /** A named change and the indices of its operations in {@link Parsed#ops}. */
    public record Group(String title, String why, List<Integer> ops) {}

    public record Rejected(String change, String reason) {}

    /**
     * @param failure  why nothing at all could be read, or null
     * @param message  what the model says to you, in its own words
     * @param questions what it needs to know from you before it can decide well
     */
    public record Parsed(String failure, String summary, String rationale, String message,
                         List<Op> ops, List<Group> groups, List<Rejected> rejected, List<String> questions) {
        public boolean ok() { return failure == null; }
        public boolean empty() { return groups.isEmpty(); }
    }

    /** The snapshot of Chronos the app sends along, for the prompts to describe. */
    public static final String CHRONOS_SHAPE = String.join("\n",
        "`chronos` describes the student's planner as it is: goals (CLOSED ones have a deadline and units, OPEN ones a weekly floor),",
        "activities (kind goal or upkeep; days are 0 = Saturday … 6 = Friday; windows are HH:mm spans it may occupy; archived ones are not planned).",
        "Each goal activity has its own target in `goals`, named after the activity; `goal_it_belongs_to` is the goal the student sees it under. edit_goal names a target by that name.",
        "levers (slack_ratio = share of free time never scheduled; f_safe / f_safe_p = mental / physical tiredness above which the planner penalises work, 0..1; bed / wake times),",
        "rest_days already chosen, and fixed commitments (classes) by weekday.");

    public static final String VOCABULARY = String.join("\n",
        "Actions (use only these; names of goals and activities are exactly as `chronos` writes them):",
        "  {\"type\":\"add_task\",\"key\":\"SHORT_KEY\",\"title\":\"…\",\"owner\":\"USER|ADVISOR\",\"p50_hours\":0,\"p90_hours\":0,",
        "     \"earliest\":\"YYYY-MM-DD or null\",\"deadline\":\"YYYY-MM-DD\",\"hard\":false,\"depends_on\":[\"NODE_ID or KEY\"],",
        "     \"needed_by\":[\"NODE_ID\"],\"priority\":\"critical|important|optional\",\"activity\":\"short planner name\",\"like\":\"existing activity\"}",
        "     a new piece of work: a node in the goal graph and its own goal and activity in the planner, set up like `like`.",
        "  {\"type\":\"set_priority\",\"node\":\"ID\",\"priority\":\"critical|important|optional\"}",
        "  {\"type\":\"extra_hours\",\"node\":\"ID\",\"hours_per_week\":3,\"from\":\"YYYY-MM-DD\",\"until\":\"YYYY-MM-DD\"}",
        "  {\"type\":\"pause\",\"node\":\"ID\",\"from\":\"YYYY-MM-DD\",\"until\":\"YYYY-MM-DD\"}   a graph node (for a Chronos activity use pause_activity)",
        "  {\"type\":\"move_deadline\",\"node\":\"ID\",\"new_deadline\":\"YYYY-MM-DD\"}   only the student's own, non-hard deadlines",
        "  {\"type\":\"discuss\",\"node\":\"ID\",\"topic\":\"…\"}   something to raise with whoever owns the node",
        "  {\"type\":\"add_activity\",\"name\":\"…\",\"kind\":\"upkeep|goal\",\"goal\":\"existing goal (kind goal)\",\"like\":\"existing activity or null\",",
        "     \"days\":[0,1],\"windows\":[{\"from\":\"HH:mm\",\"to\":\"HH:mm\"}],\"minutes\":30,\"weekly_floor_h\":3,\"start_date\":\"YYYY-MM-DD or null\"}",
        "     goal work gets its own ongoing target of weekly_floor_h hours a week under that goal (for a piece of work with a deadline use add_task)",
        "  {\"type\":\"edit_activity\",\"activity\":\"…\",\"days\":[…],\"windows\":[…],\"min_block\":30,\"max_block\":90,\"minutes\":30}   any subset",
        "  {\"type\":\"pause_activity\",\"activity\":\"…\",\"from\":\"YYYY-MM-DD\",\"until\":\"YYYY-MM-DD\"}   a Chronos activity is not planned between the dates",
        "  {\"type\":\"archive_activity\",\"activity\":\"…\"}   it stops being planned; its log stays",
        "  {\"type\":\"restore_activity\",\"activity\":\"…\"}",
        "  {\"type\":\"edit_goal\",\"goal\":\"…\",\"deadline\":\"YYYY-MM-DD\",\"total_units\":0,\"weekly_floor_h\":0,\"max_per_day_h\":0}   any subset",
        "  {\"type\":\"pressure\",\"slack_ratio\":0.2,\"f_safe\":0.6,\"f_safe_p\":0.6}   any subset; each moves at most 0.1 from now",
        "  {\"type\":\"sleep\",\"bed\":\"HH:mm\",\"wake\":\"HH:mm\"}   each moves at most the allowed shift; 6 to 10 hours asleep",
        "  {\"type\":\"rest_day\",\"date\":\"YYYY-MM-DD\",\"rest\":true}   within the next 14 days; rest false takes one back",
        "Corrections to the goal graph itself (when a link, an estimate or a chance in it is wrong):",
        "  {\"type\":\"set_links\",\"node\":\"ID\",\"depends_on\":[\"NODE_ID\"]}   the full list of what the node waits for (an empty list: nothing)",
        "  {\"type\":\"set_effort\",\"node\":\"ID\",\"p50_hours\":0,\"p90_hours\":0}   the total hours a task or skill takes",
        "  {\"type\":\"set_success\",\"node\":\"ID\",\"probability\":0.7,\"confidence\":\"low|medium|high\"}   the chance a gate or milestone succeeds once its work is done");

    public static final String SHAPE = "{\"message\":\"what you tell the student, in their language\",\"summary\":\"…\","
        + "\"rationale\":\"…\",\"changes\":[{\"title\":\"…\",\"why\":\"…\",\"actions\":[{\"type\":\"…\"}]}],"
        + "\"questions\":[\"…\"],\"consent_self_check\":{\"targets_other_person\":false}}";

    static final double LEVER_STEP = 0.10;
    static final int REST_AHEAD_DAYS = 14;

    // ------------------------------------------------------------------- read

    public static Parsed read(Graph g, LocalDate today, JsonObject chronos, String text) {
        JsonObject o = ProposalValidator.extractJson(text);
        if (o == null) return failed("The answer contains no JSON object");
        return read(g, today, chronos, o);
    }

    public static Parsed read(Graph g, LocalDate today, JsonObject chronos, JsonObject o) {
        JsonObject consent = o.has("consent_self_check") && o.get("consent_self_check").isJsonObject()
            ? o.getAsJsonObject("consent_self_check") : null;
        JsonElement targets = consent == null ? null : consent.get("targets_other_person");
        if (targets == null && consent != null) targets = consent.get("involves_other_person");
        if (targets == null || !targets.isJsonPrimitive() || !targets.getAsJsonPrimitive().isBoolean())
            return failed("The answer has no consent self-check");
        if (targets.getAsBoolean())
            return failed("The answer says it targets another person; such things are not proposed");

        List<String> questions = new ArrayList<>();
        for (String q : ProposalValidator.strings(o, "questions")) if (!q.isBlank()) questions.add(q.trim());
        Ctx c = new Ctx(g, today, chronos == null ? new JsonObject() : chronos);
        JsonArray changes = o.has("changes") && o.get("changes").isJsonArray() ? o.getAsJsonArray("changes") : new JsonArray();
        int k = 0;
        for (JsonElement e : changes) {
            if (k++ >= 8) break;
            if (!e.isJsonObject()) { c.rejected.add(new Rejected("?", "A change is not an object")); continue; }
            c.group(e.getAsJsonObject());
        }
        return new Parsed(null, ProposalValidator.str(o, "summary"), ProposalValidator.str(o, "rationale"),
            ProposalValidator.str(o, "message"), c.ops, c.groups, c.rejected, questions);
    }

    private static Parsed failed(String why) {
        return new Parsed(why, null, null, null, List.of(), List.of(), List.of(), List.of());
    }

    /** One reading: what has been accepted so far, so later changes may build on earlier ones. */
    private static final class Ctx {
        final Graph g;
        final LocalDate today;
        final JsonObject chronos;
        final Set<String> hidden;
        final List<Op> ops = new ArrayList<>();
        final List<Group> groups = new ArrayList<>();
        final List<Rejected> rejected = new ArrayList<>();
        /** add_task keys accepted so far, and the ids they became. */
        final Map<String, String> keys = new HashMap<>();
        /** Activity names taken, in Chronos or by an accepted change. */
        final Set<String> names = new java.util.HashSet<>();
        /** Lever changes accepted so far: the second one in a set is measured from the first. */
        final Map<String, Double> levers = new HashMap<>();

        Ctx(Graph g, LocalDate today, JsonObject chronos) {
            this.g = g;
            this.today = today;
            this.chronos = chronos;
            this.hidden = EstimatePrior.privateNodes(g);
            for (JsonObject a : list(chronos, "activities")) {
                String n = ProposalValidator.str(a, "name");
                if (n != null) names.add(norm(n));
            }
        }

        void group(JsonObject change) {
            String title = ProposalValidator.str(change, "title");
            if (title == null || title.isBlank()) title = "Change " + (groups.size() + rejected.size() + 1);
            JsonArray actions = change.has("actions") && change.get("actions").isJsonArray() ? change.getAsJsonArray("actions") : null;
            if (actions == null || actions.isEmpty()) { rejected.add(new Rejected(title, "It has no actions")); return; }
            if (Replan.mentions(change.toString(), hidden)) {
                rejected.add(new Rejected(title, "It is about a node that involves another person")); return;
            }
            List<Op> mine = new ArrayList<>();
            Map<String, String> newKeys = new HashMap<>();
            Set<String> newNames = new java.util.HashSet<>();
            Map<String, Double> newLevers = new HashMap<>();
            // One action that cannot be used is refused on its own; the rest of the change stands.
            for (JsonElement a : actions) {
                List<Op> one = new ArrayList<>();
                String failure = !a.isJsonObject() ? "An action is not an object"
                    : action(a.getAsJsonObject(), title, one, newKeys, newNames, newLevers);
                if (failure != null) {
                    String type = a.isJsonObject() ? ProposalValidator.str(a.getAsJsonObject(), "type") : null;
                    rejected.add(new Rejected(title, (type == null ? "" : type + ": ") + failure));
                } else mine.addAll(one);
            }
            if (mine.isEmpty()) return;
            // The graph must stay one the app accepts, with everything accepted so far.
            List<Op> structure = new ArrayList<>();
            for (Op op : ops) if (op.isStructure()) structure.add(op);
            boolean adds = false;
            for (Op op : mine) if (op.isStructure()) { structure.add(op); adds = true; }
            if (adds) {
                List<JsonObject> rows = new ArrayList<>();
                for (Node n : g.nodes.values()) rows.add(n.raw);
                Optional<String> broken = Structure.check(rows, structure);
                if (broken.isPresent()) { rejected.add(new Rejected(title, broken.get())); return; }
            }
            List<Integer> idx = new ArrayList<>();
            for (Op op : mine) { idx.add(ops.size()); ops.add(op); }
            keys.putAll(newKeys);
            names.addAll(newNames);
            levers.putAll(newLevers);
            groups.add(new Group(title, ProposalValidator.str(change, "why"), idx));
        }

        String action(JsonObject x, String title, List<Op> out, Map<String, String> newKeys, Set<String> newNames,
                      Map<String, Double> newLevers) {
            String type = ProposalValidator.str(x, "type");
            if (type == null) return "An action has no type";
            switch (type) {
                case "add_task": return addTask(x, out, newKeys, newNames);
                case "set_priority": return setPriority(x, out);
                case "extra_hours": case "pause": case "move_deadline": case "discuss": {
                    Node n = node(x, "node");
                    // Models often name what they mean the way Chronos does: an activity or a goal of the planner.
                    if (n == null) return byChronosName(type, x, title, out);
                    if (hidden.contains(n.id)) return n.id + " involves another person and is outside what the LLM may change";
                    return switch (type) {
                        case "extra_hours" -> Replan.extraHours(g, today, n, x, out, title);
                        case "pause" -> Replan.pause(g, today, n, x, out, title);
                        case "move_deadline" -> Replan.moveDeadline(g, today, n, x, out, title);
                        default -> {
                            String topic = ProposalValidator.str(x, "topic");
                            if (topic == null || topic.isBlank()) yield "A discuss action has no topic";
                            out.add(Op.discuss(n.id, topic.trim()));
                            yield null;
                        }
                    };
                }
                case "pause_activity": return pauseActivity(x, ProposalValidator.str(x, "activity"), out);
                case "add_activity": return addActivity(x, out, newNames);
                case "edit_activity": return editActivity(x, out);
                case "archive_activity": return archive(x, out, true);
                case "restore_activity": return archive(x, out, false);
                case "edit_goal": return editGoal(x, out);
                case "pressure": return pressure(x, out, newLevers);
                case "sleep": return sleep(x, out, newLevers);
                case "rest_day": return restDay(x, out);
                case "set_links": return setLinks(x, out);
                case "set_effort": return setEffort(x, out);
                case "set_success": return setSuccess(x, out);
                default: return "Unknown action type " + type;
            }
        }

        // ------------------------------------------------------------- graph

        /** A node the graph actions may correct, or why not. */
        private String correctable(Node n, String type) {
            if (n == null) return type + " names no node in the graph";
            if (hidden.contains(n.id)) return n.id + " involves another person and is outside what the LLM may change";
            if (Replan.done(n)) return n.id + " is finished";
            return null;
        }

        /** What a node waits for, replaced whole. Links to nodes the model was not shown are kept. */
        String setLinks(JsonObject x, List<Op> out) {
            Node n = node(x, "node");
            String no = correctable(n, "set_links");
            if (no != null) return no;
            if (!x.has("depends_on") || !x.get("depends_on").isJsonArray()) return n.id + ": depends_on must be a list";
            JsonArray deps = new JsonArray();
            Set<String> chosen = new java.util.LinkedHashSet<>();
            for (String d : ProposalValidator.strings(x, "depends_on")) {
                String ref = d.trim(), up = ref.toUpperCase(Locale.ROOT);
                String to = g.node(ref) != null ? ref : keys.get(up);
                if (to == null) return n.id + " would wait for " + ref + ", which is not in the graph";
                if (to.equals(n.id)) return n.id + " cannot wait for itself";
                if (hidden.contains(to)) return n.id + " would wait for a node that involves another person";
                chosen.add(to);
            }
            List<String> now = ProposalValidator.strings(n.raw, "depends_on");
            for (String d : now) if (hidden.contains(d)) chosen.add(d);
            if (new java.util.HashSet<>(now).equals(chosen)) return n.id + " already waits for exactly these";
            chosen.forEach(deps::add);
            JsonObject set = new JsonObject();
            set.add("depends_on", deps);
            JsonObject edit = new JsonObject();
            edit.addProperty("id", n.id);
            edit.add("set", set);
            out.add(Op.structure(Op.NODE_EDIT, n.id, edit, n.id + " waits for " + (chosen.isEmpty() ? "nothing" : String.join(", ", chosen))
                + " (was " + (now.isEmpty() ? "nothing" : String.join(", ", now)) + ")"));
            return null;
        }

        /** A task's or skill's total hours, as a new estimate that stands over the file's. */
        String setEffort(JsonObject x, List<Op> out) {
            Node n = node(x, "node");
            String no = correctable(n, "set_effort");
            if (no != null) return no;
            if (n.expanded) return n.id + " is broken down: its tasks carry the hours";
            if (n.effortP50 == null && n.perPointP50 == null && n.type != NodeType.TASK && n.type != NodeType.SKILL)
                return n.id + " is not measured in hours";
            Double p50 = ProposalValidator.num(x, "p50_hours"), p90 = ProposalValidator.num(x, "p90_hours");
            if (p50 == null || p50 <= 0) return n.id + ": p50_hours must be above zero";
            if (p90 == null) p90 = p50 * 1.6;
            if (p90 < p50) return n.id + ": p90_hours is below p50_hours";
            if (p90 > 3000) return n.id + ": p90_hours is beyond any plausible amount of work";
            double levels = n.levelFrom != null && n.levelTo != null ? Math.max(1, n.levelTo - n.levelFrom) : 1;
            Double was50 = n.perPointP50 != null ? n.perPointP50 * levels : n.effortP50;
            Double was90 = n.perPointP90 != null ? n.perPointP90 * levels : n.effortP90;
            Map<String, Double> values = new java.util.LinkedHashMap<>();
            values.put("p50", p50);
            values.put("p90", p90);
            values.put("override", 1.0);
            values.putAll(com.chronos.navigator.model.Overrides.was(n, "effort"));
            Map<String, Double> before = new java.util.LinkedHashMap<>();
            if (was50 != null) before.put("p50", was50);
            if (was90 != null) before.put("p90", was90);
            out.add(new Op(com.chronos.navigator.model.Overrides.nodeKey(n.id, "effort"), Dist.LOGNORMAL, values, before,
                String.format(Locale.ROOT, "%s takes %s–%s h", n.id, trim(p50), trim(p90))
                    + (was50 == null ? "" : String.format(Locale.ROOT, " (was %s–%s h)", trim(was50), trim(was90 == null ? was50 : was90))), false));
            return null;
        }

        /** The chance a gate or milestone succeeds, as a Beta prior whose weight follows the stated confidence. */
        String setSuccess(JsonObject x, List<Op> out) {
            Node n = node(x, "node");
            String no = correctable(n, "set_success");
            if (no != null) return no;
            if (n.successA == null && n.type != NodeType.GATE && n.type != NodeType.MILESTONE && n.type != NodeType.DECISION)
                return n.id + " has no chance of success to set: it is done when its work is";
            Double p = ProposalValidator.num(x, "probability");
            if (p == null) return n.id + ": probability is missing";
            if (p > 1 && p <= 100) p = p / 100;
            if (p < 0.01 || p > 0.99) return n.id + ": probability must be between 0.01 and 0.99";
            double strength = switch (String.valueOf(ProposalValidator.str(x, "confidence")).toLowerCase(Locale.ROOT)) {
                case "high" -> 25;
                case "medium" -> 10;
                default -> 4;
            };
            Map<String, Double> values = new java.util.LinkedHashMap<>();
            values.put("a", p * strength);
            values.put("b", (1 - p) * strength);
            values.put("override", 1.0);
            values.putAll(com.chronos.navigator.model.Overrides.was(n, "success"));
            Map<String, Double> before = new java.util.LinkedHashMap<>();
            String was = "";
            if (n.successA != null && n.successB != null) {
                before.put("a", n.successA);
                before.put("b", n.successB);
                was = " (was " + Math.round(100 * n.successA / (n.successA + n.successB)) + "%)";
            }
            out.add(new Op(com.chronos.navigator.model.Overrides.nodeKey(n.id, "success"), Dist.BETA, values, before,
                n.id + " succeeds with a " + Math.round(p * 100) + "% chance" + was, false));
            return null;
        }

        Node node(JsonObject x, String field) {
            String id = ProposalValidator.str(x, field);
            return id == null ? null : g.node(id.trim());
        }

        String addTask(JsonObject x, List<Op> out, Map<String, String> newKeys, Set<String> newNames) {
            String key = ProposalValidator.str(x, "key");
            if (key == null || !key.trim().matches("[A-Za-z0-9_]{1,20}")) return "A new task has no usable key";
            key = key.trim().toUpperCase(Locale.ROOT);
            String id = key.startsWith("T_") ? key : "T_" + key;
            if (g.node(id) != null || keys.containsValue(id) || newKeys.containsValue(id)) return "The id " + id + " is already taken";
            String title = ProposalValidator.str(x, "title");
            if (title == null || title.isBlank()) return id + " has no title";
            title = title.trim();
            if (title.length() > 80) title = title.substring(0, 80).trim();
            Owner owner = switch (String.valueOf(ProposalValidator.str(x, "owner")).toUpperCase(Locale.ROOT)) {
                case "ADVISOR" -> Owner.ADVISOR;
                case "SYSTEM_SUGGESTED" -> Owner.SYSTEM_SUGGESTED;
                default -> Owner.USER;
            };
            Double p50 = ProposalValidator.num(x, "p50_hours"), p90 = ProposalValidator.num(x, "p90_hours");
            if (p50 == null || p50 <= 0) return id + ": p50_hours must be above zero";
            if (p90 == null) p90 = p50 * 1.6;
            if (p90 < p50) return id + ": p90_hours is below p50_hours";
            if (p90 > 3000) return id + ": p90_hours is beyond any plausible amount of work";
            LocalDate deadline = ProposalValidator.date(x, "deadline");
            if (deadline == null) return id + " has no deadline (YYYY-MM-DD)";
            if (deadline.isBefore(today)) return id + ": its deadline has passed";
            if (g.horizonEnd != null && deadline.isAfter(g.horizonEnd)) return id + ": the deadline is past the graph's horizon";
            LocalDate earliest = ProposalValidator.date(x, "earliest");
            if (earliest != null && !earliest.isAfter(today)) earliest = null;
            if (earliest != null && earliest.isAfter(deadline)) return id + " starts after its deadline";
            Integer priority = Priority.parse(ProposalValidator.str(x, "priority"));

            JsonArray deps = new JsonArray();
            for (String d : ProposalValidator.strings(x, "depends_on")) {
                String ref = d.trim(), up = ref.toUpperCase(Locale.ROOT);
                String to = g.node(ref) != null ? ref : keys.getOrDefault(up, newKeys.get(up));
                if (to == null) return id + " depends on " + ref + ", which is neither in the graph nor a new task before it";
                if (hidden.contains(to)) return id + " depends on a node that involves another person";
                deps.add(to);
            }
            List<String> neededBy = new ArrayList<>();
            for (String d : ProposalValidator.strings(x, "needed_by")) {
                Node n = g.node(d.trim());
                if (n == null) return id + " is needed by " + d + ", which is not in the graph";
                if (hidden.contains(n.id)) return id + " is needed by a node that involves another person";
                if (n.owner == Owner.EXTERNAL) return n.id + " is decided by others: nothing is added to what it waits for";
                neededBy.add(n.id);
            }

            JsonObject like = activity(ProposalValidator.str(x, "like"));
            if (like == null || bool(like, "archived") || !"goal".equals(ProposalValidator.str(like, "kind"))) like = firstGoalActivity();
            if (like == null) return "There is no goal activity in Chronos to set the new task up like";
            String name = ProposalValidator.str(x, "activity");
            if (name == null || name.isBlank()) name = title;
            name = name.trim();
            if (name.length() > 30) name = name.substring(0, 30).trim();
            String base = name;
            for (int k = 2; names.contains(norm(name)) || newNames.contains(norm(name)); k++) name = base + " " + k;
            newNames.add(norm(name));

            JsonObject node = new JsonObject();
            node.addProperty("id", id);
            node.addProperty("type", "TASK");
            node.addProperty("owner", owner.name().toLowerCase(Locale.ROOT));
            node.addProperty("title", title);
            node.addProperty("granularity", "fine");
            if (bool(x, "hard")) node.addProperty("hard", true);
            if (priority != null) node.addProperty("priority", Priority.name(priority));
            JsonObject effort = new JsonObject();
            effort.addProperty("total_h_p50", p50);
            effort.addProperty("total_h_p90", p90);
            node.add("effort", effort);
            JsonObject window = new JsonObject();
            if (earliest != null) window.addProperty("earliest", earliest.toString());
            window.addProperty("deadline", deadline.toString());
            node.add("window", window);
            if (!deps.isEmpty()) node.add("depends_on", deps);
            JsonObject ch = new JsonObject();
            ch.addProperty("activity", name);
            node.add("chronos", ch);
            node.addProperty("source", "the Navigator, approved by you");
            out.add(Op.structure(Op.NODE_ADD, id, node, "New task " + id + ": " + title
                + String.format(Locale.ROOT, " (%s–%s h, due %s)", trim(p50), trim(p90), deadline)));
            for (String n : neededBy) {
                JsonObject edit = new JsonObject();
                edit.addProperty("id", n);
                JsonArray add = new JsonArray();
                add.add(id);
                edit.add("add_depends_on", add);
                out.add(Op.structure(Op.NODE_EDIT, n, edit, n + " now waits for " + id));
            }
            JsonObject what = new JsonObject();
            what.addProperty("type", "task_goal");
            what.addProperty("node", id);
            what.addProperty("goal", title.length() > 60 ? title.substring(0, 60).trim() : title);
            what.addProperty("activity", name);
            what.addProperty("like", ProposalValidator.str(like, "name"));
            what.addProperty("deadline", deadline.toString());
            what.addProperty("hours", Math.max(0.5, Math.round(p50 * 2) / 2.0));
            if (earliest != null) what.addProperty("start_date", earliest.toString());
            out.add(Op.chronos("task_goal", id, what, Map.of(), Map.of(), String.format(Locale.ROOT,
                "Chronos: a goal \"%s\" of %s h by %s, worked as \"%s\" (set up like \"%s\")",
                what.get("goal").getAsString(), trim(what.get("hours").getAsDouble()), deadline, name,
                ProposalValidator.str(like, "name"))));
            newKeys.put(key, id);
            newKeys.put(id, id);
            return null;
        }

        String setPriority(JsonObject x, List<Op> out) {
            Node n = node(x, "node");
            if (n == null) return "set_priority names " + ProposalValidator.str(x, "node") + ", which is not in the graph";
            if (hidden.contains(n.id)) return n.id + " involves another person and is outside what the LLM may change";
            if (n.deadline == null) return n.id + " has no deadline, so no priority";
            Integer p = Priority.parse(ProposalValidator.str(x, "priority"));
            if (p == null) return "priority must be critical, important or optional";
            String key = Overrides.nodeKey(n.id, "priority");
            Map<String, Double> before = new LinkedHashMap<>();
            before.put("value", (double) Priority.of(n));
            out.add(new Op(key, Dist.POINT, Map.of("value", (double) p), before,
                n.id + " counts as " + Priority.name(p) + " (was " + Priority.name(Priority.of(n)) + ")", false));
            return null;
        }

        // ----------------------------------------------------------- Chronos

        JsonObject activity(String name) {
            if (name == null) return null;
            for (JsonObject a : list(chronos, "activities"))
                if (norm(name).equals(norm(ProposalValidator.str(a, "name")))) return a;
            return null;
        }

        JsonObject goal(String name) {
            if (name == null) return null;
            for (JsonObject a : list(chronos, "goals"))
                if (norm(name).equals(norm(ProposalValidator.str(a, "name")))) return a;
            return null;
        }

        JsonObject firstGoalActivity() {
            for (JsonObject a : list(chronos, "activities"))
                if ("goal".equals(ProposalValidator.str(a, "kind")) && !bool(a, "archived")) return a;
            return null;
        }

        /**
         * pause, discuss or move_deadline naming a Chronos activity or goal instead of a graph node: pausing
         * pauses the activity (or every activity of the goal), discussing needs no node, and a goal's new
         * deadline is an edit of the goal.
         */
        String byChronosName(String type, JsonObject x, String title, List<Op> out) {
            String name = ProposalValidator.str(x, "node");
            JsonObject act = activity(name), gl = goal(name);
            if (act == null && gl == null && !"discuss".equals(type))
                return name + " is neither a node of the graph nor an activity or goal in Chronos";
            switch (type) {
                case "discuss": {
                    String topic = ProposalValidator.str(x, "topic");
                    if (topic == null || topic.isBlank()) return "A discuss action has no topic";
                    out.add(Op.discuss(name == null ? "general" : name.trim(), topic.trim()));
                    return null;
                }
                case "pause": {
                    if (act != null) return pauseActivity(x, ProposalValidator.str(act, "name"), out);
                    List<JsonObject> mine = new ArrayList<>();
                    for (JsonObject a : list(chronos, "activities"))
                        if (!bool(a, "archived") && norm(ProposalValidator.str(gl, "name")).equals(norm(ProposalValidator.str(a, "goal")))) mine.add(a);
                    if (mine.isEmpty()) return ProposalValidator.str(gl, "name") + " has no activity to pause";
                    for (JsonObject a : mine) {
                        String f = pauseActivity(x, ProposalValidator.str(a, "name"), out);
                        if (f != null) return f;
                    }
                    return null;
                }
                case "move_deadline": {
                    if (gl == null) return name + " is an activity; a deadline belongs to its goal";
                    JsonObject edit = new JsonObject();
                    edit.addProperty("goal", ProposalValidator.str(gl, "name"));
                    String d = ProposalValidator.str(x, "new_deadline");
                    if (d != null) edit.addProperty("deadline", d);
                    return editGoal(edit, out);
                }
                default:
                    return name + " is a Chronos " + (act != null ? "activity" : "goal")
                        + ": give its hours with edit_goal (weekly_floor_h) instead";
            }
        }

        /** An activity Chronos does not plan between two dates (its log stays; it comes back by itself). */
        String pauseActivity(JsonObject x, String name, List<Op> out) {
            JsonObject a = activity(name);
            if (a == null) return "pause_activity names " + name + ", which is not in Chronos";
            if (bool(a, "archived")) return ProposalValidator.str(a, "name") + " is archived already";
            LocalDate from = ProposalValidator.date(x, "from"), until = ProposalValidator.date(x, "until");
            if (from == null || until == null) return "from and until must be dates (YYYY-MM-DD)";
            if (from.isBefore(today)) from = today;
            if (until.isBefore(from)) return "until is before from";
            if (until.isAfter(today.plusDays(90))) return "a pause may last at most 90 days: archive the activity instead";
            String n = ProposalValidator.str(a, "name");
            JsonObject what = new JsonObject();
            what.addProperty("type", "pause_activity");
            what.addProperty("activity", n);
            what.addProperty("from", from.toString());
            what.addProperty("until", until.toString());
            out.add(Op.chronos("pause_activity", n, what, Map.of(), Map.of(),
                "Chronos: " + n + " is not planned from " + from + " to " + until));
            return null;
        }

        String addActivity(JsonObject x, List<Op> out, Set<String> newNames) {
            String name = ProposalValidator.str(x, "name");
            if (name == null || name.isBlank()) return "A new activity has no name";
            name = name.trim();
            if (name.length() > 30) return "\"" + name + "\" is longer than 30 characters";
            if (names.contains(norm(name)) || newNames.contains(norm(name))) return "There is already an activity called " + name;
            String kind = "goal".equalsIgnoreCase(ProposalValidator.str(x, "kind")) ? "goal" : "upkeep";
            JsonObject what = new JsonObject();
            what.addProperty("type", "add_activity");
            what.addProperty("name", name);
            what.addProperty("kind", kind);
            if (kind.equals("goal")) {
                JsonObject goal = goal(ProposalValidator.str(x, "goal"));
                if (goal == null) return name + ": a goal activity needs a goal that is in Chronos";
                what.addProperty("goal", ProposalValidator.str(goal, "name"));
            }
            JsonObject like = activity(ProposalValidator.str(x, "like"));
            if (like != null) what.addProperty("like", ProposalValidator.str(like, "name"));
            String failure = days(x, what, true);
            if (failure == null) failure = windows(x, what);
            if (failure == null) failure = blocks(x, what, kind);
            if (failure != null) return name + ": " + failure;
            if (kind.equals("upkeep") && !what.has("minutes") && like == null) return name + ": an upkeep activity needs its minutes";
            Double floor = ProposalValidator.num(x, "weekly_floor_h");
            if (kind.equals("goal") && floor != null) {
                if (floor < 0 || floor > 60) return name + ": weekly_floor_h must be between 0 and 60";
                what.addProperty("weekly_floor_h", floor);
            }
            LocalDate start = ProposalValidator.date(x, "start_date");
            if (start != null && start.isAfter(today)) what.addProperty("start_date", start.toString());
            newNames.add(norm(name));
            out.add(Op.chronos("add_activity", name, what, Map.of(), Map.of(),
                "Chronos: a new " + (kind.equals("goal") ? "activity for " + what.get("goal").getAsString() : "upkeep activity")
                    + " \"" + name + "\"" + daysText(what)));
            return null;
        }

        String editActivity(JsonObject x, List<Op> out) {
            JsonObject a = activity(ProposalValidator.str(x, "activity"));
            if (a == null) return "edit_activity names " + ProposalValidator.str(x, "activity") + ", which is not in Chronos";
            if (bool(a, "archived")) return ProposalValidator.str(a, "name") + " is archived: restore it first";
            String name = ProposalValidator.str(a, "name");
            JsonObject what = new JsonObject();
            what.addProperty("type", "edit_activity");
            what.addProperty("activity", name);
            String kind = ProposalValidator.str(a, "kind");
            String failure = days(x, what, false);
            if (failure == null) failure = windows(x, what);
            if (failure == null) failure = blocks(x, what, kind);
            if (failure != null) return name + ": " + failure;
            if (what.size() <= 2) return "edit_activity for " + name + " changes nothing";
            List<String> parts = new ArrayList<>();
            if (what.has("days")) parts.add("days " + daysText(what).trim() + " (now " + dayNames(a.getAsJsonArray("days")) + ")");
            if (what.has("windows")) parts.add("windows " + what.get("windows").toString().replace("\"", ""));
            if (what.has("minutes")) parts.add(what.get("minutes").getAsInt() + " min");
            if (what.has("min_block") || what.has("max_block"))
                parts.add("blocks " + (what.has("min_block") ? what.get("min_block").getAsInt() : "…") + "–"
                    + (what.has("max_block") ? what.get("max_block").getAsInt() : "…") + " min");
            out.add(Op.chronos("edit_activity", name, what, Map.of(), Map.of(), "Chronos: " + name + " — " + String.join(", ", parts)));
            return null;
        }

        String archive(JsonObject x, List<Op> out, boolean archive) {
            JsonObject a = activity(ProposalValidator.str(x, "activity"));
            if (a == null) return (archive ? "archive_activity" : "restore_activity") + " names "
                + ProposalValidator.str(x, "activity") + ", which is not in Chronos";
            String name = ProposalValidator.str(a, "name");
            if (archive == bool(a, "archived")) return name + (archive ? " is archived already" : " is not archived");
            if (archive && "goal".equals(ProposalValidator.str(a, "kind"))) {
                // The last activity of a goal with work left would strand the goal.
                String goal = ProposalValidator.str(a, "goal");
                int others = 0;
                for (JsonObject b : list(chronos, "activities"))
                    if (!bool(b, "archived") && goal != null && goal.equals(ProposalValidator.str(b, "goal"))
                        && !norm(name).equals(norm(ProposalValidator.str(b, "name")))) others++;
                JsonObject gl = goal(goal);
                Double left = gl == null ? null : remaining(gl);
                if (others == 0 && left != null && left > 0)
                    return name + " is the only activity of " + goal + ", which still has work left: edit the goal first";
            }
            JsonObject what = new JsonObject();
            what.addProperty("type", archive ? "archive_activity" : "restore_activity");
            what.addProperty("activity", name);
            out.add(Op.chronos(archive ? "archive_activity" : "restore_activity", name, what, Map.of(), Map.of(),
                archive ? "Chronos: " + name + " is archived — no longer planned; its log stays"
                    : "Chronos: " + name + " is planned again"));
            return null;
        }

        String editGoal(JsonObject x, List<Op> out) {
            JsonObject gl = goal(ProposalValidator.str(x, "goal"));
            if (gl == null) return "edit_goal names " + ProposalValidator.str(x, "goal") + ", which is not in Chronos";
            String name = ProposalValidator.str(gl, "name");
            boolean closed = "CLOSED".equals(ProposalValidator.str(gl, "mode"));
            JsonObject what = new JsonObject();
            what.addProperty("type", "edit_goal");
            what.addProperty("goal", name);
            Map<String, Double> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            List<String> parts = new ArrayList<>();
            LocalDate d = ProposalValidator.date(x, "deadline");
            if (d != null) {
                if (!closed) return name + " is ongoing and has no deadline";
                if (d.isBefore(today)) return name + ": the new deadline has passed";
                what.addProperty("deadline", d.toString());
                LocalDate was = ProposalValidator.date(gl, "deadline");
                if (was != null) before.put("deadline", (double) was.toEpochDay());
                after.put("deadline", (double) d.toEpochDay());
                parts.add("deadline " + d + (was == null ? "" : " (was " + was + ")"));
            }
            Double total = ProposalValidator.num(x, "total_units");
            if (total != null) {
                if (!closed) return name + " is ongoing and has no total";
                Double done = ProposalValidator.num(gl, "done_units");
                if (total <= 0 || (done != null && total < done)) return name + ": total_units must be above what is already done";
                what.addProperty("total_units", total);
                num(gl, "total_units", before);
                after.put("total_units", total);
                parts.add("total " + trim(total) + " " + String.valueOf(ProposalValidator.str(gl, "unit")));
            }
            Double floor = ProposalValidator.num(x, "weekly_floor_h");
            if (floor != null) {
                if (floor < 0 || floor > 60) return name + ": weekly_floor_h must be between 0 and 60";
                what.addProperty("weekly_floor_h", floor);
                num(gl, "weekly_floor_h", before);
                after.put("weekly_floor_h", floor);
                parts.add("at least " + trim(floor) + " h a week");
            }
            Double cap = ProposalValidator.num(x, "max_per_day_h");
            if (cap != null) {
                if (cap < 0 || cap > 16) return name + ": max_per_day_h must be between 0 and 16";
                what.addProperty("max_per_day_h", cap);
                num(gl, "max_per_day_h", before);
                after.put("max_per_day_h", cap);
                parts.add("at most " + trim(cap) + " h a day");
            }
            if (parts.isEmpty()) return "edit_goal for " + name + " changes nothing";
            out.add(Op.chronos("edit_goal", name, what, before, after, "Chronos: " + name + " — " + String.join(", ", parts)));
            return null;
        }

        // ------------------------------------------------------------ levers

        double lever(String key, Map<String, Double> newLevers) {
            if (newLevers.containsKey(key)) return newLevers.get(key);
            if (levers.containsKey(key)) return levers.get(key);
            JsonObject l = chronos.has("levers") ? chronos.getAsJsonObject("levers") : new JsonObject();
            Double v = ProposalValidator.num(l, key);
            return v == null ? Double.NaN : v;
        }

        String pressure(JsonObject x, List<Op> out, Map<String, Double> newLevers) {
            JsonObject what = new JsonObject();
            what.addProperty("type", "pressure");
            Map<String, Double> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            List<String> parts = new ArrayList<>();
            for (String k : new String[]{"slack_ratio", "f_safe", "f_safe_p"}) {
                Double v = ProposalValidator.num(x, k);
                if (v == null) continue;
                double now = lever(k, newLevers);
                double lo = k.equals("slack_ratio") ? 0.05 : 0.2, hi = k.equals("slack_ratio") ? 0.5 : 0.95;
                if (v < lo || v > hi) return k + " must be between " + lo + " and " + hi;
                if (!Double.isNaN(now) && Math.abs(v - now) > LEVER_STEP + 1e-9)
                    return k + " may move at most " + LEVER_STEP + " in one proposal (now " + trim(now) + ")";
                what.addProperty(k, v);
                if (!Double.isNaN(now)) before.put(k, now);
                after.put(k, v);
                newLevers.put(k, v);
                parts.add(switch (k) {
                    case "slack_ratio" -> "share of free time left unscheduled " + pct(now) + " → " + pct(v);
                    case "f_safe" -> "mental tiredness line " + trim2(now) + " → " + trim2(v);
                    default -> "physical tiredness line " + trim2(now) + " → " + trim2(v);
                });
            }
            if (parts.isEmpty()) return "pressure changes nothing";
            out.add(Op.chronos("pressure", "week", what, before, after, "Chronos: " + String.join("; ", parts)));
            return null;
        }

        String sleep(JsonObject x, List<Op> out, Map<String, Double> newLevers) {
            Integer bed = clock(ProposalValidator.str(x, "bed")), wake = clock(ProposalValidator.str(x, "wake"));
            if (bed == null && wake == null) return "sleep needs bed or wake as HH:mm";
            double bedNow = lever("bed_min", newLevers), wakeNow = lever("wake_min", newLevers);
            JsonObject l = chronos.has("levers") ? chronos.getAsJsonObject("levers") : new JsonObject();
            if (Double.isNaN(bedNow)) { Integer b = clock(ProposalValidator.str(l, "bed")); bedNow = b == null ? Double.NaN : b; }
            if (Double.isNaN(wakeNow)) { Integer w = clock(ProposalValidator.str(l, "wake")); wakeNow = w == null ? Double.NaN : w; }
            int newBed = bed != null ? bed : (int) bedNow, newWake = wake != null ? wake : (int) wakeNow;
            if (Double.isNaN(bedNow) || Double.isNaN(wakeNow)) return "Chronos has no sleep times to move";
            double shift = maxShift();
            if (circular(newBed, bedNow) > shift + 1e-9 || circular(newWake, wakeNow) > shift + 1e-9)
                return "bed and wake may each move at most " + (int) shift + " minutes in one proposal";
            int asleep = Math.floorMod(newWake - newBed, 1440);
            if (asleep < 360 || asleep > 600) return "that leaves " + asleep / 60 + " h " + asleep % 60 + " min asleep; it must be 6 to 10 hours";
            JsonObject what = new JsonObject();
            what.addProperty("type", "sleep");
            what.addProperty("bed", hhmm(newBed));
            what.addProperty("wake", hhmm(newWake));
            newLevers.put("bed_min", (double) newBed);
            newLevers.put("wake_min", (double) newWake);
            Map<String, Double> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            before.put("bed_min", bedNow); before.put("wake_min", wakeNow);
            after.put("bed_min", (double) newBed); after.put("wake_min", (double) newWake);
            out.add(Op.chronos("sleep", "night", what, before, after, "Chronos: sleep " + hhmm((int) bedNow) + "–"
                + hhmm((int) wakeNow) + " → " + hhmm(newBed) + "–" + hhmm(newWake)));
            return null;
        }

        double maxShift() {
            var p = g.params.get("crew.max_sleep_shift_min");
            return p == null || p.get("value") == null ? 30 : p.get("value");
        }

        String restDay(JsonObject x, List<Op> out) {
            LocalDate d = ProposalValidator.date(x, "date");
            if (d == null) return "rest_day needs a date (YYYY-MM-DD)";
            if (d.isBefore(today) || d.isAfter(today.plusDays(REST_AHEAD_DAYS)))
                return "a rest day is chosen within the next " + REST_AHEAD_DAYS + " days";
            boolean rest = !x.has("rest") || !x.get("rest").isJsonPrimitive() || x.get("rest").getAsBoolean();
            boolean already = ProposalValidator.strings(chronos, "rest_days").contains(d.toString());
            if (rest == already) return d + (rest ? " is a rest day already" : " is not a rest day");
            JsonObject what = new JsonObject();
            what.addProperty("type", "rest_day");
            what.addProperty("date", d.toString());
            what.addProperty("rest", rest);
            out.add(Op.chronos("rest_day", d.toString(), what, Map.of(), Map.of(),
                rest ? "Chronos: " + d + " becomes a rest day — no goal work" : "Chronos: " + d + " is a working day again"));
            return null;
        }

        // ------------------------------------------------------- the fields

        String days(JsonObject x, JsonObject what, boolean required) {
            if (!x.has("days")) return required && !what.has("like") ? "days are missing" : null;
            if (!x.get("days").isJsonArray()) return "days must be a list of 0 (Saturday) to 6 (Friday)";
            JsonArray out = new JsonArray();
            java.util.TreeSet<Integer> seen = new java.util.TreeSet<>();
            for (JsonElement e : x.getAsJsonArray("days")) {
                Integer d = dayOf(e);
                if (d == null) return "days must be numbers 0 (Saturday) to 6 (Friday)";
                if (d < 0 || d > 6) return "days must be numbers 0 (Saturday) to 6 (Friday)";
                seen.add(d);
            }
            if (seen.isEmpty()) return "days are empty: archive the activity instead";
            seen.forEach(out::add);
            what.add("days", out);
            return null;
        }

        String windows(JsonObject x, JsonObject what) {
            if (!x.has("windows")) return null;
            if (!x.get("windows").isJsonArray()) return "windows must be a list of {from, to}";
            JsonArray out = new JsonArray();
            for (JsonElement e : x.getAsJsonArray("windows")) {
                if (!e.isJsonObject()) return "a window is not {from, to}";
                Integer a = clock(ProposalValidator.str(e.getAsJsonObject(), "from")), b = clock(ProposalValidator.str(e.getAsJsonObject(), "to"));
                if (a == null || b == null) return "window times must be HH:mm";
                if (b <= a) return "a window ends before it starts";
                JsonObject w = new JsonObject();
                w.addProperty("from", hhmm(a));
                w.addProperty("to", hhmm(b));
                out.add(w);
            }
            if (!out.isEmpty()) what.add("windows", out);
            return null;
        }

        String blocks(JsonObject x, JsonObject what, String kind) {
            Double minutes = ProposalValidator.num(x, "minutes");
            if (minutes != null) {
                if (minutes < 5 || minutes > 300) return "minutes must be between 5 and 300";
                what.addProperty("minutes", (int) Math.round(minutes));
            }
            Double lo = ProposalValidator.num(x, "min_block"), hi = ProposalValidator.num(x, "max_block");
            if (lo != null && (lo < 10 || lo > 240)) return "min_block must be between 10 and 240 minutes";
            if (hi != null && (hi < 10 || hi > 300)) return "max_block must be between 10 and 300 minutes";
            if (lo != null && hi != null && hi < lo) return "max_block is below min_block";
            if (lo != null) what.addProperty("min_block", (int) Math.round(lo));
            if (hi != null) what.addProperty("max_block", (int) Math.round(hi));
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    static List<JsonObject> list(JsonObject o, String k) {
        List<JsonObject> out = new ArrayList<>();
        if (o != null && o.has(k) && o.get(k).isJsonArray())
            for (JsonElement e : o.getAsJsonArray(k)) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) return false;
        if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
        return e.getAsJsonPrimitive().isString() && e.getAsString().trim().equalsIgnoreCase("true");
    }

    static final String[] DAY_NAMES = {"sat", "sun", "mon", "tue", "wed", "thu", "fri"};

    /** A weekday 0 (Saturday) to 6 (Friday), also as "3" or by its English name ("Monday"); null otherwise. */
    static Integer dayOf(JsonElement e) {
        if (!e.isJsonPrimitive()) return null;
        if (e.getAsJsonPrimitive().isNumber()) return e.getAsInt();
        String s = ProposalValidator.latinDigits(e.getAsString()).trim().toLowerCase(Locale.ROOT);
        if (s.matches("\\d")) return Integer.parseInt(s);
        for (int i = 0; i < 7; i++) if (s.startsWith(DAY_NAMES[i])) return i;
        return null;
    }

    static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }

    static Double remaining(JsonObject goal) {
        if (!"CLOSED".equals(ProposalValidator.str(goal, "mode"))) return null;
        Double t = ProposalValidator.num(goal, "total_units"), d = ProposalValidator.num(goal, "done_units");
        return t == null ? null : t - (d == null ? 0 : d);
    }

    static void num(JsonObject o, String k, Map<String, Double> into) {
        Double v = ProposalValidator.num(o, k);
        if (v != null) into.put(k, v);
    }

    /** Minutes past midnight of "HH:mm", or null. */
    public static Integer clock(String s) {
        if (s == null || !s.trim().matches("\\d{1,2}:\\d{2}")) return null;
        String[] p = s.trim().split(":");
        int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
        if (h > 24 || m > 59 || (h == 24 && m > 0)) return null;
        return h * 60 + m;
    }

    public static String hhmm(int minutes) {
        int m = Math.floorMod(minutes, 1440);
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60);
    }

    static double circular(double a, double b) {
        double d = Math.abs(a - b) % 1440;
        return Math.min(d, 1440 - d);
    }

    static final String[] DAY = {"Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri"};

    static String dayNames(JsonArray days) {
        if (days == null) return "—";
        List<String> out = new ArrayList<>();
        for (JsonElement e : days) if (e.isJsonPrimitive()) out.add(DAY[Math.max(0, Math.min(6, e.getAsInt()))]);
        return String.join(" ", out);
    }

    static String daysText(JsonObject what) {
        return what.has("days") ? " on " + dayNames(what.getAsJsonArray("days")) : "";
    }

    static String trim(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r);
    }

    static String trim2(double v) { return Double.isNaN(v) ? "?" : String.format(Locale.ROOT, "%.2f", v); }

    static String pct(double v) { return Double.isNaN(v) ? "?" : Math.round(v * 100) + "%"; }

    /** The change set in words, for a model to see what was proposed before. */
    public static String describe(List<Op> ops, List<Group> groups) {
        StringBuilder b = new StringBuilder();
        for (Group gr : groups) {
            b.append("- ").append(gr.title());
            if (gr.why() != null) b.append(" — ").append(gr.why());
            b.append('\n');
            for (int i : gr.ops()) if (i < ops.size()) b.append("    · ").append(ops.get(i).note()).append('\n');
        }
        return b.toString();
    }

    /** The same as JSON actions the model can copy into a revision: what each change does, by title. */
    public static JsonArray outline(List<Op> ops, List<Group> groups) {
        JsonArray out = new JsonArray();
        for (Group gr : groups) {
            JsonObject o = new JsonObject();
            o.addProperty("title", gr.title());
            if (gr.why() != null) o.addProperty("why", gr.why());
            JsonArray parts = new JsonArray();
            for (int i : gr.ops()) if (i < ops.size()) parts.add(ops.get(i).note());
            o.add("does", parts);
            out.add(o);
        }
        return out;
    }
}
