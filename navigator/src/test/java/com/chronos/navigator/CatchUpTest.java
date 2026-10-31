package com.chronos.navigator;

import static org.junit.Assert.assertTrue;

import com.chronos.navigator.estimate.Estimator;
import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.forecast.Forecaster;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Param;

import org.junit.Test;

import java.time.LocalDate;
import java.util.TreeMap;

/**
 * Chronos re-plans every night: work not done today comes back before the deadline. A forecast that
 * followed only the first plan would call a deadline lost that the planner would in fact still make.
 */
public class CatchUpTest {

    static double courseOdds(double hoursPerDay, int days) {
        Graph g = ForecastTest.answered();
        Inputs in = ForecastTest.inputs();
        for (Param p : Estimator.run(g, in, ForecastTest.TODAY, 1).posteriors()) g.params.put(p.key, p);
        TreeMap<LocalDate, Double> plan = new TreeMap<>();
        for (int d = 0; d < days; d++) plan.put(ForecastTest.TODAY.plusDays(d), hoursPerDay);
        in.chronosPlan.put("T_VIDEOS", plan);
        return Forecaster.run(g, in, ForecastTest.TODAY, 2000, 3).nodes().get("T_VIDEOS").pByDeadline();
    }

    @Test
    public void workLeftBehindComesBackBeforeTheDeadline() {
        // The deadline (12 Oct) inside a 14-day plan: a run behind schedule gets the missing hours later.
        // The deadline (12 Oct) inside a 14-day plan: a run behind schedule gets the missing hours later.
        double inside = courseOdds(3.0, 14);
        // The same hours a day, but the plan ends three days in: nothing to catch up against.
        double outside = courseOdds(3.0, 3);
        System.out.printf("  course with the plan around its deadline %.2f, plan ending early %.2f%n", inside, outside);
        assertTrue("catching up raises the odds", inside > outside);
    }
}
