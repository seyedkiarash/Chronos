package com.chronos.navigator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.estimate.Estimator;
import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.forecast.Bands;
import com.chronos.navigator.forecast.Forecaster;
import com.chronos.navigator.forecast.Guard;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.Dist;
import com.chronos.navigator.model.Overrides;
import com.chronos.navigator.model.Param;
import com.chronos.navigator.model.Priority;

import org.junit.Test;

/** The 80% band on each deadline's odds, and the floors your priorities set. */
public class BandsTest {

    static Graph learned(Inputs in) {
        Graph g = ForecastTest.answered();
        for (Param p : Estimator.run(g, in, ForecastTest.TODAY, 1).posteriors()) g.params.put(p.key, p);
        return g;
    }

    @Test
    public void theBandHoldsTheOddsAndRepeats() {
        Inputs in = ForecastTest.inputs();
        Graph g = learned(in);
        Bands.Result a = Bands.run(g, in, ForecastTest.TODAY, 30, 30, 5), b = Bands.run(g, in, ForecastTest.TODAY, 30, 30, 5);
        Forecaster.Result plain = Forecaster.run(g, in, ForecastTest.TODAY, 3000, 5);
        for (Bands.Band x : a.nodes().values()) {
            assertTrue(x.id(), x.low() <= x.p() + 1e-9 && x.p() <= x.high() + 1e-9);
            assertEquals("same seed, same band", x.low(), b.nodes().get(x.id()).low(), 0);
        }
        Bands.Band course = a.nodes().get("T_VIDEOS");
        // Its odds hang on the learned speed: the band is wide, and centred near the plain forecast.
        assertTrue("course's band is not a point", course.width() > 0.05);
        assertEquals(plain.nodes().get("T_VIDEOS").pByDeadline(), course.p(), 0.12);
    }

    @Test
    public void lowerOddsOnACriticalGoalBreakItsFloor() {
        Inputs in = ForecastTest.inputs();
        Graph g = learned(in);
        Bands.Result before = Bands.run(g, in, ForecastTest.TODAY, 30, 30, 9);
        // The same graph with goal hours cut in half: everything gets harder.
        Graph worse = learned(in);
        for (String t : new String[]{"IDEAL", "BUSY", "STRESS"}) {
            Param post = worse.params.get("post.capacity." + t);
            Param prior = worse.params.get("capacity." + t);
            if (post != null) post.values.put("mean", post.get("mean") * 0.4);
            if (prior != null && prior.get("mean") != null) prior.values.put("mean", prior.get("mean") * 0.4);
        }
        worse.params.put(Overrides.nodeKey("T_VIDEOS", "priority"),
            Overrides.answer(Overrides.nodeKey("T_VIDEOS", "priority"), Dist.POINT, "", 1, "value", Priority.CRITICAL));
        Overrides.apply(worse);
        assertEquals(Priority.CRITICAL, Priority.of(worse.node("T_VIDEOS")));
        Bands.Result after = Bands.run(worse, in, ForecastTest.TODAY, 30, 30, 9);
        Guard.Verdict v = Guard.check(worse, before, after);
        Bands.Band b0 = before.nodes().get("T_VIDEOS"), b1 = after.nodes().get("T_VIDEOS");
        System.out.printf("  course before %.2f [%.2f–%.2f], after %.2f [%.2f–%.2f]%n",
            b0.p(), b0.low(), b0.high(), b1.p(), b1.low(), b1.high());
        assertTrue("fewer hours lower course's odds", b1.p() < b0.p());
        assertFalse("a critical goal pushed lower breaks its floor", v.ok());
        assertTrue(v.breaches().stream().anyMatch(m -> m.nodeId().equals("T_VIDEOS")));

        Guard.Verdict same = Guard.check(g, before, before);
        assertTrue("no change, nothing broken", same.ok() && same.moves().isEmpty());
    }
}
