package com.chronos.navigator.llm;

import com.chronos.navigator.forecast.Bands;
import com.chronos.navigator.forecast.Guard;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.NodeType;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Priority;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Navigator as a crew (your decision of 13 Oct 2026): several models, each
 * with its own job, and one coordinating them, with the app's own measurement
 * as the last word on odds and tiredness.
 *
 * <pre>
 * ANALYST      reads the summary of your log and says what it shows
 * ARCHITECT    turns what you tell it into tasks, dependencies and planner changes
 * ESTIMATOR    checks the hours of new tasks against your measured speed
 * TUNER        sets the levers of the week: pressure, sleep, rest days, activities
 * CRITIC       looks for what is wrong or risky in a proposal and its measured effect
 * COORDINATOR  decides what reaches you, answers the critic, may ask once for a revision
 * </pre>
 *
 * Every specialist sees the same brief: the goals with their odds and 80%
 * bands, their priorities and floors, Chronos as it is, and a summary of the
 * log the app computes (never the raw log). Answers are proposals; nothing
 * changes until you approve.
 */
public final class Crew {
    private Crew() {}

    public enum Role { COORDINATOR, ARCHITECT, ANALYST, ESTIMATOR, TUNER, CRITIC }

    public static final String TASK = "CREW";

    /** The task string a role call is recorded under. */
    public static String task(String jobId, Role role) { return TASK + "|" + jobId + "|" + role.name(); }

    // ------------------------------------------------------------- the brief

    /**
     * @param chronos  the planner as {@link ChangeSet#CHRONOS_SHAPE} describes it
     * @param digest   the app's summary of the log, or null
     * @param language the language the student reads ("fa", "en")
     */
    public static JsonObject brief(Graph g, LocalDate today, Bands.Result bands, JsonObject chronos, JsonObject digest,
                                   String language) {
        JsonObject s = EstimatePrior.context(g, today);
        s.remove("dated_nodes");
        s.addProperty("student_language", language);
        Set<String> hidden = EstimatePrior.privateNodes(g);
        JsonArray goals = new JsonArray();
        for (Node n : g.nodes.values()) {
            if (hidden.contains(n.id) || n.deadline == null || n.deadline.isBefore(today) || Replan.done(n)) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", n.id);
            o.addProperty("title", n.title);
            o.addProperty("type", String.valueOf(n.type));
            o.addProperty("owner", String.valueOf(n.owner));
            if (n.phase != null) o.addProperty("phase", n.phase);
            if (n.earliest != null) o.addProperty("earliest", n.earliest.toString());
            o.addProperty("deadline", n.deadline.toString());
            if (n.hard || n.hardDeadline) o.addProperty("hard", true);
            int pr = Priority.of(n);
            o.addProperty("priority", Priority.name(pr) + (Priority.chosen(n) ? "" : " (default)"));
            double floor = Priority.floor(g, pr);
            if (floor > 0) o.addProperty("floor_on_low_end", round(floor));
            if (n.effortP50 != null && !n.expanded) o.addProperty("effort_p50_h", round(n.effortP50));
            if (n.unitsTotal != null) o.addProperty("units", round(n.unitsTotal) + " " + (n.unitName == null ? "" : n.unitName));
            if (n.expanded) o.addProperty("broken_down", true);
            JsonArray deps = new JsonArray();
            for (String d : ProposalValidator.strings(n.raw, "depends_on")) if (!hidden.contains(d)) deps.add(d);
            if (!deps.isEmpty()) o.add("depends_on", deps);
            if (!n.activities.isEmpty()) {
                JsonArray a = new JsonArray();
                n.activities.forEach(a::add);
                o.add("planner_activities", a);
            }
            Bands.Band b = bands == null ? null : bands.nodes().get(n.id);
            if (b != null) o.add("odds", band(b));
            goals.add(o);
        }
        s.add("goals", goals);
        JsonArray streams = new JsonArray();
        for (Node n : g.nodes.values()) {
            if (hidden.contains(n.id) || n.type != NodeType.STREAM || Replan.done(n)) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", n.id);
            o.addProperty("title", n.title);
            if (n.weeklyFloorH != null) o.addProperty("weekly_floor_h", n.weeklyFloorH);
            streams.add(o);
        }
        s.add("ongoing", streams);
        if (chronos != null) s.add("chronos", chronos);
        if (digest != null) s.add("log", digest);
        return s;
    }

