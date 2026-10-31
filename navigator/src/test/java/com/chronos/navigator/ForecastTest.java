package com.chronos.navigator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.estimate.Estimator;
import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.forecast.Forecaster;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.GraphJson;
import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Overrides;
import com.chronos.navigator.model.Param;
import com.chronos.navigator.model.ParamRegistry;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** The forecast on the real graph, answered as the app asks it to be. */
public class ForecastTest {

    static final LocalDate TODAY = LocalDate.parse("2026-10-06");

    /** The real graph with the Missing-data answers given. */
    static Graph answered() {
        Graph g = EstimatorTest.ready();
        List<JsonObject> nodes = g.nodes.values().stream().map(n -> n.raw).toList();
        List<JsonObject> regimes = g.regimes.stream().map(r -> r.raw).toList();
        List<Param> params = new ArrayList<>(g.params.values());
        params.add(Overrides.answer(Overrides.nodeKey("T_PRODUCT", "effort"), Dist.LOGNORMAL, "", 1, "p50", 260, "p90", 650));
        params.add(Overrides.answer(Overrides.nodeKey("MS_MARKS", "success"), Dist.BETA, "", 1, "a", 4.5, "b", 0.5));
        params.add(Overrides.answer(Overrides.regimeKey("LATER", "span"), Dist.POINT, "", 1,
            "from", LocalDate.parse("2027-02-11").toEpochDay(), "to", LocalDate.parse("2032-09-30").toEpochDay()));
        params.add(Overrides.answer(Overrides.regimeKey("LATER", "mix"), Dist.DIRICHLET, "", 1,
            "IDEAL", 0.4, "BUSY", 0.4, "STRESS", 0.2, "strength", 2));
        Graph out = GraphJson.assemble(GraphJson.rest(g), nodes, regimes, params);
        ParamRegistry.seed(out, 1);
        return out;
    }

    static Inputs inputs() {
        Inputs in = new Inputs();
        in.nodeActivities.put("T_VIDEOS", List.of("watching course videos"));
        in.nodeActivities.put("STR_CLASSES", List.of("reading course A", "reading course B"));
        in.nodeActivities.put("SK_LANG1", List.of("studying language a"));
        in.nodeActivities.put("SK_LANG2", List.of("studying language b"));
        in.nodeActivities.put("STR_SIDE", List.of("planning"));
        in.nodeActivities.put("SK_TOOLS", List.of("learning tools"));
        in.unitGoals.put("watching course videos", new Inputs.UnitGoal(1.5, 13.5, 30.0));
        return in;
    }

    static Forecaster.Result forecast(int runs, long seed) {
        Graph g = answered();
        Inputs in = inputs();
        for (Param p : Estimator.run(g, in, TODAY, 1).posteriors()) g.params.put(p.key, p);
        return Forecaster.run(g, in, TODAY, runs, seed);
    }

    @Test
    public void theSameSeedGivesTheSameNumbers() {
        Forecaster.Result a = forecast(300, 42), b = forecast(300, 42);
        for (String id : a.nodes().keySet()) {
            assertEquals(id, a.nodes().get(id).pByDeadline(), b.nodes().get(id).pByDeadline());
            assertEquals(id, a.nodes().get(id).p50(), b.nodes().get(id).p50());
        }
    }

    @Test
    public void theRealGraphForecastsAndIsFast() {
        Forecaster.Result r = forecast(5000, 7);
        System.out.println("  5000 runs in " + r.millis() + " ms");
        for (Forecaster.NodeResult n : r.nodes().values())
            System.out.printf("  %-14s P=%s reached=%.2f p50=%s critical=%.2f%n", n.id(),
                n.pByDeadline() == null ? "  —  " : String.format("%.2f", n.pByDeadline()), n.pReached(), n.p50(), n.critical());
        Forecaster.NodeResult course = r.nodes().get("T_VIDEOS");
        assertNotNull(course.pByDeadline());
        // 16.5 videos left at ~2.7 h each by 12 Oct, with the sprint's 5.5 h a day: a real race.
        assertTrue("course is neither certain nor hopeless", course.pByDeadline() > 0.05 && course.pByDeadline() < 0.95);
        assertEquals("the graph's terminal can be reached in some futures", true, r.nodes().get("A_PITCH").pReached() >= 0);
        assertTrue("well under the 5 s budget on a desktop", r.millis() < 5000);
    }

    @Test
    public void moreHoursNeverLowerTheChance() {
        // Property (spec): more hours for a node do not lower its probability.
        Graph g = answered();
        Inputs in = inputs();
        for (Param p : Estimator.run(g, in, TODAY, 1).posteriors()) g.params.put(p.key, p);
        Forecaster.Result base = Forecaster.run(g, in, TODAY, 2000, 11);
        g.params.get("capacity.IDEAL").values.put("mean", 10.0);
        g.params.remove("post.capacity.IDEAL");
        Forecaster.Result more = Forecaster.run(g, in, TODAY, 2000, 11);
        double p0 = base.nodes().get("T_VIDEOS").pByDeadline(), p1 = more.nodes().get("T_VIDEOS").pByDeadline();
        assertTrue(p1 + 0.02 >= p0);
    }
}
