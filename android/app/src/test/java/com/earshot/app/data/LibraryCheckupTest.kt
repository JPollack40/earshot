package com.earshot.app.data

import com.earshot.app.data.LibraryCheckup.DAY
import com.earshot.app.data.LibraryCheckup.Kind
import com.earshot.app.data.db.Episode
import com.earshot.app.data.db.Podcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCheckupTest {
    private val now = 1_000 * DAY
    private fun pod(id: Long, action: String? = null, complete: Boolean = false) =
        Podcast(id = id, feedUrl = "https://x/$id", title = "Show $id", newEpisodeAction = action, complete = complete)
    private fun ep(id: String, pod: Long, daysAgo: Long, played: Boolean = false, pos: Long = 0, playedAfterDays: Double? = null, queued: Boolean = false) =
        Episode(
            id = id, podcastId = pod, guid = id, title = id, audioUrl = "https://x/$id.mp3",
            pubDate = now - daysAgo * DAY, played = played, positionMs = pos,
            lastPlayedAt = playedAfterDays?.let { now - daysAgo * DAY + (it * DAY).toLong() } ?: 0,
            queuePosition = if (queued) 0L else null,
        )
    private fun kinds(f: List<LibraryCheckup.Finding>) = f.map { it.kind to it.podcastId }

    @Test fun passedOverAfterFiveUnplayed() {
        val eps = (1..5).map { ep("a$it", 1, 20L + it * 7) }
        assertEquals(listOf(Kind.PASSED_OVER to 1L), kinds(LibraryCheckup.check(listOf(pod(1)), eps, now)))
        // One played: no suggestion. Already skipping new episodes: none either.
        assertTrue(LibraryCheckup.check(listOf(pod(1)), eps.dropLast(1) + ep("p", 1, 60, played = true), now).isEmpty())
        assertTrue(LibraryCheckup.check(listOf(pod(1, action = "skip")), eps, now).isEmpty())
    }

    @Test fun quietShowsUnlessFinished() {
        val eps = listOf(ep("q", 2, 120, played = true))
        assertEquals(listOf(Kind.QUIET to 2L), kinds(LibraryCheckup.check(listOf(pod(2)), eps, now)))
        assertTrue(LibraryCheckup.check(listOf(pod(2, complete = true)), eps, now).isEmpty())
    }

    @Test fun alwaysFirstSuggestsTopOfQueue() {
        val eps = (1..5).map { ep("f$it", 3, it * 3L, played = true, playedAfterDays = 0.2) }
        assertEquals(listOf(Kind.ALWAYS_FIRST to 3L), kinds(LibraryCheckup.check(listOf(pod(3)), eps, now)))
        assertTrue(LibraryCheckup.check(listOf(pod(3, action = "queue_next")), eps, now).isEmpty())
    }

    @Test fun staleQueueNeedsThree() {
        val podcasts = listOf(pod(4))
        val fresh = ep("new", 4, 1, played = true)
        val two = listOf(ep("s1", 4, 70, queued = true), ep("s2", 4, 80, queued = true), fresh)
        assertTrue(LibraryCheckup.check(podcasts, two, now).none { it.kind == Kind.STALE_QUEUE })
        val three = two + ep("s3", 4, 90, queued = true)
        val f = LibraryCheckup.check(podcasts, three, now).single { it.kind == Kind.STALE_QUEUE }
        assertEquals(setOf("s1", "s2", "s3"), f.episodeIds.toSet())
    }
}
