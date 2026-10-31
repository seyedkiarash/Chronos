package com.chronos.navigator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.estimate.Estimator;
import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.forecast.Effects;
import com.chronos.navigator.forecast.Forecaster;
import com.chronos.navigator.forecast.InfoValue;
import com.chronos.navigator.llm.Expand;
import com.chronos.navigator.llm.LlmRequest;
import com.chronos.navigator.llm.Op;
import com.chronos.navigator.llm.ProposalValidator;
import com.chronos.navigator.llm.Replan;
import com.chronos.navigator.llm.Structure;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.GraphImport;
import com.chronos.navigator.model.GraphJson;
import com.chronos.navigator.model.Issue;
import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Overrides;
import com.chronos.navigator.model.Param;
import com.chronos.navigator.model.ParamRegistry;
import com.chronos.navigator.model.Requirements;
import com.chronos.navigator.trigger.Triggers;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stages 5 and 6: triggers, the replan and expansion answers, their checks, and what they change. */
public class TriggerLayerTest {

    static final LocalDate TODAY = ForecastTest.TODAY;
    static final String SELF = "\"consent_self_check\":{\"involves_other_person\":false,\"explanation\":\"no\"}";

    /** The answered real graph with extra parameters, as the stored graph would be rebuilt with them. */
    static Graph with(List<Param> extra) {
        Graph g = ForecastTest.answered();
        List<JsonObject> nodes = g.nodes.values().stream().map(n -> n.raw).toList();
        return rebuild(g, nodes, extra);
    }

    static Graph rebuild(Graph g, List<JsonObject> nodes, List<Param> extra) {
        List<JsonObject> regimes = g.regimes.stream().filter(r -> r.raw != null).map(r -> r.raw).toList();
        List<Param> params = new ArrayList<>(g.params.values());
        params.addAll(extra);
        Graph out = GraphJson.assemble(GraphJson.rest(g), nodes, regimes, params);
        ParamRegistry.seed(out, 1);
        for (Param p : Estimator.run(out, ForecastTest.inputs(), TODAY, 1).posteriors()) out.params.put(p.key, p);
        return out;
    }

    static Triggers.Night night(LocalDate day, Map<String, Double> p, Map<String, Double> low, String hash) {
        return new Triggers.Night(day, day.toEpochDay() * 86_400_000L, p, low, hash, null, Set.of());
    }

    // ---------------------------------------------------------------- triggers

    @Test
    public void probDropHasHysteresisAndACooldown() {
        Graph g = ForecastTest.answered();
        Map<String, Triggers.State> state = new HashMap<>();
        Map<String, Double> p = new HashMap<>(Map.of("T_READING", 0.55));

        List<Triggers.Fired> f = Triggers.evaluate(g, night(TODAY, p, Map.of(), null), state);
        assertEquals(1, f.stream().filter(x -> x.kind().equals(Triggers.PROB_DROP)).count());
        assertEquals(List.of("T_READING"), f.get(0).nodes());

        // Still low the next day: disarmed, so silent.
        assertTrue(Triggers.evaluate(g, night(TODAY.plusDays(1), p, Map.of(), null), state).stream()
            .noneMatch(x -> x.kind().equals(Triggers.PROB_DROP)));
        // Back to 0.65 is not enough to re-arm; above 0.70 is.
        p.put("T_READING", 0.65);
        Triggers.evaluate(g, night(TODAY.plusDays(2), p, Map.of(), null), state);
        p.put("T_READING", 0.50);
        assertTrue(Triggers.evaluate(g, night(TODAY.plusDays(3), p, Map.of(), null), state).stream()
            .noneMatch(x -> x.kind().equals(Triggers.PROB_DROP)));
        p.put("T_READING", 0.75);
        Triggers.evaluate(g, night(TODAY.plusDays(4), p, Map.of(), null), state);
        p.put("T_READING", 0.50);
        assertTrue(Triggers.evaluate(g, night(TODAY.plusDays(5), p, Map.of(), null), state).stream()
            .anyMatch(x -> x.kind().equals(Triggers.PROB_DROP)));
    }

    @Test
    public void privateNodesNeverFire() {
        Graph g = ForecastTest.answered();
        Map<String, Double> p = new HashMap<>();
        for (Node n : g.nodes.values()) if (n.deadline != null) p.put(n.id, 0.1);
        List<Triggers.Fired> f = Triggers.evaluate(g, night(TODAY, p, Map.of(), null), new HashMap<>());
        for (Triggers.Fired x : f) for (String id : x.nodes())
            assertFalse(id, Set.of("G_PARTNER", "G_NETWORK", "A_PITCH").contains(id));
        assertTrue(f.get(0).nodes().size() <= Triggers.GROUP);
    }

