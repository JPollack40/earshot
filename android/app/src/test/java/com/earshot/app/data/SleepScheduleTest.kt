package com.earshot.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SleepScheduleTest {
    // 2026-11-30 is a Monday.
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 29 + day, h, m)

    @Test fun windowAcrossMidnightBelongsToTheNightItStarts() {
        val s = SleepSchedule(on = true, startMin = 22 * 60 + 30, endMin = 6 * 60, days = setOf(1)) // Monday nights
        assertTrue(s.inWindow(at(1, 23)))        // Monday 11 PM
        assertTrue(s.inWindow(at(2, 5, 59)))     // Tuesday 5:59 AM, still Monday night
        assertFalse(s.inWindow(at(2, 6)))        // Tuesday 6 AM: over
        assertFalse(s.inWindow(at(2, 23)))       // Tuesday night: not chosen
        assertFalse(s.inWindow(at(1, 5)))        // Monday early morning belongs to Sunday night
        assertFalse(s.inWindow(at(1, 22, 29)))
    }

    @Test fun sameDayWindow() {
        val s = SleepSchedule(on = true, startMin = 13 * 60, endMin = 15 * 60, days = setOf(6, 7))
        assertTrue(s.inWindow(at(6, 14)))  // Saturday 2 PM
        assertFalse(s.inWindow(at(5, 14))) // Friday
        assertFalse(s.inWindow(at(6, 15)))
    }

    @Test fun offOrEmptyNeverMatches() {
        assertFalse(SleepSchedule(on = false).inWindow(at(1, 23)))
        assertFalse(SleepSchedule(on = true, days = emptySet()).inWindow(at(1, 23)))
    }

    @Test fun jsonRoundTripAndBadInput() {
        val s = SleepSchedule(on = true, startMin = 21 * 60, endMin = 7 * 60, days = setOf(1, 3, 5), minutes = 0)
        assertEquals(s, SleepSchedule.fromJson(s.toJson()))
        assertEquals(SleepSchedule(), SleepSchedule.fromJson("not json"))
        assertEquals("10:30 PM", SleepSchedule.timeLabel(22 * 60 + 30))
        assertEquals("12:05 AM", SleepSchedule.timeLabel(5))
        assertEquals("Mo", SleepSchedule.dayLetter(1))
    }
}
