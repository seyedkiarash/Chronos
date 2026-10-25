package com.chronos.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.chronos.engine.Slots;
import com.chronos.model.Block;
import com.chronos.model.Config;

import org.junit.Test;

/** The night's sleep as a calendar block always starts inside the day it is filed under. */
public class SleepBlockTest {

    private static Config sleeping(String from, String to) {
        Config c = new Config();
        c.params.sleepStart = from;
        c.params.sleepEnd = to;
        return c;
    }

    @Test
    public void bedtimeBeforeMidnightRunsIntoTheNextMorning() {
        Block b = CalendarSync.sleepBlock(sleeping("23:00", "06:00"), "Sleep");
        assertEquals(Slots.parse("23:00"), b.start);
        assertEquals(7 * 60, b.dur * Slots.SLOT_MIN);
    }

    @Test
    public void bedtimeAfterMidnightStartsOnItsOwnDate() {
        Block b = CalendarSync.sleepBlock(sleeping("01:00", "08:30"), "Sleep");
        assertEquals(Slots.parse("01:00"), b.start);
        assertEquals(450, b.dur * Slots.SLOT_MIN);
    }

    @Test
    public void offMeansNoBlock() {
        assertNull(CalendarSync.sleepBlock(sleeping("23:00", "06:00"), null));
    }
}