    @Test
    public void probJumpExpandDueManualChangeAndMonthlyReview() {
        Graph g = ForecastTest.answered();
        Map<String, Triggers.State> state = new HashMap<>();
        Map<String, Double> p = Map.of("T_SHORTLIST", 0.9);
        Map<String, Double> low = Map.of("T_SHORTLIST", 0.6);
        List<Triggers.Fired> f = Triggers.evaluate(g, night(TODAY, p, low, "a"), state);
        assertTrue(f.stream().anyMatch(x -> x.kind().equals(Triggers.PROB_JUMP)));
        assertTrue("manual_change only remembers on first sight", f.stream().noneMatch(x -> x.kind().equals(Triggers.MANUAL_CHANGE)));
        assertTrue("T_PROTOTYPE expands on 2026-11-01, not before", f.stream().noneMatch(x -> x.kind().equals(Triggers.EXPAND_DUE)));

        LocalDate nov4 = LocalDate.parse("2026-11-04");     // the first Wednesday of November
        f = Triggers.evaluate(g, night(nov4, p, low, "b"), state);
        assertTrue(f.stream().anyMatch(x -> x.kind().equals(Triggers.MANUAL_CHANGE)));
        assertTrue(f.stream().anyMatch(x -> x.kind().equals(Triggers.EXPAND_DUE) && x.nodes().equals(List.of("T_PROTOTYPE"))));
        assertTrue(f.stream().anyMatch(x -> x.kind().equals(Triggers.MONTHLY_REVIEW)));
        // The order they are sent in: expansion before the change review before the monthly one.
        List<String> kinds = f.stream().map(Triggers.Fired::kind).toList();
        assertTrue(kinds.indexOf(Triggers.EXPAND_DUE) < kinds.indexOf(Triggers.MONTHLY_REVIEW));

        f = Triggers.evaluate(g, new Triggers.Night(nov4.plusDays(1), nov4.plusDays(1).toEpochDay() * 86_400_000L,
            p, low, "b", null, Set.of("T_PROTOTYPE")), state);
        assertTrue("once a month, and not while an expansion waits", f.stream()
            .noneMatch(x -> x.kind().equals(Triggers.MONTHLY_REVIEW) || x.kind().equals(Triggers.EXPAND_DUE)));
    }

    // ------------------------------------------------------------------ replan

    static LlmRequest replanFor(Graph g, String... nodes) {
        return Replan.replan(g, TODAY, Triggers.PROB_DROP, List.of(nodes), "test", Map.of(), null);
    }

    @Test
    public void replanOptionsBecomeParametersOrTalks() {
        Graph g = ForecastTest.answered();
        String answer = "{\"options\":["
            + "{\"title\":\"More survey hours\",\"actions\":[{\"type\":\"extra_hours\",\"node\":\"T_READING\",\"hours_per_week\":6,"
            + "\"from\":\"2026-10-14\",\"until\":\"2026-11-10\"},{\"type\":\"pause\",\"node\":\"T_SHORTLIST\",\"from\":\"2026-10-14\",\"until\":\"2026-11-10\"}],\"rationale\":\"r\"},"
            + "{\"title\":\"Ask for time\",\"actions\":[{\"type\":\"move_deadline\",\"node\":\"T_VIDEOS\",\"new_deadline\":\"2026-11-01\"}]},"
            + "{\"title\":\"Pause the gate\",\"actions\":[{\"type\":\"pause\",\"node\":\"G_INTERN\",\"from\":\"2026-10-14\",\"until\":\"2026-11-10\"}]},"
            + "{\"title\":\"Nope\",\"actions\":[{\"type\":\"extra_hours\",\"node\":\"NOPE\",\"hours_per_week\":1,\"from\":\"2026-10-14\",\"until\":\"2026-11-10\"}]}],"
            + SELF + ",\"summary\":\"s\"}";
        List<ProposalValidator.Result> r = Replan.validate(g, TODAY, replanFor(g, "T_READING"), answer);
        assertEquals(4, r.size());
        assertTrue(r.get(0).reason(), r.get(0).ok());
        assertEquals(Overrides.nodeKey("T_READING", "min_weekly"), r.get(0).ops().get(0).key());
        assertEquals(6.0, r.get(0).ops().get(0).values().get("value"), 1e-9);
        assertEquals(Overrides.nodeKey("T_SHORTLIST", "pause"), r.get(0).ops().get(1).key());
        assertTrue("an advisor's deadline is only discussed", r.get(1).ok() && r.get(1).ops().get(0).discussOnly());
        assertFalse("a gate is never paused", r.get(2).ok());
        assertFalse(r.get(3).ok());
        assertTrue(r.get(3).reason().contains("not in the graph"));
    }

