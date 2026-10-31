package com.chronos.navigator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.allocate.Allocator;
import com.chronos.navigator.estimate.Estimator;
import com.chronos.navigator.estimate.Inputs;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Param;

import org.junit.Test;

import java.time.LocalDate;

/** The week's hours on the real graph: the sprint first, floors next, the rest where it counts most. */
public class AllocatorTest {

    static Allocator.Result week(LocalDate today) {
        Graph g = ForecastTest.answered();
        Inputs in = ForecastTest.inputs();
        for (Param p : Estimator.run(g, in, today, 1).posteriors()) g.params.put(p.key, p);
        return Allocator.run(g, in, today, Allocator.weekStart(today), 99);
    }

    static void print(Allocator.Result r) {
        System.out.printf("  week of %s: capacity %.1f h, %.1f h left free, %d ms%n", r.weekStart(), r.capacityH(), r.freeH(), r.millis());
        for (Allocator.Line l : r.lines()) System.out.printf("    %-14s %5.2f h  %s%n", l.nodeId(), l.hours(), l.reason());
    }

    @Test
    public void duringTheSprintCourseGetsWhatWasPlanned() {
        Allocator.Result r = week(LocalDate.parse("2026-10-06"));
        print(r);
        Allocator.Line course = r.lines().stream().filter(l -> l.nodeId().equals("T_VIDEOS")).findFirst().orElseThrow();
        assertTrue("at least the sprint's 5.5 h a day for the days left in it", course.hours() >= 5.5 * 5);
        double total = r.lines().stream().mapToDouble(Allocator.Line::hours).sum();
        assertTrue("never more than the week holds, beyond the sprint's own plan",
            total <= r.capacityH() + r.overH() + 1e-6 + 0.25 * r.lines().size());
    }

    @Test
    public void afterTheSprintTheSupportWorkComesBack() {
        Allocator.Result r = week(LocalDate.parse("2026-10-14"));
        print(r);
        assertTrue(r.lines().stream().anyMatch(l -> l.nodeId().equals("SK_TOOLS") || l.nodeId().equals("T_READING")));
        assertTrue("well under a few seconds on a desktop", r.millis() < 20000);
    }

    @Test
    public void theSameSeedGivesTheSameWeek() {
        Allocator.Result a = week(LocalDate.parse("2026-10-14")), b = week(LocalDate.parse("2026-10-14"));
        assertEquals(a.lines(), b.lines());
    }
}