    static JsonObject band(Bands.Band b) {
        JsonObject o = new JsonObject();
        o.addProperty("p", round(b.p()));
        o.addProperty("low", round(b.low()));
        o.addProperty("high", round(b.high()));
        return o;
    }

    /** The guard's verdict as the critic and the coordinator read it. */
    public static JsonArray odds(Guard.Verdict v) {
        JsonArray out = new JsonArray();
        for (Guard.Move m : v.moves()) {
            JsonObject o = new JsonObject();
            o.addProperty("goal", m.nodeId());
            o.addProperty("title", m.title());
            o.addProperty("priority", Priority.name(m.priority()));
            if (m.floor() > 0) o.addProperty("floor", round(m.floor()));
            if (!Double.isNaN(m.before().p())) o.add("before", band(m.before()));
            else o.addProperty("new", true);
            o.add("after", band(m.after()));
            if (m.breach()) o.addProperty("breaks_floor", true);
            out.add(o);
        }
        return out;
    }

    // ---------------------------------------------------------------- prompts

    static final String COMMON = String.join("\n",
        "You are one member of a crew of models that steers a student's planning system. The crew's levels:",
        "- The Navigator (you and the others) sets the high level: which goals and tasks exist and how much they matter, which activities the planner has and on which days, how hard the week pushes, when the student sleeps, which days are rest days.",
        "- Chronos, the planner, arranges each day inside that: blocks by energy, mental and physical tiredness and deadlines. It also keeps the log.",
        "- The app measures every proposal itself: the odds of each deadline (with an 80% band from what the log has not pinned down yet) and a simulated week. Never state odds of your own.",
        "Rules:",
        "- Everything you propose waits for the student's approval.",
        "- More work is not automatically better: every hour comes from somewhere. Goals have priorities; critical and important ones have a floor on the low end of their odds that a change must not break unless it also says what makes up for it.",
        "- Health is not traded for odds: enough sleep, tiredness kept near the safe lines, rest when it accumulates.",
        "- Tasks assigned by an advisor or a teacher are fine to plan. Never propose anything that gathers private information about a specific person or arranges physical proximity to them; fill `consent_self_check.targets_other_person` truthfully.",
        "- Texts meant for the student (message, why, questions, findings) are in `student_language` of the brief. Names of new tasks and activities follow the language of the existing names in `chronos`.",
        "- Finish with exactly one JSON object in the requested shape and nothing after it. Dates are YYYY-MM-DD.",
        "");

    static final String ARCHITECT = COMMON + String.join("\n",
        "Your job: the ARCHITECT. The student tells you about their situation (a new task from their advisor, a change of plans, something they want).",
        "Turn it into a change set: new tasks with honest effort ranges (p50 and a p90 they would be surprised to exceed), their deadlines and dependencies, their place in the planner, priorities, and what makes room for them (pauses, extra hours, the student's own deadlines, activities).",
        "Group actions that only make sense together into one change; offer alternatives as separate changes so the student can choose.",
        "If something essential is missing (a deadline, the size of the work), ask in `questions`; propose only what is safe without it.",
        "",
        ChangeSet.CHRONOS_SHAPE, "", ChangeSet.VOCABULARY);