    @Test
    public void replanRefusesPrivateNodesAndASelfDeclaredPerson() {
        Graph g = ForecastTest.answered();
        String priv = "{\"options\":[{\"title\":\"x\",\"actions\":[{\"type\":\"discuss\",\"node\":\"G_NETWORK\",\"topic\":\"t\"}]}]," + SELF + "}";
        assertFalse(Replan.validate(g, TODAY, replanFor(g, "T_READING"), priv).get(0).ok());
        String declared = "{\"options\":[{\"title\":\"x\",\"actions\":[{\"type\":\"discuss\",\"node\":\"T_SHORTLIST\",\"topic\":\"t\"}]}],"
            + "\"consent_self_check\":{\"involves_other_person\":true}}";
        List<ProposalValidator.Result> r = Replan.validate(g, TODAY, replanFor(g, "T_READING"), declared);
        assertEquals(1, r.size());
        assertFalse(r.get(0).ok());
    }

    @Test
    public void approvedHoursAndPausesMoveTheForecast() {
        Inputs in = ForecastTest.inputs();
        Graph base = with(List.of());
        Forecaster.Result before = Effects.baseline(base, in, TODAY, 1500, 11);
        Param more = new Param(Overrides.nodeKey("T_READING", "min_weekly"), Dist.POINT, null, "",
            "value", 10, "from", LocalDate.parse("2026-10-14").toEpochDay(), "until", LocalDate.parse("2026-11-10").toEpochDay());
        List<Effects.Change> up = Effects.compare(before, with(List.of(more)), in, TODAY, List.of("T_READING"), 0.03, 4);
        System.out.println("  10 h/week on the survey: " + up);
        assertTrue(up.get(0).delta() > 0.05);

        Param pause = new Param(Overrides.nodeKey("T_READING", "pause"), Dist.POINT, null, "",
            "from", LocalDate.parse("2026-10-14").toEpochDay(), "until", LocalDate.parse("2026-11-05").toEpochDay());
        List<Effects.Change> down = Effects.compare(before, with(List.of(pause)), in, TODAY, List.of("T_READING"), 0.03, 4);
        System.out.println("  paused until 5 Nov: " + down);
        assertTrue(down.get(0).after() < 0.2);
    }

    // ------------------------------------------------------------------ expand

    static String kids(String extra) {
        return "{\"children\":["
            + "{\"key\":\"DATA\",\"title\":\"Pick a dataset and baseline\",\"activity\":\"repro data\",\"granularity\":\"fine\","
            + "\"p50_hours\":12,\"p90_hours\":20,\"deadline\":\"2026-11-20\",\"depends_on\":[]},"
            + "{\"key\":\"TRAIN\",\"title\":\"Train the compact model\",\"activity\":\"repro training\",\"granularity\":\"fine\","
            + "\"p50_hours\":25,\"p90_hours\":50,\"deadline\":\"2026-12-20\",\"depends_on\":[\"DATA\"]},"
            + "{\"key\":\"EVAL\",\"title\":\"Evaluate and write up\",\"activity\":\"repro eval\",\"granularity\":\"fine\","
            + "\"p50_hours\":12,\"p90_hours\":25,\"deadline\":\"2027-01-10\",\"depends_on\":[\"TRAIN\"" + extra + "]}],"
            + "\"rationale\":\"r\"," + SELF + ",\"summary\":\"s\"}";
    }

    @Test
    public void anExpansionIsCheckedLikeAFile() {
        Graph g = ForecastTest.answered();
        LocalDate nov = LocalDate.parse("2026-11-01");
        LlmRequest req = Expand.request(g, nov, "T_PROTOTYPE", List.of("learning tools"));
        assertTrue(req.user().contains("T_PROTOTYPE"));
        assertFalse("private nodes stay out of the snapshot", req.user().contains("G_NETWORK"));

        ProposalValidator.Result ok = Expand.validate(g, nov, req, kids(""));
        assertTrue(ok.reason(), ok.ok());
        assertEquals(4, ok.ops().size());
        JsonObject first = ok.ops().get(0).node();
        assertEquals("T_PROTOTYPE_DATA", first.get("id").getAsString());
        assertTrue("the first task waits for what the node waited for",
            first.getAsJsonArray("depends_on").toString().contains("SK_TOOLS"));

        assertFalse("a loop", Expand.validate(g, nov, req, kids("").replace("\"depends_on\":[]", "\"depends_on\":[\"EVAL\"]")).ok());
        assertFalse("after the parent's deadline", Expand.validate(g, nov, req, kids("").replace("2027-01-10", "2027-02-10")).ok());
        assertFalse("not a sibling", Expand.validate(g, nov, req, kids(",\"SK_TOOLS\"")).ok());
        assertFalse("efforts far below the node's",
            Expand.validate(g, nov, req, kids("").replace("\"p50_hours\":25", "\"p50_hours\":1").replace("\"p50_hours\":12", "\"p50_hours\":2")).ok());
        assertFalse("medium needs its own expand_at",
            Expand.validate(g, nov, req, kids("").replace("\"granularity\":\"fine\",\"p50_hours\":25", "\"granularity\":\"medium\",\"p50_hours\":25")).ok());
    }

