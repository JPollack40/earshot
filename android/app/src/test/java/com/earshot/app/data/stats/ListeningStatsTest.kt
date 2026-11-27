package com.earshot.app.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ListeningStatsTest {
    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val min = 60_000L

    /** Local time in [zone] as epoch ms. */
    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun listen(
        start: Long,
        minutes: Long,
        feed: String = "https://a.example/feed",
        podcast: String = "Alpha",
        episode: String = "a1",
        speed: Float = 1f,
        device: String = "phone",
        toMs: Long = 0,
    ) = ListenEntry(episode, feed, podcast, "Episode $episode", start, start + minutes * min, minutes * min, toMs, speed, device)

    // Friday 27 Nov 2026, 9 PM local.
    private val now = at(2026, 11, 27, 21)

    @Test
    fun emptyHistoryGivesZeros() {
        val r = ListeningStats.compute(emptyList(), ListeningStats.period(StatsRange.WEEK, now, zone, null), zone)
        assertEquals(0L, r.totalMs)
        assertEquals(0, r.episodes)
        assertEquals(7, r.days)
        assertEquals(7, r.timeline.size)
        assertNull(r.busiestDay)
        assertNull(r.newPodcasts) // no history at all: nothing is "new"
        assertEquals(1f, r.averageSpeed)
    }

    @Test
    fun totalsTopListsAndDevices() {
        val entries = listOf(
            listen(at(2026, 11, 27, 8), 30, episode = "a1"),
            listen(at(2026, 11, 27, 18), 20, episode = "a2", device = "car"),
            listen(at(2026, 11, 26, 12), 45, feed = "https://b.example/feed", podcast = "Beta", episode = "b1", device = "web"),
        )
        val r = ListeningStats.compute(entries, ListeningStats.period(StatsRange.WEEK, now, zone, entries.minOf { it.startedAt }), zone)
        assertEquals(95 * min, r.totalMs)
        assertEquals(3, r.episodes)
        assertEquals(2, r.podcasts)
        assertEquals(2, r.activeDays)
        assertEquals(listOf("Alpha", "Beta"), r.topPodcasts.map { it.title })
        assertEquals(2, r.topPodcasts[0].episodes)
        assertEquals("b1", r.topEpisodes[0].episodeId)
        assertEquals(listOf("phone" to 30 * min, "car" to 20 * min, "web" to 45 * min), r.devices)
        assertEquals(30 * min, r.hours[8])
        assertEquals(20 * min, r.hours[18])
        // Thursday 26th = index 3, Friday 27th = index 4.
        assertEquals(45 * min, r.weekdays[3])
        assertEquals(50 * min, r.weekdays[4])
        assertEquals(at(2026, 11, 27), r.busiestDay?.start)
    }

    @Test
    fun stretchAcrossMidnightCountsOnBothDays() {
        // 11:40 PM to 12:20 AM: 20 minutes on each day, and in hours 23 and 0.
        val e = listen(at(2026, 11, 25, 23, 40), 40)
        val r = ListeningStats.compute(listOf(e), ListeningStats.period(StatsRange.WEEK, now, zone, e.startedAt), zone)
        assertEquals(20 * min, r.hours[23])
        assertEquals(20 * min, r.hours[0])
        assertEquals(2, r.activeDays)
        assertEquals(2, r.longestStreak)
        val byDay = r.timeline.associate { it.start to it.ms }
        assertEquals(20 * min, byDay[at(2026, 11, 25)])
        assertEquals(20 * min, byDay[at(2026, 11, 26)])
    }

    @Test
    fun periodEdgeClipsStretch() {
        // Week in review for Mon 23 – Sun 29 Nov; a stretch from Sun 22 Nov 11:30 PM for an hour.
        val e = listen(at(2026, 11, 22, 23, 30), 60)
        val week = ListeningStats.reviewPeriod(ReviewKind.WEEK, now, zone)
        assertEquals(at(2026, 11, 23), week.from)
        assertEquals(at(2026, 11, 30), week.to)
        val r = ListeningStats.compute(listOf(e), week, zone)
        assertEquals(30 * min, r.totalMs)
    }

    @Test
    fun pausedTimeInsideAStretchIsNotCounted() {
        // 40 minutes of wall clock, but only 20 heard: hour slices share the 20 minutes.
        val e = ListenEntry("a1", "f", "Alpha", "E", at(2026, 11, 27, 9, 40), at(2026, 11, 27, 10, 20), 20 * min, 0, 1f, "phone")
        val r = ListeningStats.compute(listOf(e), ListeningStats.period(StatsRange.WEEK, now, zone, e.startedAt), zone)
        assertEquals(20 * min, r.totalMs)
        assertEquals(10 * min, r.hours[9])
        assertEquals(10 * min, r.hours[10])
    }

    @Test
    fun speedSavingsAndAverage() {
        val entries = listOf(
            listen(at(2026, 11, 27, 8), 60, speed = 1.5f),
            listen(at(2026, 11, 27, 10), 60, speed = 1f, episode = "a2"),
        )
        val r = ListeningStats.compute(entries, ListeningStats.period(StatsRange.WEEK, now, zone, entries.minOf { it.startedAt }), zone)
        assertEquals(30 * min, r.savedBySpeedMs)
        assertEquals(1.25f, r.averageSpeed, 0.001f)
    }

    @Test
    fun feedSpellingsGroupTogether() {
        val entries = listOf(
            listen(at(2026, 11, 27, 8), 10, feed = "https://www.a.example/feed/"),
            listen(at(2026, 11, 27, 9), 10, feed = "http://a.example/feed", episode = "a2"),
        )
        val key = { u: String -> u.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/') }
        val r = ListeningStats.compute(entries, ListeningStats.period(StatsRange.WEEK, now, zone, entries.minOf { it.startedAt }), zone, feedKey = key)
        assertEquals(1, r.podcasts)
        assertEquals(2, r.topPodcasts[0].episodes)
    }

    @Test
    fun finishedNeedsKnownLength() {
        val entries = listOf(
            listen(at(2026, 11, 27, 8), 30, episode = "a1", toMs = 59 * min),
            listen(at(2026, 11, 27, 9), 30, episode = "a2", toMs = 20 * min),
        )
        val p = ListeningStats.period(StatsRange.WEEK, now, zone, entries.minOf { it.startedAt })
        assertNull(ListeningStats.compute(entries, p, zone).finished)
        val r = ListeningStats.compute(entries, p, zone, durationOf = { 60 * min })
        assertEquals(1, r.finished)
    }

    @Test
    fun previousPeriodOnlyWhenHistoryReachesIt() {
        val first = listen(at(2026, 11, 27, 8), 30)
        val p = ListeningStats.reviewPeriod(ReviewKind.WEEK, now, zone)
        val prev = ListeningStats.previousReview(ReviewKind.WEEK, p, zone)
        assertNull(ListeningStats.compute(listOf(first), p, zone, previous = prev).previousTotalMs)
        val older = listen(at(2026, 11, 15, 8), 15, episode = "a0")
        assertEquals(0L, ListeningStats.compute(listOf(first, older), p, zone, previous = prev).previousTotalMs)
        val lastWeek = listen(at(2026, 11, 18, 8), 25, episode = "a00")
        assertEquals(25 * min, ListeningStats.compute(listOf(first, older, lastWeek), p, zone, previous = prev).previousTotalMs)
    }

    @Test
    fun newPodcastsOnlyAfterHistoryStarts() {
        val old = listen(at(2026, 11, 2, 8), 30)
        val fresh = listen(at(2026, 11, 26, 8), 30, feed = "https://b.example/feed", podcast = "Beta", episode = "b1")
        val again = listen(at(2026, 11, 27, 8), 30, episode = "a2")
        val r = ListeningStats.compute(listOf(old, fresh, again), ListeningStats.period(StatsRange.WEEK, now, zone, old.startedAt), zone)
        assertEquals(listOf("Beta"), r.newPodcasts?.map { it.title })
        // History starting inside the period: everything would be new, so it isn't shown.
        assertNull(ListeningStats.compute(listOf(fresh, again), ListeningStats.period(StatsRange.WEEK, now, zone, fresh.startedAt), zone).newPodcasts)
    }

    @Test
    fun streaksCountRunsEndingTodayOrYesterday() {
        val days = listOf(20, 21, 22, 25, 26)
        val entries = days.map { listen(at(2026, 11, it, 8), 10, episode = "e$it") }
        assertEquals(Streaks(current = 2, longest = 3), ListeningStats.streaks(entries, now, zone)) // 27th: nothing yet
        val today = entries + listen(at(2026, 11, 27, 8), 10, episode = "t")
        assertEquals(Streaks(current = 3, longest = 3), ListeningStats.streaks(today, now, zone))
        val tiny = listOf(listen(at(2026, 11, 27, 8), 0)) // under a minute doesn't count
        assertEquals(Streaks(0, 0), ListeningStats.streaks(tiny, now, zone))
    }

    @Test
    fun timelineGranularity() {
        val e = listen(at(2026, 6, 1, 8), 30)
        assertEquals(Granularity.DAY, ListeningStats.compute(listOf(e), ListeningStats.period(StatsRange.FOUR_WEEKS, now, zone, e.startedAt), zone).granularity)
        val six = ListeningStats.compute(listOf(e), ListeningStats.period(StatsRange.SIX_MONTHS, now, zone, e.startedAt), zone)
        assertEquals(Granularity.WEEK, six.granularity)
        assertTrue(six.timeline.size in 26..28)
        val year = ListeningStats.compute(listOf(e), ListeningStats.reviewPeriod(ReviewKind.YEAR, now, zone), zone)
        assertEquals(Granularity.MONTH, year.granularity)
        assertEquals(12, year.timeline.size)
        assertEquals(30 * min, year.timeline[5].ms)
    }

    @Test
    fun peakHoursWrapsMidnight() {
        val h = LongArray(24)
        h[23] = 20 * min; h[0] = 20 * min; h[1] = 5 * min; h[12] = 15 * min
        assertEquals(23, ListeningStats.peakHours(h))
        assertNull(ListeningStats.peakHours(LongArray(24).also { it[3] = min }))
    }

    @Test
    fun reviewsListNewestFirstWithCurrentMarked() {
        val entries = listOf(
            listen(at(2025, 12, 30, 8), 30, episode = "x"),
            listen(at(2026, 11, 10, 8), 30, episode = "y"),
            listen(at(2026, 11, 27, 8), 30, episode = "z"),
        )
        val reviews = ListeningStats.reviews(entries, now, zone)
        val weeks = reviews.filter { it.kind == ReviewKind.WEEK }
        assertEquals(listOf(at(2026, 11, 23), at(2026, 11, 9), at(2025, 12, 29)), weeks.map { it.from })
        assertTrue(weeks[0].inProgress)
        assertTrue(!weeks[1].inProgress)
        val years = reviews.filter { it.kind == ReviewKind.YEAR }
        assertEquals(listOf(at(2026, 1, 1), at(2025, 1, 1)), years.map { it.from })
        assertEquals(60 * min, years[0].totalMs)
    }

    @Test
    fun ranges() {
        val p = ListeningStats.period(StatsRange.WEEK, now, zone, null)
        assertEquals(at(2026, 11, 21), p.from)
        assertEquals(at(2026, 11, 28), p.to)
        assertEquals(at(2026, 10, 31), ListeningStats.period(StatsRange.FOUR_WEEKS, now, zone, null).from)
        assertEquals(at(2026, 5, 28), ListeningStats.period(StatsRange.SIX_MONTHS, now, zone, null).from)
        assertEquals(at(2026, 11, 2), ListeningStats.period(StatsRange.ALL, now, zone, at(2026, 11, 2, 15)).from)
    }
}