    /** A question about the goal graph itself (your request of 22 Oct 2026): one call, explained, corrections proposed. */
    static final String GRAPH = COMMON + String.join("\n",
        "Your job: you explain the student's GOAL GRAPH and correct it with them. `graph` lists every node: what it is, what it waits for",
        "(depends_on: all of them first; any_of: one of each group; soft: helps but is not required), its hours (p50/p90), its chance of",
        "success (gates and milestones), and `odds`: the app's chance it is reached by its deadline, with an 80% band.",
        "The odds come from the app's own simulation, not from you: many futures of the student's real working speed and hours a week",
        "(`log.speed` and `log.adherence` when present) against the hours each node needs, in the order the links allow, times the",
        "chances of success along the way. So a low chance is explained by what feeds it: too many hours for the time left, a long chain",
        "of links before it, a low chance upstream, or few hours a week given to it. Name the one or two that matter most, with numbers.",
        "Answer the student's question plainly and briefly in `message`. When they ask you to change something, or a link, an estimate or",
        "a chance is clearly wrong from what they told you, propose the correction in `changes` (it waits for their approval, and the app",
        "measures its effect). Do not change anything they did not ask about unless it is plainly an error, and then say why.",
        "Changing nothing is the usual answer to a question: leave `changes` empty.",
        "",
        ChangeSet.CHRONOS_SHAPE, "", ChangeSet.VOCABULARY);

    static final String TUNER = COMMON + String.join("\n",
        "Your job: the TUNER. You set the levers of the coming week: pressure (slack, tiredness lines), the sleep window, rest days, and which activities exist and on which days, upkeep included.",
        "You get the analyst's findings and `candidates`: lever moves the app has already simulated, each with its measured effect on the week and the goals' odds. Prefer moves the measurements support; you may combine them, in small steps.",
        "Explain each change from a finding or a measurement. Changing nothing is a valid answer when the week is right.",
        "",
        ChangeSet.CHRONOS_SHAPE, "", ChangeSet.VOCABULARY);

    static final String ANALYST = COMMON + String.join("\n",
        "Your job: the ANALYST. You read `log`, the app's summary of the student's own log: hours planned and worked per activity, measured speed against plan, mental and physical tiredness by weekday and activity, sleep, rest days, how the mornings felt.",
        "Report what matters for planning: patterns, trends, risks, what is working. Every finding cites its evidence from the summary; never invent numbers. Say how sure you are.",
        "Shape:",
        "{\"findings\":[{\"text\":\"…\",\"evidence\":\"…\",\"about\":[\"activity or goal\"],\"confidence\":\"low|medium|high\",\"bears_on\":\"pressure|sleep|rest|activities|estimates|none\"}],"
            + "\"summary\":\"…\",\"consent_self_check\":{\"targets_other_person\":false}}");

    static final String ESTIMATOR = COMMON + String.join("\n",
        "Your job: the ESTIMATOR. You check the hours proposed for new tasks against the student's measured speed (`log.speed`: actual time against planned, per kind of work) and what such work usually takes.",
        "For each task give the p50 and p90 hours you believe and why in one sentence. Agreeing is fine.",
        "Shape:",
        "{\"estimates\":[{\"node\":\"T_…\",\"p50_hours\":0,\"p90_hours\":0,\"why\":\"…\"}],\"summary\":\"…\",\"consent_self_check\":{\"targets_other_person\":false}}");

    static final String CRITIC = COMMON + String.join("\n",
        "Your job: the CRITIC. You review a change set another model proposed, with the effect the app measured.",
        "Find what is wrong or risky: assumptions without support, hours that disagree with the measured speed, priorities ignored, floors broken without anything making up for it, sleep or tiredness, changes that conflict, what the student said that was missed.",
        "Be specific and short; do not rewrite the set. Severity: blocker (must not be approved as it is), major, minor.",
        "Shape:",
        "{\"objections\":[{\"severity\":\"blocker|major|minor\",\"change\":\"its title\",\"text\":\"…\"}],\"verdict\":\"approve|revise|reject\",\"summary\":\"…\","
            + "\"consent_self_check\":{\"targets_other_person\":false}}");

