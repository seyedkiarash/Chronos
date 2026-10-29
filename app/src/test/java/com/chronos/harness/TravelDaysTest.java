package com.chronos.harness;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.chronos.engine.DaySequencer;
import com.chronos.engine.FixedFrame;
import com.chronos.model.Block;
import com.chronos.model.Commitment;
import com.chronos.model.Config;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** A trip is made on the days it is travelled, not every day something is outside (your report of 29 Oct 2026). */
public class TravelDaysTest {

    private static final LocalDate FRI = LocalDate.of(2026, 10, 30);   // dow 6, no class

    private static boolean hasTrip(DaySequencer.Result r) {
        return r.blocks().stream().anyMatch(b -> b.name != null && b.name.startsWith("Commute ("));
    }

    @Test
    public void aWalkOutsideOnADayWithoutClassesIsNotATrip() {
        Config cfg = ParityHarness.build(FRI);
        cfg.activity("exercise").days.add(6);               // out of the house on Friday too
        Commitment commute = cfg.commitments.stream().filter(c -> c.kind == com.chronos.model.Enums.CommitmentKind.VARIABLE).findFirst().get();

        assertTrue("as before: something away, so the trip", hasTrip(DaySequencer.run(cfg, 6, Map.of(), 3)));

        commute.days = new TreeSet<>(List.of(0, 1, 2, 3, 4, 5));   // the class days
        DaySequencer.Result fri = DaySequencer.run(cfg, 6, Map.of(), 3);
        assertFalse("Friday is not a day it is travelled", hasTrip(fri));
        assertTrue("the workout is still planned", fri.blocks().stream().anyMatch(b -> "Workout".equals(b.name)));
        assertTrue("no trip time counted against Friday either", FixedFrame.travelMinutes(cfg, 6) == 0);
        assertTrue("a class day keeps its trip", hasTrip(DaySequencer.run(cfg, 1, Map.of(), 3)));
    }

    @Test
    public void aTripIsNeverLaidOverWhatIsBehindYou() {
        Config cfg = ParityHarness.build(FRI);
        // The morning kept as it was: reading at 06:30–07:30, and the plan built again from 07:00.
        Block reading = new Block();
        reading.name = "Read & summarize papers"; reading.start = 26; reading.dur = 4; reading.kept = true; reading.goalId = "lit";
        DaySequencer.Result r = DaySequencer.run(cfg, 0, Map.of(), 3, Map.of(), false, List.of(reading), 28);
        for (Block a : r.blocks())
            for (Block b : r.blocks())
                if (a != b && a.start >= 0 && b.start >= 0 && !a.gap && !b.gap)
                    assertFalse(a.name + " at " + a.start + " overlaps " + b.name + " at " + b.start,
                        a.start < b.start + b.dur && b.start < a.start + a.dur && (a.kept || b.kept));
    }
}
