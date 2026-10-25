package com.chronos.navigator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.model.Edge;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.GraphImport;
import com.chronos.navigator.model.GraphJson;
import com.chronos.navigator.model.Overrides;
import com.chronos.navigator.model.Param;
import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Issue;
import com.chronos.navigator.model.Issue.Fix;
import com.chronos.navigator.model.Issue.Severity;
import com.chronos.navigator.model.Kinds.EdgeKind;
import com.chronos.navigator.model.Kinds.NodeType;
import com.chronos.navigator.model.Kinds.Owner;
import com.chronos.navigator.model.Kinds.Source;
import com.chronos.navigator.model.Requirements;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** The real goal graph, imported as the phone will import it, and broken on purpose. */
public class GraphImportTest {

    static String fixture() {
        try (InputStream in = GraphImportTest.class.getResourceAsStream("/goal-graph-v0.2.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static Graph real() {
        GraphImport.Result r = GraphImport.read(fixture(), 1L);
        assertTrue("the real file imports: " + r.invalid(), r.ok());
        return r.graph();
    }

    /** The Chronos activities that existed on 30 Sep 2026, as the export lists them. */
    static final Set<String> CHRONOS = Set.of("watching convex videos", "reading gsp", "reading cs",
        "studying english", "studying korean", "planning");

    @Test
    public void theRealFileImports() {
        Graph g = real();
        assertEquals(33, g.nodes.size());
        assertEquals(51, g.edges.size());
        assertEquals("0.2", g.schemaVersion);
        assertEquals(LocalDate.parse("2032-09-30"), g.horizonEnd);
        assertEquals(6, g.regimes.size());
        assertEquals(5, (int) g.riskTolerance);

        var convex = g.node("T_CONVEX");
        assertEquals(NodeType.TASK, convex.type);
        assertEquals(Owner.ADVISOR, convex.owner);
        assertEquals(30.0, convex.unitsTotal, 0);
        assertEquals(9.0, convex.unitsDone, 0);
        assertEquals(2.0, convex.perUnitPrior, 0);
        assertEquals(LocalDate.parse("2026-10-12"), convex.deadline);
        assertTrue(convex.hardDeadline);
        assertEquals(List.of("watching convex videos"), convex.activities);

        assertEquals(List.of("reading gsp", "reading cs"), g.node("STR_COURSES").activities);
        assertTrue(g.edges.contains(new Edge("T_CONVEX", "ADV_TRACK", EdgeKind.CHILD, -1, null)));
        assertTrue(g.edges.contains(new Edge("T_OUTREACH", "G_INTERN", EdgeKind.OR_GROUP, 0, null)));
        assertTrue(g.edges.contains(new Edge("T_APPLY_PROG", "G_INTERN", EdgeKind.OR_GROUP, 1, null)));
        assertTrue(g.edges.contains(new Edge("T_REPORT", "SK_EN", EdgeKind.CREDIT, -1, 0.7)));

        assertFalse(g.node("G_NETWORK").plannable());
        assertTrue(g.node("G_PARTNER").plannable());
        assertNotNull(g.regime("SPRINT_CONVEX").overlayOn);
        assertEquals(5.5, g.regime("SPRINT_CONVEX").plannedHPerDay.get("T_CONVEX"), 0);
    }

    @Test
    public void paramsCarryTheirSource() {
        Graph g = real();
        assertEquals(Source.USER, g.params.get("adherence").source);
        assertEquals(16.0, g.params.get("adherence").get("a"), 0);
        assertEquals(Source.EXPLICIT_PRIOR, g.params.get("bias.default").source);
        assertEquals(Source.DATA, g.params.get("bias.watching convex videos").source);
        assertEquals(8.0, g.params.get("capacity.IDEAL").get("mean"), 0);
        // Constants of the spec are seeded and labelled as such.
        assertEquals(Source.EXPLICIT_PRIOR, g.params.get("forgetting.lambda").source);
        assertEquals(0.98, g.params.get("forgetting.lambda").get("value"), 0);
    }

    @Test
    public void whatTheRealGraphStillLacks() {
        Graph g = real();
        List<Issue> issues = Requirements.check(g, LocalDate.parse("2026-10-03"), CHRONOS, Map.of());
        for (Issue i : issues) System.out.println("  " + i.severity() + " " + i.fix() + " " + i.subject() + ": " + i.message());

        Set<String> blocking = issues.stream().filter(i -> i.severity() == Severity.BLOCKING)
            .map(i -> i.fix() + ":" + i.subject()).collect(Collectors.toSet());
        assertTrue(blocking.contains("NODE_EFFORT:T_PRODUCT"));
        assertTrue(blocking.contains("NODE_PRIOR:MS_GRADES"));
        assertTrue(blocking.contains("REGIME_GAP:2027-02-11..2032-09-30"));
        assertTrue(blocking.contains("PARAM:capacity.IDEAL"));
        // pytorch and the survey are paused by the convex sprint: named, not blocking yet.
        assertTrue(issues.stream().anyMatch(i -> i.fix() == Fix.LINK_ACTIVITY
            && i.subject().equals("SK_TORCH|pytorch audio") && i.severity() == Severity.WARNING));
        assertFalse(Requirements.canRun(issues));

        // Linking clears the link issue.
        List<Issue> linked = Requirements.check(g, LocalDate.parse("2026-10-03"), CHRONOS,
            Map.of(Requirements.linkKey("SK_TORCH", "pytorch audio"), "planning"));
        assertFalse(linked.stream().anyMatch(i -> i.subject().equals("SK_TORCH|pytorch audio")));
    }

    @Test
    public void afterTheSprintPytorchBlocks() {
        Graph g = real();
        List<Issue> issues = Requirements.check(g, LocalDate.parse("2026-10-13"), CHRONOS, Map.of());
        assertTrue(issues.stream().anyMatch(i -> i.subject().equals("SK_TORCH|pytorch audio")
            && i.severity() == Severity.BLOCKING));
    }

    /** What the store writes and reads back, then the answers the Missing-data forms give. */
    @Test
    public void storedAndAnsweredTheGraphCanRun() {
        Graph g = real();
        List<JsonObject> nodes = g.nodes.values().stream().map(n -> n.raw).toList();
        List<JsonObject> regimes = g.regimes.stream().map(r -> r.raw).toList();
        List<Param> params = new java.util.ArrayList<>(g.params.values());

        Graph back = GraphJson.assemble(GraphJson.rest(g), nodes, regimes, params);
        assertEquals(g.nodes.size(), back.nodes.size());
        assertEquals(g.edges, back.edges);
        assertEquals(g.regimes.size(), back.regimes.size());
        assertEquals(g.params.keySet(), back.params.keySet());
        assertEquals(5, (int) back.riskTolerance);

        long now = 2L;
        params.add(Overrides.answer(Overrides.nodeKey("T_PRODUCT", "effort"), Dist.LOGNORMAL, "", now, "p50", 200, "p90", 500));
        params.add(Overrides.answer(Overrides.nodeKey("MS_GRADES", "success"), Dist.BETA, "", now, "a", 7, "b", 3));
        params.add(Overrides.answer(Overrides.regimeKey("TERM_LATER", "span"), Dist.POINT, "", now,
            "from", LocalDate.parse("2027-02-11").toEpochDay(), "to", LocalDate.parse("2032-09-30").toEpochDay()));
        params.add(Overrides.answer(Overrides.regimeKey("TERM_LATER", "mix"), Dist.DIRICHLET, "", now,
            "IDEAL", 0.5, "BUSY", 0.4, "STRESS", 0.1));
        for (String t : new String[]{"IDEAL", "BUSY", "STRESS"})
            params.add(Overrides.answer("capacity." + t, Dist.NORMAL, "", now,
                "mean", g.params.get("capacity." + t).get("mean"), "tau0", 1.5, "sigma", 2.0));

        Graph answered = GraphJson.assemble(GraphJson.rest(g), nodes, regimes, params);
        assertEquals(200.0, answered.node("T_PRODUCT").effortP50, 0);
        assertEquals("TERM_LATER", answered.calendarRegime(LocalDate.parse("2030-01-01")).id);
        List<Issue> left = Requirements.check(answered, LocalDate.parse("2026-10-03"), CHRONOS, Map.of());
        for (Issue i : left) System.out.println("  left: " + i.message());
        assertTrue("only warnings are left", Requirements.canRun(left));

        // A newer file that states the effort itself makes the app's answer redundant.
        JsonObject root = root();
        JsonObject effort = new JsonObject();
        effort.addProperty("total_h_p50", 300);
        effort.addProperty("total_h_p90", 600);
        nodeOf(root, "T_PRODUCT").add("effort", effort);
        Graph fresh = GraphImport.read(root.toString(), 3L).graph();
        List<String> dropped = Overrides.superseded(fresh, params);
        assertEquals(List.of(Overrides.nodeKey("T_PRODUCT", "effort")), dropped);
    }

    // ------------------------------------------------------- broken on purpose

    private static JsonObject root() { return JsonParser.parseString(fixture()).getAsJsonObject(); }

    private static JsonObject nodeOf(JsonObject root, String id) {
        for (var e : root.getAsJsonArray("nodes"))
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        throw new AssertionError(id);
    }

    private static List<Issue> refused(JsonObject root) {
        GraphImport.Result r = GraphImport.read(root.toString(), 1L);
        assertNull("a broken file must be refused", r.graph());
        for (Issue i : r.invalid()) System.out.println("  refused: " + i.message());
        return r.invalid();
    }

    @Test
    public void aLoopIsRefused() {
        JsonObject root = root();
        JsonArray deps = new JsonArray();
        deps.add("T_CV");
        nodeOf(root, "T_REPRO").add("depends_on", deps);   // T_CV → … → T_REPRO → … → T_CV
        List<Issue> out = refused(root);
        assertTrue(out.stream().anyMatch(i -> i.message().startsWith("The graph has a loop")));
    }

    @Test
    public void aMissingReferenceIsRefused() {
        JsonObject root = root();
        nodeOf(root, "T_CV").getAsJsonArray("depends_on").add("T_NOWHERE");
        assertTrue(refused(root).stream().anyMatch(i -> i.message().contains("T_NOWHERE")));
    }

    @Test
    public void consentWithoutChannelIsRefused() {
        JsonObject root = root();
        nodeOf(root, "G_PARTNER").getAsJsonObject("consent").remove("channel");
        assertTrue(refused(root).stream().anyMatch(i -> i.subject().equals("G_PARTNER")));
    }

    @Test
    public void nothingMaySitUnderAnUnplannableNode() {
        JsonObject root = root();
        nodeOf(root, "T_LABLIST").addProperty("parent", "G_NETWORK");
        assertFalse(refused(root).isEmpty());
    }

    @Test
    public void badNumbersAreRefused() {
        JsonObject root = root();
        nodeOf(root, "T_REPRO").getAsJsonObject("effort").addProperty("total_h_p90", 10);   // below p50 = 50
        nodeOf(root, "SK_LANG2").getAsJsonObject("level").addProperty("to", 12);
        List<Issue> out = refused(root);
        assertTrue(out.stream().anyMatch(i -> i.subject().equals("T_REPRO")));
        assertTrue(out.stream().anyMatch(i -> i.subject().equals("SK_LANG2")));
    }

    @Test
    public void aMixThatDoesNotAddUpIsRefused() {
        JsonObject root = root();
        JsonObject early = root.getAsJsonObject("regimes").getAsJsonArray("calendar").get(2).getAsJsonObject();
        early.getAsJsonObject("mix").addProperty("IDEAL", 0.9);
        assertTrue(refused(root).stream().anyMatch(i -> i.subject().equals("EARLY")));
    }

    @Test
    public void notJsonIsRefused() {
        GraphImport.Result r = GraphImport.read("{ nodes: [", 1L);
        assertNull(r.graph());
        assertEquals(1, r.invalid().size());
    }
}