    static final String COORDINATOR = COMMON + String.join("\n",
        "Your job: the COORDINATOR. Specialists have proposed and reviewed; you decide what reaches the student.",
        "You get the job, the brief, each specialist's output and the app's measurements. Produce the final change set: keep, drop or adjust the changes (you may also take an estimator's hours). Answer every objection: accepted or not, and why.",
        "If a blocker cannot be fixed by adjusting, you may instead ask a specialist once for a revision: give `revise` and leave `changes` empty.",
        "`message` speaks to the student in a few sentences: what you propose, the trade-off, and what you need from them.",
        "A change you keep exactly as proposed: put its title in `keep` (the app takes it as proposed and measured). A change you adjust: write it in `changes` with all its actions.",
        "",
        ChangeSet.CHRONOS_SHAPE, "", ChangeSet.VOCABULARY, "",
        "Shape: as below, plus \"keep\":[\"title of a proposed change\"], \"responses\":[{\"objection\":\"…\",\"accepted\":true,\"why\":\"…\"}] and \"revise\":null or {\"role\":\"architect|tuner\",\"instructions\":\"…\"}");

    static final String ONE_PASS = COMMON + String.join("\n",
        "Your job: the WHOLE CREW in one pass, because the student is asking you in their own chat app rather than through the app's models.",
        "1. Understand: read the student's words (or, for a weekly review, the `log` summary and the `candidates` the app simulated).",
        "2. Propose like an architect or week tuner: tasks with honest effort ranges, dependencies, planner changes, levers.",
        "3. Check yourself like a critic: hours against the measured speed in `log.speed`, priorities and their floors, sleep and tiredness, conflicts, what the student said that you might miss.",
        "4. Decide like a coordinator: keep only what survives your check; offer alternatives as separate changes.",
        "The app validates and measures your answer after you give it (odds with their bands, the simulated week), and the student approves.",
        "Use only the actions below; names of goals and activities exactly as `chronos` writes them.",
        "",
        ChangeSet.CHRONOS_SHAPE, "", ChangeSet.VOCABULARY);

    /**
     * The whole question as one text, for the student to paste into a chat app of their own (Claude,
     * ChatGPT, Gemini): instructions, then the brief, then the job. Chat apps have no system field.
     *
     * @param candidates for a weekly review, the lever moves the app simulated; null otherwise
     */
    public static String relay(JsonObject brief, List<Turn> earlier, String message, JsonArray candidates) {
        StringBuilder b = new StringBuilder();
        b.append("=== Instructions ===\n").append(ONE_PASS).append("\n\n=== Data ===\n").append(EstimatePrior.pretty(brief)).append("\n\n");
        if (candidates != null) {
            JsonObject in = new JsonObject();
            in.add("candidates", candidates);
            b.append(pretty(in)).append("\n\nThis is the weekly review: set the coming week's levers.\n\n");
        } else {
            b.append(chat(earlier)).append("The student says now:\n").append(message).append("\n\n");
        }
        b.append("=== Answer ===\nReply with your thinking if you like, then end with exactly one JSON object in this shape "
            + "(the student will paste your whole reply back into the app):\n").append(ChangeSet.SHAPE);
        return b.toString();
    }

    /** The question about the graph for your own chat app: the same instructions and graph, one complete text. */
    public static String relayGraph(JsonObject brief, JsonArray graph, List<Turn> earlier, String message) {
        JsonObject b = brief.deepCopy();
        b.add("graph", graph);
        return "=== Instructions ===\n" + GRAPH + "\n\n=== Data ===\n" + EstimatePrior.pretty(b) + "\n\n"
            + chat(earlier) + "The student asks about the graph:\n" + message + "\n\n"
            + "=== Answer ===\nAnswer in words if you like, then end with exactly one JSON object in this shape "
            + "(the student will paste your whole reply back into the app):\n" + ChangeSet.SHAPE;
    }

    static String system(Role r) {
        return switch (r) {
            case ARCHITECT -> ARCHITECT;
            case TUNER -> TUNER;
            case ANALYST -> ANALYST;
            case ESTIMATOR -> ESTIMATOR;
            case CRITIC -> CRITIC;
            case COORDINATOR -> COORDINATOR;
        };
    }

    // --------------------------------------------------------------- requests

    /** One turn of your conversation with the Navigator. */
    public record Turn(String who, String text) {}

