package com.chronos.navigator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.forecast.Density;
import com.chronos.navigator.forecast.Forecaster;
import com.chronos.navigator.model.Graph;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;

/** Too much for the time: found however far the deadline, and what could give way is the least important. */
public class DensityTest {
    private static final LocalDate TODAY = ForecastTest.TODAY;

    private static Density.Load load(String id, double hours, int days, int importance, boolean fixed) {
        return new Density.Load(id, id, id, hours, TODAY.plusDays(days), importance, fixed, true);
    }

    @Test
    public void workBeyondTheHoursIsFoundEvenWhenItsDateIsFar() {
        Graph g = ForecastTest.answered();
        Inputs in = ForecastTest.inputs();
        Density.Report r = Density.of(g, in, TODAY, List.of(load("huge far task", 5000, 300, 0, false)));
        assertFalse(r.fits());
        assertTrue(r.crunch().need() > r.crunch().have());
    }

    @Test
    public void theLeastImportantIsOfferedFirstAndWhatCannotMoveNever() {
        Graph g = ForecastTest.answered();
        Inputs in = ForecastTest.inputs();
        Density.Report r = Density.of(g, in, TODAY, List.of(
            load("must", 2000, 120, 2, true), load("nice", 400, 100, 0, false), load("wanted", 400, 110, 1, false)));
        assertFalse(r.fits());
        assertTrue(r.giving().stream().noneMatch(x -> x.family().equals("must")));
        assertTrue(r.giving().stream().anyMatch(x -> x.family().equals("nice")));
        assertTrue("what matters more is left alone while something that matters less can give way",
            r.giving().stream().noneMatch(x -> x.family().equals("wanted")));
    }

    @Test
    public void onStressDaysOnlyFirmWorkGoesOn() {
        // With every day a stress day but with hours in it, standing hours get none and a dated task still finishes.
        Graph g = ForecastTest.answered();
        Inputs in = ForecastTest.inputs();
        var cap = g.params.get("capacity.STRESS");
        cap.values.put("mean", 4.0);
        g.regimes.forEach(reg -> { if (reg.mix != null) { reg.mix.clear();
            reg.mix.put(com.chronos.navigator.model.Kinds.DayType.STRESS, 1.0); } });
        assertNotNull(Forecaster.run(g, in, TODAY, 200, 5).nodes().get("T_VIDEOS").p50());
    }
}
