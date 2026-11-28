package com.earshot.app.data

import com.earshot.app.data.db.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatchUpDripTest {
    private fun ep(id: String, day: Long, no: Double? = null, season: Int? = null, played: Boolean = false, queued: Boolean = false) =
        Episode(
            id = id, podcastId = 1, guid = id, title = id, audioUrl = "https://x/$id.mp3",
            pubDate = day * 86_400_000L, episodeNo = no, season = season, played = played,
            queuePosition = if (queued) 0L else null,
        )

    @Test fun nextOldestUnplayedByDate() {
        val list = listOf(ep("e3", 3), ep("e1", 1, played = true), ep("e2", 2))
        assertEquals("e2", CatchUpDrip.next(list, "e1")?.id)
    }

    @Test fun skipsPlayedOnes() {
        val list = listOf(ep("e1", 1), ep("e2", 2, played = true), ep("e3", 3))
        assertEquals("e3", CatchUpDrip.next(list, "e1")?.id)
    }

    @Test fun numbersWinOverDates() {
        // Episode 2 was re-published later than episode 3: numbers still decide.
        val list = listOf(ep("n1", 1, no = 1.0), ep("n2", 9, no = 2.0), ep("n3", 3, no = 3.0))
        assertEquals("n2", CatchUpDrip.next(list, "n1")?.id)
        assertEquals("n3", CatchUpDrip.next(list, "n2")?.id)
    }

    @Test fun seasonsComeBeforeNumbers() {
        val list = listOf(ep("s1e9", 1, no = 9.0, season = 1), ep("s2e1", 2, no = 1.0, season = 2), ep("s1e10", 3, no = 10.0, season = 1))
        assertEquals("s1e10", CatchUpDrip.next(list, "s1e9")?.id)
        assertEquals("s2e1", CatchUpDrip.next(list, "s1e10")?.id)
    }

    @Test fun nothingAfterTheLastOrWhenAlreadyQueued() {
        val list = listOf(ep("e1", 1), ep("e2", 2, queued = true))
        assertNull(CatchUpDrip.next(list, "e2"))
        assertNull(CatchUpDrip.next(list, "e1"))
        assertNull(CatchUpDrip.next(list, "missing"))
    }

    @Test fun fewNumbersFallBackToDates() {
        val list = listOf(ep("a", 1, no = 5.0), ep("b", 2), ep("c", 3))
        assertEquals("b", CatchUpDrip.next(list, "a")?.id)
    }
}