    private static LlmRequest req(Role role, String jobId, JsonObject brief, String body) {
        JsonObject target = new JsonObject();
        target.addProperty("kind", "CREW");
        target.addProperty("subject", jobId);
        target.addProperty("role", role.name());
        return new LlmRequest(TASK, target, system(role), EstimatePrior.pretty(brief) + "\n\n" + body, false);
    }

    static String chat(List<Turn> earlier) {
        if (earlier == null || earlier.isEmpty()) return "";
        StringBuilder b = new StringBuilder("The conversation so far:\n");
        for (Turn t : earlier.subList(Math.max(0, earlier.size() - 8), earlier.size()))
            b.append(t.who().equals("you") ? "Student: " : "Navigator: ").append(t.text()).append('\n');
        return b.append('\n').toString();
    }

    public static LlmRequest architect(String jobId, JsonObject brief, List<Turn> earlier, String message,
                                       String revision) {
        String body = chat(earlier) + "The student says now:\n" + message + "\n\n"
            + (revision == null ? "" : revision + "\n\n")
            + "Shape:\n" + ChangeSet.SHAPE;
        return req(Role.ARCHITECT, jobId, brief, body);
    }

    /** The question about the graph, with the whole graph in front of the model. Recorded as the coordinator's call. */
    public static LlmRequest graphQuestion(String jobId, JsonObject brief, JsonArray graph, List<Turn> earlier, String message) {
        JsonObject b = brief.deepCopy();
        b.add("graph", graph);
        JsonObject target = new JsonObject();
        target.addProperty("kind", "CREW");
        target.addProperty("subject", jobId);
        target.addProperty("role", Role.COORDINATOR.name());
        String body = chat(earlier) + "The student asks about the graph:\n" + message + "\n\nShape:\n" + ChangeSet.SHAPE;
        return new LlmRequest(TASK, target, GRAPH, EstimatePrior.pretty(b) + "\n\n" + body, false);
    }

    /**
     * Every node the model may see, with what explains its odds: links of each kind, hours,
     * the chance of success, the weekly floor, and the app's band.
     */
    public static JsonArray graph(Graph g, Bands.Result bands) {
        Set<String> hidden = EstimatePrior.privateNodes(g);
        JsonArray out = new JsonArray();
        for (Node n : g.nodes.values()) {
            if (hidden.contains(n.id)) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", n.id);
            o.addProperty("title", n.title);
            o.addProperty("type", String.valueOf(n.type));
            o.addProperty("owner", String.valueOf(n.owner));
            if (n.status != null) o.addProperty("status", n.status);
            if (n.phase != null) o.addProperty("phase", n.phase);
            if (n.earliest != null) o.addProperty("earliest", n.earliest.toString());
            if (n.deadline != null) o.addProperty("deadline", n.deadline.toString());
            if (n.hard || n.hardDeadline) o.addProperty("hard", true);
            JsonArray deps = new JsonArray();
            for (String d : ProposalValidator.strings(n.raw, "depends_on")) if (!hidden.contains(d)) deps.add(d);
            if (!deps.isEmpty()) o.add("depends_on", deps);
            if (n.raw.has("any_of")) o.add("any_of", n.raw.get("any_of").deepCopy());
            JsonArray soft = new JsonArray();
            for (String d : ProposalValidator.strings(n.raw, "soft_depends_on")) if (!hidden.contains(d)) soft.add(d);
            if (!soft.isEmpty()) o.add("soft", soft);
            if (n.expanded) o.addProperty("broken_down", true);
            else if (n.perPointP50 != null && n.levelFrom != null && n.levelTo != null) {
                double levels = Math.max(1, n.levelTo - n.levelFrom);
                o.addProperty("hours_p50", round(n.perPointP50 * levels));
                if (n.perPointP90 != null) o.addProperty("hours_p90", round(n.perPointP90 * levels));
            } else if (n.effortP50 != null) {
                o.addProperty("hours_p50", round(n.effortP50));
                if (n.effortP90 != null) o.addProperty("hours_p90", round(n.effortP90));
            }
            if (n.successA != null && n.successB != null && n.successA + n.successB > 0)
                o.addProperty("chance_of_success", round(n.successA / (n.successA + n.successB)));
            if (n.weeklyFloorH != null) o.addProperty("weekly_floor_h", round(n.weeklyFloorH));
            if (n.unitsTotal != null) o.addProperty("units", round(n.unitsTotal) + " " + (n.unitName == null ? "" : n.unitName)
                + (n.unitsDone != null ? ", " + round(n.unitsDone) + " done" : ""));
            Bands.Band b = bands == null ? null : bands.nodes().get(n.id);
            if (b != null) o.add("odds", band(b));
            out.add(o);
        }
        return out;
    }

