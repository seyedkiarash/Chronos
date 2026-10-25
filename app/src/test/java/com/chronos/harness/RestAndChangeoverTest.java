package com.chronos.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.chronos.data.ChronosRepository;
import com.chronos.data.Codec;
import com.chronos.data.Mappers;
import com.chronos.engine.DaySequencer;
import com.chronos.engine.Energy;
import com.chronos.engine.Objective;
import com.chronos.engine.Slots;
import com.chronos.model.Activity;
import com.chronos.model.Block;
import com.chronos.model.Config;
import com.chronos.model.Enums.ActivityKind;
import com.chronos.model.Goal;
import com.chronos.model.Params;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Two things the day has to get right about rest.
 *
 * Rest is worth something because it raises the capacity of the hours after it.
 * Rest taken before anything has been done raises nothing, because the
 * reservoir is already empty — so a restorative block placed there spends an
 * hour and buys nothing, and the objective now says so.
 *
 * And a block that happens somewhere else needs the minutes to get there. The
 * desk activities need none; changing for the gym or moving to the kitchen is
 * time that has to be on the timeline, not assumed away.
 */
public class RestAndChangeoverTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-09-22");

    private static Block block(String name, String start, int slots, double load, double recovery) {
        Block b = new Block();
        b.name = name;
        b.start = Slots.parse(start);
        b.dur = slots;
        b.load = load;
        b.recovery = recovery;
        return b;
    }

    // -------------------------------------------------- rest with nothing to shed

    /** An empty reservoir cannot be emptied further, however long you rest. */
    @Test
    public void restBeforeAnyWorkRelievesNothing() {
        Config cfg = ParityHarness.build(TODAY);
        Params p = cfg.params;
        double[] h = Energy.homeostatic(p);

        Block work = block("deep work", "10:00", 8, 0.8, 0.0);
        Block morningRest = block("morning rest", "07:30", 4, 0.0, 0.6);

        Energy.Trace with = Energy.simulate(List.of(morningRest, work), p, h);
        Energy.Trace without = Energy.simulate(List.of(work), p, h);

        int noon = Slots.parse("12:00");
        assertEquals("resting before any work cannot change the fatigue that follows",
            without.fatigue()[noon], with.fatigue()[noon], 1e-9);
    }

    /** And the hour it spent is charged, so the planner stops putting it there. */
    @Test
    public void restWithNothingToRelieveIsCountedAsSpentForNothing() {
        Config cfg = ParityHarness.build(TODAY);
        Params p = cfg.params;
        double[] h = Energy.homeostatic(p);

        Block work = block("deep work", "10:00", 8, 0.8, 0.0);
        Block early = block("walk", "07:30", 4, 0.0, 0.6);   // before any work
        Block late = block("walk", "12:00", 4, 0.0, 0.6);    // straight after it

        double wastedEarly = Energy.simulate(List.of(early, work), p, h).wastedRecoveryH();
        double wastedLate = Energy.simulate(List.of(late, work), p, h).wastedRecoveryH();
        System.out.printf("  wasted restoration: %.3f early, %.3f late%n", wastedEarly, wastedLate);

        assertTrue("an hour of rest before any work is restoration thrown away",
            wastedEarly > 0);
        assertTrue("the same rest after the work is not", wastedLate < wastedEarly);
    }

    /** The objective prefers the rest that can actually do something. */
    @Test
    public void theObjectivePrefersRestWhereThereIsFatigueToShed() {
        Config cfg = ParityHarness.build(TODAY);
        Params p = cfg.params;
        double[] h = Energy.homeostatic(p);
        Map<String, Double> weights = Map.of();

        Block work = block("deep work", "10:00", 8, 0.8, 0.0);
        double zEarly = Objective.score(
            new ArrayList<>(List.of(block("walk", "07:30", 4, 0.0, 0.6), work)), p, h, weights).z();
        double zLate = Objective.score(
            new ArrayList<>(List.of(block("walk", "12:00", 4, 0.0, 0.6), work)), p, h, weights).z();
        System.out.printf("  day score: %.2f with the walk early, %.2f with it late%n", zEarly, zLate);

        assertTrue("a walk that relieves nothing must not score as well as one that does",
            zLate > zEarly);
    }

    // ------------------------------------------------------------- changeover

    /** Nothing may butt up against a block that needs time to change into. */
    @Test
    public void aChangeoverIsHeldFreeBeforeTheBlock() {
        Config cfg = ParityHarness.build(TODAY);
        Activity gym = null;
        for (Activity a : cfg.activities)
            if (a.kind == ActivityKind.MAINTENANCE) { gym = a; break; }
        assertTrue("the harness has upkeep to mark", gym != null);
        gym.changeoverMin = 15;

        Goal g = cfg.goals.get(0);
        DaySequencer.Result r = DaySequencer.run(cfg, Slots.dow(TODAY), Map.of(g.id, 4.0),
            ChronosRepository.seedFor(TODAY, Mappers.configHash(cfg)));

        int changeoverSlots = 1;                 // 15 minutes is one slot
        for (Block b : r.blocks()) {
            if (!gym.name.equals(b.name) || b.start < 0) continue;
            for (Block other : r.blocks()) {
                if (other == b || other.start < 0) continue;
                int otherEnd = other.start + other.dur;
                assertTrue(other.name + " ends at " + Slots.format(otherEnd) + ", inside the "
                         + "changeover before " + b.name + " at " + Slots.format(b.start),
                    otherEnd <= b.start - changeoverSlots || other.start >= b.start + b.dur);
            }
            System.out.printf("  %s at %s, clear from %s%n", b.name, Slots.format(b.start),
                Slots.format(b.start - changeoverSlots));
        }
    }

    /** A desk activity with no changeover may still start the moment the last one ends. */
    @Test
    public void withoutAChangeoverBlocksMayStillTouch() {
        Config cfg = ParityHarness.build(TODAY);
        for (Activity a : cfg.activities) a.changeoverMin = null;
        Goal g = cfg.goals.get(0);

        DaySequencer.Result r = DaySequencer.run(cfg, Slots.dow(TODAY), Map.of(g.id, 4.0),
            ChronosRepository.seedFor(TODAY, Mappers.configHash(cfg)));
        for (Block b : r.blocks()) assertEquals("no changeover was asked for", 0, b.changeover);
    }

    /** The setting survives storage, and rows written before it existed read as none. */
    @Test
    public void theChangeoverSurvivesStorage() {
        Config cfg = ParityHarness.build(TODAY);
        Activity a = cfg.activities.get(0);
        a.changeoverMin = 20;

        Activity back = Mappers.toModel(Mappers.toRow(a, 0));
        assertEquals(Integer.valueOf(20), back.changeoverMin);

        a.changeoverMin = null;
        assertEquals("unset stays unset", null, Mappers.toModel(Mappers.toRow(a, 0)).changeoverMin);
    }

    /** Changing it changes the plan's fingerprint, so the stored week is marked stale. */
    @Test
    public void theChangeoverIsPartOfTheConfigurationFingerprint() {
        Config cfg = ParityHarness.build(TODAY);
        String before = Mappers.configHash(cfg);
        cfg.activities.get(0).changeoverMin = 15;
        assertTrue("a changeover moves blocks, so the plan must be marked out of date",
            !before.equals(Mappers.configHash(cfg)));
    }

    /** Codec is untouched by this field, but the round trip must still hold. */
    @Test
    public void theDayStillRoundTripsThroughStorage() {
        Config cfg = ParityHarness.build(TODAY);
        cfg.activities.get(0).changeoverMin = 15;
        Goal g = cfg.goals.get(0);
        DaySequencer.Result r = DaySequencer.run(cfg, Slots.dow(TODAY), Map.of(g.id, 3.0),
            ChronosRepository.seedFor(TODAY, Mappers.configHash(cfg)));

        List<Block> back = Codec.parseBlocks(Codec.blocks(r.blocks()));
        assertEquals(r.blocks().size(), back.size());
    }
}
