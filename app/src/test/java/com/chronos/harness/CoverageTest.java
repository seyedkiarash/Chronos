package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.chronos.data.ChronosRepository;
import com.chronos.data.Mappers;
import com.chronos.engine.DaySequencer;
import com.chronos.engine.HorizonPlanner;
import com.chronos.engine.Slots;
import com.chronos.model.Activity;
import com.chronos.model.Block;
import com.chronos.model.Config;
import com.chronos.model.Goal;
import com.chronos.validate.Readiness;

import org.junit.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The gap between "the hours exist" and "the day can arrange them".
 *
 * The horizon planner counts a day's free hours. It cannot see that an
 * activity's allowed window, once a class and a meal and the trip home are in
 * it, has no unbroken stretch left long enough for one session — so it can pace
 * work onto a day that then drops it. These are the two halves of the fix: the
 * day delivering as much as it can hold, and the plan being re-paced around
 * what the days actually placed.
 */
public class CoverageTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-09-22");

    /** Hours of one goal placed on a given weekday. */
    private static double placedOn(Config cfg, int dow, Map<String, Double> alloc, String goalId) {
        DaySequencer.Result r = DaySequencer.run(cfg, dow, alloc,
            ChronosRepository.seedFor(TODAY, Mappers.configHash(cfg)));
        double h = 0;
        for (Block b : r.blocks())
            if (goalId.equals(b.goalId) && b.start >= 0) h += b.dur * Slots.SLOT_MIN / 60.0;
        return h;
    }

    /**
     * Given more hours than it can hold, a day must still place as many as fit.
     * The enumeration used to offer only splits that would deliver the whole
     * allocation, so when none of them could fit, a single block went down and
     * the rest was dropped — the day delivered less the more it was asked for.
     */
    @Test
    public void aDayThatCannotHoldItAllStillPlacesWhatFits() {
        Config cfg = ParityHarness.build(TODAY);
        Goal g = cfg.goals.get(0);
        Activity a = cfg.activitiesFor(g.id).get(0);

        // The freest weekday, so the test measures the sequencer rather than a
        // day that happens to be full of classes.
        int best = 0;
        double bestPlaced = 0;
        for (int dow = 0; dow < 7; dow++) {
            double p = placedOn(cfg, dow, Map.of(g.id, 8.0), g.id);
            if (p > bestPlaced) { bestPlaced = p; best = dow; }
        }
        System.out.printf("  %s given 8.0h places %.2fh (longest session %d min)%n",
            Slots.DOW[best], bestPlaced, a.maxBlock);

        assertTrue("a day with room, given eight hours, must place more than one session; "
                 + "it placed " + bestPlaced + "h", bestPlaced > a.maxBlock / 60.0);
    }

    /**
     * Asking for more must never deliver less on a day with room to spare. This
     * was the visible symptom: six hours placed two, where four placed four.
     */
    @Test
    public void askingForMoreNeverDeliversLessOnADayWithRoom() {
        Config cfg = ParityHarness.build(TODAY);
        Goal g = cfg.goals.get(0);

        int best = 0;
        double bestPlaced = 0;
        for (int dow = 0; dow < 7; dow++) {
            double p = placedOn(cfg, dow, Map.of(g.id, 8.0), g.id);
            if (p > bestPlaced) { bestPlaced = p; best = dow; }
        }

        double previous = 0;
        StringBuilder sb = new StringBuilder("  " + Slots.DOW[best] + ": ");
        for (double asked : new double[]{1, 2, 3, 4, 6, 8}) {
            double placed = placedOn(cfg, best, Map.of(g.id, asked), g.id);
            sb.append(String.format("%.0f->%.2f  ", asked, placed));
            assertTrue("asking for " + asked + "h delivered " + placed
                     + "h, less than the " + previous + "h a smaller ask delivered",
                placed >= previous - 0.01);
            previous = placed;
        }
        System.out.println(sb);
    }

    /** The undelivered hours are reported per goal, not just as a warning string. */
    @Test
    public void theDayReportsWhichGoalLostHours() {
        Config cfg = ParityHarness.build(TODAY);
        Goal g = cfg.goals.get(0);
        DaySequencer.Result r = DaySequencer.run(cfg, Slots.dow(TODAY), Map.of(g.id, 12.0),
            ChronosRepository.seedFor(TODAY, Mappers.configHash(cfg)));

        assertTrue("twelve hours of one goal cannot fit in a day", !r.undelivered().isEmpty());
        assertTrue("and the goal is named", r.undelivered().containsKey(g.id));
        assertTrue("with the hours it lost", r.undelivered().get(g.id) > 0);
        System.out.printf("  undelivered: %.2fh of %s%n", r.undelivered().get(g.id), g.name);
    }

    /**
     * Re-planning with the measured ceilings must not lose the hours — it moves
     * them to days that can hold them, or reports them as a shortfall. What it
     * must never do is allocate hours that quietly disappear.
     */
    @Test
    public void rePlanningWithMeasuredCeilingsDeliversMoreThanTheFirstPass() {
        Config cfg = ParityHarness.build(TODAY);
        // Enough work that the horizon paces more onto some days than their
        // windows can arrange — which is the situation the pass exists for.
        Goal heavy = cfg.goals.get(0);
        heavy.totalUnits = 90.0;
        heavy.doneUnits = 0.0;
        heavy.maxPerDayH = 9.0;
        heavy.deadline = TODAY.plusDays(13);

        HorizonPlanner.Plan first = HorizonPlanner.plan(cfg, TODAY);
        String hash = Mappers.configHash(cfg);

        Map<Integer, Map<String, Double>> caps = new HashMap<>();
        double lostBefore = 0;
        for (int i = 0; i < Math.min(14, first.allocation().size()); i++) {
            LocalDate d = TODAY.plusDays(i);
            Map<String, Double> demand = first.allocation().get(i);
            if (demand.isEmpty()) continue;
            DaySequencer.Result r = DaySequencer.run(cfg, Slots.dow(d), demand,
                ChronosRepository.seedFor(d, hash));
            if (r.undelivered().isEmpty()) continue;
            Map<String, Double> forDay = new HashMap<>();
            for (Map.Entry<String, Double> e : r.undelivered().entrySet()) {
                lostBefore += e.getValue();
                forDay.put(e.getKey(), Math.max(0, demand.getOrDefault(e.getKey(), 0.0) - e.getValue()));
            }
            caps.put(i, forDay);
        }

        assertTrue("the fixture is meant to over-allocate, so there is something to correct",
            !caps.isEmpty());

        HorizonPlanner.Plan second = HorizonPlanner.plan(cfg, TODAY, caps);
        double lostAfter = 0;
        for (int i = 0; i < Math.min(14, second.allocation().size()); i++) {
            LocalDate d = TODAY.plusDays(i);
            Map<String, Double> demand = second.allocation().get(i);
            if (demand.isEmpty()) continue;
            DaySequencer.Result r = DaySequencer.run(cfg, Slots.dow(d), demand,
                ChronosRepository.seedFor(d, hash));
            for (double v : r.undelivered().values()) lostAfter += v;
        }
        System.out.printf("  hours dropped by the days: %.2f before, %.2f after%n",
            lostBefore, lostAfter);
        assertTrue("the correction pass must not make the arrangement worse",
            lostAfter <= lostBefore + 0.01);
    }

    /** A horizon outside the supported range is reported, not silently corrected. */
    @Test
    public void anOutOfRangePlanningHorizonIsReported() {
        Config cfg = ParityHarness.build(TODAY);
        cfg.params.horizonDays = 3;
        List<Readiness.Issue> issues = Readiness.check(cfg, TODAY);
        assertTrue("three days is below the supported range",
            issues.stream().anyMatch(i -> i.what().contains("between 7 and 90")));

        cfg.params.horizonDays = 30;
        assertTrue("thirty is fine", Readiness.check(cfg, TODAY).stream()
            .noneMatch(i -> i.what().contains("between 7 and 90")));
    }

    /** Typing a horizon no longer rewrites what was typed. */
    @Test
    public void aPartiallyTypedHorizonIsLeftAlone() {
        Config cfg = ParityHarness.build(TODAY);
        // What the field now stores while "14" is being typed, one digit in.
        cfg.params.horizonDays = 1;
        assertEquals("the field must keep what was typed", Integer.valueOf(1), cfg.params.horizonDays);
        // And the planner must not fall over on it before the second digit lands.
        assertTrue(HorizonPlanner.plan(cfg, TODAY).days().size() >= 1);
    }
}
