package com.chronos.navigator;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.estimate.Estimator;
import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.forecast.Forecaster;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Edge;
import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Kinds.EdgeKind;
import com.chronos.navigator.model.Kinds.NodeType;
import com.chronos.navigator.model.Kinds.Source;
import com.chronos.navigator.model.Node;
import com.chronos.navigator.model.Param;

import org.junit.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * What made the forecast far too gloomy on real data (found 6 Nov 2026). Capacity is learned from
 * hours actually worked, so the share of the plan that gets done must not be taken off it again; a
 * stream that never ends must not hold a track open; and standing hours that fill the day must not
 * leave a dated task nothing at all.
 */
public class HonestOddsTest {
    private static final LocalDate TODAY = LocalDate.parse("2026-10-06");

    /** The share of the plan done overall (a low one, then a high one) hardly moves a task, because capacity already holds it. */
    @Test
    public void theShareOfThePlanDoneIsNotCountedTwice() {
        long low = finish(0.3), high = finish(0.9);
        assertTrue("finish " + low + " vs " + high + " days", Math.abs(low - high) <= 3);
    }

    private static long finish(double share) {
        Graph g = ForecastTest.answered();
        Inputs in = ForecastTest.inputs();
        for (Param p : Estimator.run(g, in, TODAY, 1).posteriors()) g.params.put(p.key, p);
        Param post = new Param("post.adherence", Dist.BETA, Source.DATA, null);
        post.values.put("a", 200 * share); post.values.put("b", 200 * (1 - share));
        g.params.put(post.key, post);
        LocalDate p50 = Forecaster.run(g, in, TODAY, 400, 5).nodes().get("T_VIDEOS").p50();
        return ChronoUnit.DAYS.between(TODAY, p50);
    }

    @Test
    public void aStreamDoesNotHoldATrackOpen() {
        Graph g = ForecastTest.answered();
        String track = g.nodes.values().stream().filter(n -> n.type == NodeType.EXTERNAL_TRACK).findFirst().orElseThrow().id;
        Inputs in = ForecastTest.inputs();
        g.edges.add(new Edge("STR_SIDE", track, EdgeKind.CHILD, 0, null));
        assertTrue(Forecaster.run(g, in, TODAY, 300, 5).nodes().get(track).pReached() > 0.5);
    }

    @Test
    public void standingHoursThatFillTheDayLeaveADatedTaskItsHours() {
        Graph g = ForecastTest.answered();
        Inputs in = ForecastTest.inputs();
        for (Node n : g.nodes.values()) if (n.type == NodeType.STREAM) n.weeklyFloorH = 200.0;
        assertNotNull(Forecaster.run(g, in, TODAY, 300, 5).nodes().get("T_VIDEOS").p50());
    }
}
