package com.earshot.app.data

import com.earshot.app.data.MarkRange.Direction
import com.earshot.app.data.db.Episode
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkRangeTest {
    private fun ep(id: String, pub: Long, played: Boolean = false, queue: Long? = null, podcast: Long = 1) =
        Episode(id = id, podcastId = podcast, guid = id, title = id, pubDate = pub, audioUrl = "https://example.com/$id.mp3", played = played, queuePosition = queue)

    private val list = listOf(
        ep("e1", 100),
        ep("e2", 200, played = true),
        ep("e3", 300, queue = 0),
        ep("e4", 400),
        ep("e5", 500),
        ep("e6", 600),
        ep("e7", 700),
        ep("other", 50, podcast = 2),
    )

    @Test
    fun olderSkipsPlayedQueuedAndItself() {
        val r = MarkRange.pick(list, list[4], Direction.OLDER, loadedId = null)
        assertEquals(listOf("e1", "e4"), r.ids)
        assertEquals(1, r.skipped)
    }

    @Test
    fun newerSkipsTheLoadedEpisode() {
        val r = MarkRange.pick(list, list[4], Direction.NEWER, loadedId = "e7")
        assertEquals(listOf("e6"), r.ids)
        assertEquals(1, r.skipped)
    }

    @Test
    fun sameReleaseTimeIsLeftAlone() {
        val twins = listOf(ep("a", 100), ep("b", 100), ep("c", 50))
        assertEquals(listOf("c"), MarkRange.pick(twins, twins[0], Direction.OLDER, null).ids)
        assertEquals(emptyList<String>(), MarkRange.pick(twins, twins[0], Direction.NEWER, null).ids)
    }

    @Test
    fun nothingToDoAtTheEnds() {
        assertEquals(0, MarkRange.pick(list, list[0], Direction.OLDER, null).ids.size)
        assertEquals(0, MarkRange.pick(list, list[6], Direction.NEWER, null).ids.size)
    }
}