    public static LlmRequest analyst(String jobId, JsonObject brief) {
        return req(Role.ANALYST, jobId, brief, "Read `log` and report your findings.");
    }

    /** @param candidates lever moves the app simulated, each with its measured effect */
    public static LlmRequest tuner(String jobId, JsonObject brief, JsonArray findings, JsonArray candidates,
                                   String reason, String revision) {
        JsonObject in = new JsonObject();
        in.addProperty("why_now", reason);
        in.add("analyst_findings", findings == null ? new JsonArray() : findings);
        in.add("candidates", candidates == null ? new JsonArray() : candidates);
        String body = pretty(in) + "\n\nSet the coming week's levers.\n\n"
            + (revision == null ? "" : revision + "\n\n") + "Shape:\n" + ChangeSet.SHAPE;
        return req(Role.TUNER, jobId, brief, body);
    }

    public static LlmRequest estimator(String jobId, JsonObject brief, JsonArray newTasks) {
        JsonObject in = new JsonObject();
        in.add("new_tasks", newTasks);
        return req(Role.ESTIMATOR, jobId, brief, pretty(in) + "\n\nCheck the hours of these tasks.");
    }

    /** @param proposal what was proposed ({@link ChangeSet#outline}), @param measured the app's measurement */
    public static LlmRequest critic(String jobId, JsonObject brief, String job, JsonArray proposal, JsonObject measured) {
        JsonObject in = new JsonObject();
        in.addProperty("job", job);
        in.add("proposal", proposal);
        in.add("measured_by_the_app", measured);
        return req(Role.CRITIC, jobId, brief, pretty(in) + "\n\nReview this proposal.");
    }

    /**
     * @param outputs each specialist's answer as read, by role name
     * @param mayRevise whether the one revision is still available
     */
    public static LlmRequest coordinator(String jobId, JsonObject brief, String job, JsonArray proposal,
                                         JsonObject measured, JsonObject outputs, boolean mayRevise) {
        JsonObject in = new JsonObject();
        in.addProperty("job", job);
        in.add("proposal", proposal);
        in.add("measured_by_the_app", measured);
        in.add("specialists", outputs);
        String body = pretty(in) + "\n\nDecide the final change set. List the changes you keep as proposed in `keep` by title; "
            + "write any change you adjust in `changes` with all its actions."
            + (mayRevise ? "" : " A revision is no longer available: decide.")
            + "\n\nShape:\n" + ChangeSet.SHAPE;
        return req(Role.COORDINATOR, jobId, brief, body);
    }