    @Test
    public void anApprovedExpansionHandsTheWorkToTheChildren() {
        Graph g = ForecastTest.answered();
        LocalDate nov = LocalDate.parse("2026-11-01");
        ProposalValidator.Result ok = Expand.validate(g, nov, Expand.request(g, nov, "T_PROTOTYPE", List.of()), kids(""));
        List<JsonObject> rows = Structure.apply(g.nodes.values().stream().map(n -> n.raw).toList(), ok.ops());
        Graph after = rebuild(g, rows, List.of());

        Node parent = after.node("T_PROTOTYPE");
        assertTrue(parent.expanded);
        assertNull(parent.effortP50);
        assertTrue(parent.raw.has("effort_before_expand"));
        assertEquals("T_PROTOTYPE", after.node("T_PROTOTYPE_DATA").raw.get("expanded_from").getAsString());
        List<Issue> issues = Requirements.check(after, TODAY, Set.of(), Map.of());
        assertTrue("an expanded node asks for no effort of its own",
            issues.stream().noneMatch(i -> i.subject().equals("T_PROTOTYPE") && i.fix() == Issue.Fix.NODE_EFFORT));

        Inputs in = ForecastTest.inputs();
        Forecaster.Result r = Forecaster.run(after, in, TODAY, 800, 5);
        LocalDate eval = r.nodes().get("T_PROTOTYPE_EVAL").p50(), whole = r.nodes().get("T_PROTOTYPE").p50();
        System.out.println("  after expansion: EVAL p50 " + eval + ", T_PROTOTYPE p50 " + whole);
        if (eval != null && whole != null) assertFalse("the node finishes with its last task", whole.isBefore(eval));
    }

    @Test
    public void aMovedDeadlineGivesWayToANewerFile() {
        Graph g = ForecastTest.answered();
        Node n = g.node("T_SHORTLIST");
        Map<String, Double> v = new java.util.LinkedHashMap<>();
        v.put("value", (double) LocalDate.parse("2026-12-01").toEpochDay());
        v.putAll(Overrides.was(n, "deadline"));
        Param moved = new Param(Overrides.nodeKey("T_SHORTLIST", "deadline"), Dist.POINT, null, "");
        moved.values.putAll(v);
        Graph after = with(List.of(moved));
        assertEquals(LocalDate.parse("2026-12-01"), after.node("T_SHORTLIST").deadline);

        // The same file again: the decision stays. A file that moved that deadline itself: the file wins.
        assertTrue(Overrides.superseded(ForecastTest.answered(), List.of(moved)).isEmpty());
        Graph fresh = ForecastTest.answered();
        fresh.node("T_SHORTLIST").deadline = LocalDate.parse("2026-11-20");
        assertEquals(List.of(moved.key), Overrides.superseded(fresh, List.of(moved)));
    }

    // ------------------------------------------------------------- onboarding

    @Test
    public void questionsAreRankedByWhatTheyWouldChange() {
        Graph g = with(List.of());
        assertFalse(InfoValue.candidates(g, TODAY).isEmpty());
        long t0 = System.nanoTime();
        List<InfoValue.Ranked> r = InfoValue.rank(g, TriggerLayerTest::with, ForecastTest.inputs(), TODAY, 200, 3, 30);
        System.out.printf("  ranked %d questions in %.1f s%n", r.size(), (System.nanoTime() - t0) / 1e9);
        r.stream().limit(5).forEach(x -> System.out.printf("  %.3f  %s%n", x.impact(), x.text()));
        assertFalse(r.isEmpty());
        for (int i = 1; i < r.size(); i++) assertTrue(r.get(i - 1).impact() >= r.get(i).impact());
    }

    @Test
    public void everyStageFiveAndSixConstantIsSeeded() {
        Graph g = ForecastTest.answered();
        for (String k : List.of("trigger.prob_drop", "trigger.prob_jump", "trigger.cooldown_h", "trigger.retry_days",
                "effects.runs", "info.runs", "info.budget_s"))
            assertNotNull(k, g.params.get(k));
        assertEquals(0.60, g.params.get("trigger.prob_drop").get("below"), 1e-9);
        assertEquals(0.70, g.params.get("trigger.prob_drop").get("reset"), 1e-9);
    }
}
