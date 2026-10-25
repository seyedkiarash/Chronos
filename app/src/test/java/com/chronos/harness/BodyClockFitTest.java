package com.chronos.harness;

import static org.junit.Assert.assertFalse;

import com.chronos.engine.Calibrator;
import com.chronos.engine.Energy;
import com.chronos.engine.Slots;
import com.chronos.model.Config;
import com.chronos.model.Enums.LogKind;
import com.chronos.model.LogEntry;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Sharp in the morning and evening, dull after lunch: the fitted peak is still an hour you are awake. */
public class BodyClockFitTest {

    @Test
    public void aMiddayDipDoesNotPutYourPeakAtNight() {
        LocalDate day = LocalDate.of(2026, 9, 30);
        Config cfg = ParityHarness.build(day);
        int[] byHour = {4, 4, 4, 4, 3, 3, 2, 2, 2, 3, 3, 4, 4, 4, 4, 3};   // 07:00 .. 22:00
        List<LogEntry> logs = new ArrayList<>();
        for (int d = 0; d < 3; d++)
            for (int i = 0; i < byHour.length; i++) {
                LogEntry e = new LogEntry();
                e.id = "e" + d + "|" + i;
                e.kind = LogKind.CHECKIN;
                e.date = day.minusDays(d);
                e.at = String.format("%02d:30", 7 + i);
                e.ts = 1_000_000L + d * 100 + i;
                e.energy = byHour[i];
                logs.add(e);
            }
        Calibrator.Outcome out = Calibrator.calibrate(cfg.copy(), logs);
        int phi = out.params().phi;
        int s0 = Slots.parse(cfg.params.sleepStart), s1 = Slots.parse(cfg.params.sleepEnd);
        assertFalse("fitted peak " + Slots.formatMinutes(phi) + " is while asleep",
            Energy.isAsleep(phi / Slots.SLOT_MIN, s0, s1));
    }
}
