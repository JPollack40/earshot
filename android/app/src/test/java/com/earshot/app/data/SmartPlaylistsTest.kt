package com.earshot.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartPlaylistsTest {
    private val min = 60_000L
    private val day = 24L * 60 * 60 * 1000
    private val now = 400 * day

    private fun ep(id: String, feed: String, ageDays: Int, minutes: Long, position: Long = 0, played: Boolean = false, downloaded: Boolean = false, title: String = id) =
        PlaylistCandidate(id, Repository.feedKey(feed), title, now - ageDays * day, minutes * min, position, played, downloaded)

    private val a = "https://a.example/feed"
    private val b = "https://www.B.example/feed/"
    private val all = listOf(
        ep("a-new-short", a, 1, 20),
        ep("a-old-long", a, 100, 90),
        ep("a-started", a, 3, 50, position = 10 * min, title = "Interview: octopus"),
        ep("a-played", a, 2, 30, played = true),
        ep("b-new", b, 0, 45, downloaded = true),
        ep("b-unknown", b, 5, 0),
    )

    private fun select(f: PlaylistFilters) = SmartPlaylists.select(f, all, now, Repository::feedKey)

    @Test
    fun defaultIsUnplayedNewestFirst() =
        assertEquals(listOf("b-new", "a-new-short", "a-started", "b-unknown", "a-old-long"), select(PlaylistFilters()))

    @Test
    fun podcastsMatchByFeedKey() =
        // Listed differently from how it's stored (http, upper case, no www, no trailing slash).
        assertEquals(listOf("b-new", "b-unknown"), select(PlaylistFilters(podcasts = listOf("http://b.example/feed"))))

    @Test
    fun shortOnesSkipUnknownLength() =
        assertEquals(listOf("a-new-short"), select(PlaylistFilters(maxMinutes = 30, sort = PlaylistFilters.Sort.SHORTEST)))

    @Test
    fun inProgressAndPlayed() {
        assertEquals(listOf("a-started"), select(PlaylistFilters(status = PlaylistFilters.Status.IN_PROGRESS)))
        assertEquals(listOf("a-played"), select(PlaylistFilters(status = PlaylistFilters.Status.PLAYED)))
        assertEquals(6, select(PlaylistFilters(status = PlaylistFilters.Status.ANY)).size)
    }

    @Test
    fun recentDownloadedKeywordsAndLimit() {
        assertEquals(listOf("b-new", "a-new-short", "a-started"), select(PlaylistFilters(withinDays = 3)))
        assertEquals(listOf("b-new"), select(PlaylistFilters(downloaded = true)))
        assertEquals(listOf("a-started"), select(PlaylistFilters(keywords = "octopus, squid")))
        assertEquals(listOf("a-old-long", "a-started"), select(PlaylistFilters(sort = PlaylistFilters.Sort.LONGEST, limit = 2)))
        assertEquals(listOf("a-old-long", "b-unknown", "a-started", "a-new-short", "b-new"), select(PlaylistFilters(sort = PlaylistFilters.Sort.OLDEST)))
    }
}
