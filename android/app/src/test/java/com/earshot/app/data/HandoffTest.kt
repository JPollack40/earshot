package com.earshot.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffTest {
    private val t0 = java.time.Instant.parse("2026-11-28T08:30:00Z").toEpochMilli()

    @Test fun readsServerTimes() {
        assertEquals(t0, Handoff.parseTime("2026-11-28T08:30:00+00:00"))
        assertEquals(t0 + 123, Handoff.parseTime("2026-11-28 08:30:00.123+00"))
        assertEquals(t0, Handoff.parseTime("2026-11-28T08:30:00Z"))
        assertEquals(0L, Handoff.parseTime("nonsense"))
    }

    @Test fun parsesARow() {
        val o = JSONObject("""{"device":"web","device_name":"","episode_id":"ep1","position_ms":61000,"playing":true,"updated_at":"2026-11-28T08:30:00+00:00"}""")
        val h = Handoff.parse(o)!!
        assertEquals("web", h.device)
        assertEquals(61_000L, h.positionMs)
        assertEquals(t0, h.updatedAt)
        assertEquals("the web player", Handoff.label(h))
        assertNull(Handoff.parse(JSONObject("""{"device":"","episode_id":"x"}""")))
    }

    @Test fun offersOnlyOtherRecentDevices() {
        val playing = Handoff.Holder("web", "", "ep1", 1000, true, t0)
        assertTrue(Handoff.offer(playing, "android:me", t0 + 60_000))
        assertFalse("stale while playing", Handoff.offer(playing, "android:me", t0 + 3 * 60_000))
        assertFalse("this device", Handoff.offer(playing, "web", t0))
        val paused = playing.copy(playing = false)
        assertTrue(Handoff.offer(paused, "android:me", t0 + 3 * 60 * 60_000L))
        assertFalse(Handoff.offer(paused, "android:me", t0 + 13 * 60 * 60_000L))
        assertFalse(Handoff.offer(null, "android:me", t0))
    }
}
