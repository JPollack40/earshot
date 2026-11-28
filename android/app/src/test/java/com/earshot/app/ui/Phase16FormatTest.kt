package com.earshot.app.ui

import com.earshot.app.data.db.Episode
import com.earshot.app.data.db.EpisodeWithPodcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 16 polish: show-notes timestamps, mini player status, queue time left. */
class Phase16FormatTest {
    @Test fun findsTimestampsInNotes() {
        val text = "(00:00) Intro\n12:34 - Octopus minds; 1:02:03 wrap-up. Call 555-12:34? Not 3.12:30 or 10:75."
        val found = findTimestamps(text, durationMs = 2 * 3600_000L).map { it.second }
        assertEquals(listOf(0L, 754_000L, 3_723_000L), found.take(3))
        assertTrue(found.none { it == 750_000L + 4 * 60_000 }) // "10:75" rejected
    }

    @Test fun dropsTimesPastTheEnd() {
        assertEquals(listOf(60_000L), findTimestamps("1:00 and 59:00", durationMs = 30 * 60_000L).map { it.second })
    }

    @Test fun miniPlayerStatus() {
        assertEquals("Buffering…", miniPlayerStatus(true, 0, 60_000, 1f))
        assertEquals("30m left", miniPlayerStatus(false, 0, 60 * 60_000L, 2f))
        assertEquals("under 1m left", miniPlayerStatus(false, 59 * 60_000L + 30_000, 60 * 60_000L, 1f))
        assertEquals("", miniPlayerStatus(false, 0, 0, 1f))
    }

    private fun item(id: String, podcastId: Long, durationMs: Long, positionMs: Long = 0) =
        EpisodeWithPodcast(Episode(id = id, podcastId = podcastId, guid = id, title = id, audioUrl = "https://x/$id.mp3", durationMs = durationMs, positionMs = positionMs), "P", null)

    @Test fun queueTimeLeftUsesEachShowsSpeedAndTheLivePosition() {
        val items = listOf(item("a", 1, 60 * 60_000L, positionMs = 10 * 60_000L), item("b", 2, 30 * 60_000L))
        val speeds = mapOf(1L to 2f, 2L to 1f)
        // a is playing at 40 min: 20 min left at 2x = 10 min; b 30 min at 1x.
        assertEquals(40 * 60_000L, queueTimeLeftMs(items, { speeds.getValue(it) }, "a", 40 * 60_000L))
    }
}
