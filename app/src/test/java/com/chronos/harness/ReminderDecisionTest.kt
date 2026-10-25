package com.chronos.harness

import com.chronos.auto.Reminders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a log reminder asks: at the set times, after a long quiet stretch, never asleep or in class. */
class ReminderDecisionTest {
    private val wake = 7 * 60
    private val bed = 23 * 60 + 30
    private val times = listOf("13:00", "18:00")

    private fun at(h: Int, m: Int = 0) = h * 60 + m

    @Test fun aSetTimeAsksOnceWhenNothingWasLoggedJustBefore() {
        assertEquals(Reminders.Ask.AtTime("13:00"),
            Reminders.decide(at(13, 10), wake, bed, false, times, emptySet(), at(11), null, 0.0))
        assertNull(Reminders.decide(at(13, 10), wake, bed, false, times, setOf("13:00"), at(11), null, 0.0))
        // Logged twenty minutes ago: no need to ask.
        assertNull(Reminders.decide(at(13, 10), wake, bed, false, times, emptySet(), at(12, 50), null, 0.0))
        // Long after the time (the phone was off): that one is let go.
        assertNull(Reminders.decide(at(15), wake, bed, false, times, emptySet(), at(11), null, 0.0))
    }

    @Test fun aLongQuietStretchAsks() {
        val ask = Reminders.decide(at(14), wake, bed, false, emptyList(), emptySet(), at(10, 30), null, 3.0)
        assertTrue(ask is Reminders.Ask.Quiet)
        assertNull(Reminders.decide(at(13), wake, bed, false, emptyList(), emptySet(), at(10, 30), null, 3.0))
        // Asked an hour ago: the stretch counts from then.
        assertNull(Reminders.decide(at(14), wake, bed, false, emptyList(), emptySet(), at(10, 30), at(13), 3.0))
        // Nothing yet today: counted from waking.
        assertTrue(Reminders.decide(at(10, 15), wake, bed, false, emptyList(), emptySet(), null, null, 3.0) is Reminders.Ask.Quiet)
    }

    @Test fun neverAsleepOrInClass() {
        assertNull(Reminders.decide(at(6), wake, bed, false, times, emptySet(), null, null, 1.0))
        assertNull(Reminders.decide(at(23, 45), wake, bed, false, times, emptySet(), null, null, 1.0))
        assertNull(Reminders.decide(at(13, 10), wake, bed, true, times, emptySet(), null, null, 1.0))
        // Bed past midnight: half past midnight is still awake.
        val late = 25 * 60
        assertTrue(Reminders.decide(at(0, 30), wake, late, false, emptyList(), emptySet(), null, at(0), 0.0) == null)
        assertTrue(Reminders.decide(at(23, 50), wake, late, false, emptyList(), emptySet(), at(20), null, 3.0) is Reminders.Ask.Quiet)
    }
}
