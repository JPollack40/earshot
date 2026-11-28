package com.earshot.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FinishByTest {
    private val min = 60_000L

    @Test fun speedsUpJustEnoughInSteps() {
        // 60 min left, 50 min to go: 1.2x.
        assertEquals(1.2f, FinishByMath.speedFor(60 * min, 0, 50 * min, 1f, 2f), 1e-4f)
        // 1.21x needed rounds up to the next step.
        assertEquals(1.25f, FinishByMath.speedFor(121 * min, 0, 100 * min, 1f, 2f), 1e-4f)
    }

    @Test fun neverBelowOwnSpeedOrAboveLimit() {
        assertEquals(1.5f, FinishByMath.speedFor(10 * min, 0, 60 * min, 1.5f, 2f), 1e-4f)
        assertEquals(2f, FinishByMath.speedFor(300 * min, 0, 60 * min, 1f, 2f), 1e-4f)
        // Past the time: the limit.
        assertEquals(2f, FinishByMath.speedFor(10 * min, 60 * min, 60 * min, 1f, 2f), 1e-4f)
        // Nothing left: own speed.
        assertEquals(1.1f, FinishByMath.speedFor(0, 0, 60 * min, 1.1f, 2f), 1e-4f)
    }

    @Test fun defaultLimitAndChoices() {
        assertEquals(1.5f, FinishByMath.defaultMax(1f), 1e-4f)
        assertEquals(1.75f, FinishByMath.defaultMax(1.2f), 1e-4f)
        assertEquals(3f, FinishByMath.defaultMax(2.8f), 1e-4f)
        assertEquals(listOf(2.5f, 3f), FinishByMath.maxChoices(2f))
        assertEquals(listOf(3f), FinishByMath.maxChoices(3f))
    }

    @Test fun pickedTimeIsTodayOrTomorrow() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 11, 28, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        val later = FinishByMath.deadlineFor(21, 30, now, zone)
        assertEquals(90 * min, later - now)
        val earlier = FinishByMath.deadlineFor(7, 0, now, zone)
        assertEquals(11 * 60 * min, earlier - now)
    }

    @Test fun endsAtFollowsSpeed() {
        assertEquals(30 * min, FinishByMath.endsAt(60 * min, 0, 2f))
    }
}