    static String pretty(JsonObject o) {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o);
    }

    // ---------------------------------------------------------------- answers

    public record Finding(String text, String evidence, List<String> about, String confidence, String bearsOn) {}

    public record Estimate(String node, double p50, double p90, String why) {}

    public record Objection(String severity, String change, String text) {
        public boolean blocker() { return "blocker".equalsIgnoreCase(severity); }
    }

    public record Review(List<Objection> objections, String verdict, String summary) {}

    public record Response(String objection, boolean accepted, String why) {}

    public record Revise(Role role, String instructions) {}

    public record Decision(ChangeSet.Parsed set, List<Response> responses, Revise revise) {}

    /** Null when the answer holds no JSON or says it targets another person. */
    static JsonObject answer(String text) {
        JsonObject o = ProposalValidator.extractJson(text);
        if (o == null) return null;
        JsonObject c = o.has("consent_self_check") && o.get("consent_self_check").isJsonObject()
            ? o.getAsJsonObject("consent_self_check") : null;
        JsonElement t = c == null ? null : c.has("targets_other_person") ? c.get("targets_other_person") : c.get("involves_other_person");
        if (t != null && t.isJsonPrimitive() && t.getAsJsonPrimitive().isBoolean() && t.getAsBoolean()) return null;
        return o;
    }

    public static List<Finding> findings(String text) {
        JsonObject o = answer(text);
        List<Finding> out = new ArrayList<>();
        if (o == null) return out;
        for (JsonObject f : ChangeSet.list(o, "findings")) {
            String t = ProposalValidator.str(f, "text");
            if (t == null || t.isBlank()) continue;
            out.add(new Finding(t.trim(), ProposalValidator.str(f, "evidence"), ProposalValidator.strings(f, "about"),
                ProposalValidator.str(f, "confidence"), ProposalValidator.str(f, "bears_on")));
            if (out.size() >= 8) break;
        }
        return out;
    }

    public static List<Estimate> estimates(String text) {
        JsonObject o = answer(text);
        List<Estimate> out = new ArrayList<>();
        if (o == null) return out;
        for (JsonObject e : ChangeSet.list(o, "estimates")) {
            String n = ProposalValidator.str(e, "node");
            Double a = ProposalValidator.num(e, "p50_hours"), b = ProposalValidator.num(e, "p90_hours");
            if (n == null || a == null || b == null || a <= 0 || b < a) continue;
            out.add(new Estimate(n.trim(), a, b, ProposalValidator.str(e, "why")));
        }
        return out;
    }

    public static Review review(String text) {
        JsonObject o = answer(text);
        if (o == null) return null;
        List<Objection> out = new ArrayList<>();
        for (JsonObject e : ChangeSet.list(o, "objections")) {
            String t = ProposalValidator.str(e, "text");
            if (t == null || t.isBlank()) continue;
            String sev = String.valueOf(ProposalValidator.str(e, "severity")).toLowerCase(Locale.ROOT);
            if (!sev.equals("blocker") && !sev.equals("major")) sev = "minor";
            out.add(new Objection(sev, ProposalValidator.str(e, "change"), t.trim()));
            if (out.size() >= 10) break;
        }
        return new Review(out, ProposalValidator.str(o, "verdict"), ProposalValidator.str(o, "summary"));
    }

    public static Decision decision(Graph g, LocalDate today, JsonObject chronos, String text, boolean mayRevise) {
        JsonObject o = ProposalValidator.extractJson(text);
        if (o == null) return new Decision(ChangeSet.read(g, today, chronos, text), List.of(), null);
        Revise revise = null;
        if (mayRevise && o.has("revise") && o.get("revise").isJsonObject()) {
            JsonObject r = o.getAsJsonObject("revise");
            String role = String.valueOf(ProposalValidator.str(r, "role")).toUpperCase(Locale.ROOT);
            String how = ProposalValidator.str(r, "instructions");
            if ((role.equals("ARCHITECT") || role.equals("TUNER")) && how != null && !how.isBlank())
                revise = new Revise(Role.valueOf(role), how.trim());
        }
        List<Response> responses = new ArrayList<>();
        for (JsonObject e : ChangeSet.list(o, "responses")) {
            String obj = ProposalValidator.str(e, "objection");
            if (obj == null) continue;
            boolean acc = e.has("accepted") && e.get("accepted").isJsonPrimitive() && e.get("accepted").getAsBoolean();
            responses.add(new Response(obj, acc, ProposalValidator.str(e, "why")));
        }
        return new Decision(ChangeSet.read(g, today, chronos, o), responses, revise);
    }

    /**
     * The coordinator's decision made whole: a change it keeps by title (in `keep`, or in `changes` without
     * usable actions) is taken from the proposal, already checked and measured; a decision that cannot be
     * read at all leaves the proposal as it was. Models often name what they keep instead of copying it.
     */
    public static ChangeSet.Parsed reconcile(ChangeSet.Parsed proposed, ChangeSet.Parsed decided, String text) {
        JsonObject raw = ProposalValidator.extractJson(text);
        if (raw == null || !decided.ok()) return proposed;
        List<String> wanted = new ArrayList<>(ProposalValidator.strings(raw, "keep"));
        for (JsonObject c : ChangeSet.list(raw, "changes")) {
            String t = ProposalValidator.str(c, "title");
            if (t != null) wanted.add(t);
        }
        List<Op> ops = new ArrayList<>(decided.ops());
        List<ChangeSet.Group> groups = new ArrayList<>(decided.groups());
        java.util.Set<String> have = new java.util.HashSet<>(), keys = new java.util.HashSet<>();
        for (ChangeSet.Group g : groups) have.add(ChangeSet.norm(g.title()));
        for (Op op : ops) keys.add(op.key());
        for (String w : wanted) {
            String t = ChangeSet.norm(w);
            if (have.contains(t)) continue;
            ChangeSet.Group g = null;
            for (ChangeSet.Group p : proposed.groups()) if (ChangeSet.norm(p.title()).equals(t)) g = p;
            if (g == null) continue;
            List<Op> mine = new ArrayList<>();
            for (int i : g.ops()) mine.add(proposed.ops().get(i));
            if (mine.stream().anyMatch(op -> keys.contains(op.key()))) continue;
            List<Integer> idx = new ArrayList<>();
            for (Op op : mine) { idx.add(ops.size()); ops.add(op); keys.add(op.key()); }
            groups.add(new ChangeSet.Group(g.title(), g.why(), idx));
            have.add(t);
        }
        List<ChangeSet.Rejected> rejected = new ArrayList<>();
        for (ChangeSet.Rejected r : decided.rejected()) if (!have.contains(ChangeSet.norm(r.change()))) rejected.add(r);
        return new ChangeSet.Parsed(null, decided.summary(), decided.rationale(), decided.message(), ops, groups,
            rejected, decided.questions());
    }

    /** The new tasks of a change set, for the estimator. */
    public static JsonArray newTasks(List<Op> ops) {
        JsonArray out = new JsonArray();
        for (Op op : ops) {
            if (!Op.NODE_ADD.equals(op.kind()) || op.node() == null) continue;
            JsonObject n = op.node(), t = new JsonObject();
            t.addProperty("node", n.get("id").getAsString());
            t.addProperty("title", n.get("title").getAsString());
            if (n.has("effort")) t.add("proposed", n.get("effort"));
            if (n.has("window")) t.add("window", n.get("window"));
            out.add(t);
        }
        return out;
    }

    /** A change set with the estimator's hours taken for the tasks it names. */
    public static List<Op> withEstimates(List<Op> ops, List<Estimate> est) {
        List<Op> out = new ArrayList<>();
        for (Op op : ops) {
            Estimate e = null;
            String id = op.node() == null ? null : op.node().has("id") ? op.node().get("id").getAsString()
                : op.node().has("node") ? op.node().get("node").getAsString() : null;
            if (id != null) for (Estimate x : est) if (x.node().equals(id)) e = x;
            if (e == null || !(Op.NODE_ADD.equals(op.kind()) || op.isChronos())) { out.add(op); continue; }
            JsonObject n = op.node().deepCopy();
            if (Op.NODE_ADD.equals(op.kind())) {
                JsonObject eff = new JsonObject();
                eff.addProperty("total_h_p50", e.p50());
                eff.addProperty("total_h_p90", e.p90());
                n.add("effort", eff);
            } else if (n.has("hours")) {
                n.addProperty("hours", Math.max(0.5, Math.round(e.p50() * 2) / 2.0));
            }
            out.add(new Op(op.key(), op.dist(), op.values(), op.before(), op.note(), op.discussOnly(), op.kind(), n));
        }
        return out;
    }

    static double round(double v) { return Math.round(v * 100) / 100.0; }
}
