package com.chronos.harness

import com.chronos.model.Activity
import com.chronos.model.Config
import com.chronos.model.Enums
import com.chronos.model.Goal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Hours as well as dates: an activity's start, its goal's deadline, the plan's start, an upkeep's last day. */
class HoursOnDaysTest {
    private val day = LocalDate.parse("2026-10-22")

    private fun cfg(): Config = Config().apply {
        goals.add(Goal().apply { id = "g"; name = "hw"; mode = Enums.GoalMode.CLOSED; deadline = day.plusDays(2) })
        activities.add(Activity().apply {
            id = "a"; name = "writing hw"; kind = Enums.ActivityKind.GOAL; goalId = "g"
            windows.add(Activity.Window("08:00", "22:00")); days.addAll(0..6)
        })
        activities.add(Activity().apply {
            id = "u"; name = "workout"; kind = Enums.ActivityKind.MAINTENANCE; days.addAll(0..6)
            pins.add(Activity.Pin(com.chronos.engine.Slots.dow(day), "07:00"))
        })
    }

    @Test fun nothingSetLeavesTheDayAsItWas() {
        val c = cfg()
        assertSame(c, c.on(day))
    }

    @Test fun aStartHourNarrowsOnlyTheFirstDay() {
        val c = cfg()
        c.activity("a").startDate = day; c.activity("a").startTime = "15:30"
        val w = c.on(day).activity("a").windows
        assertEquals(1, w.size); assertEquals("15:30", w[0].from); assertEquals("22:00", w[0].to)
        assertEquals("08:00", c.on(day.plusDays(1)).activity("a").windows[0].from)
        assertNull(c.on(day.minusDays(1)).activity("a"))
    }

    @Test fun aDeadlineHourNarrowsTheDeadlineDay() {
        val c = cfg()
        c.goal("g").deadlineTime = "12:00"
        val w = c.on(day.plusDays(2)).activity("a").windows
        assertEquals("08:00", w[0].from); assertEquals("12:00", w[0].to)
        // A deadline before any of its window leaves nothing that day.
        c.goal("g").deadlineTime = "07:00"
        assertNull(c.on(day.plusDays(2)).activity("a"))
    }

    @Test fun thePlanStartHourHoldsEveryActivityAndDropsEarlierPins() {
        val c = cfg()
        c.planFrom = LocalDateTime.parse("2026-10-22T13:00")
        val on = c.on(day)
        assertEquals("13:00", on.activity("a").windows[0].from)
        val u = on.activity("u")
        assertEquals("13:00", u.windows[0].from); assertEquals("00:00", u.windows[0].to)
        assertTrue(u.pins.isEmpty())
        assertSame(c, c.on(day.plusDays(1)))
        // The stored activity is never touched.
        assertEquals("08:00", c.activity("a").windows[0].from)
    }

    @Test fun anUpkeepIsNotPlannedAfterItsLastDay() {
        val c = cfg()
        c.activity("u").endDate = day
        assertTrue(c.on(day).activity("u") != null)
        assertNull(c.on(day.plusDays(1)).activity("u"))
    }
}
