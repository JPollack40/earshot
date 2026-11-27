package com.earshot.app.data.stats

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Stats page maths (phase "Stats"), kept free of Android so it can be unit tested. The web player
 * has the same rules in web/src/stats.ts; keep the two in step.
 *
 * Input is the listening history (the `listens` table, phase 7): one [ListenEntry] per stretch of
 * listening. A stretch's real listening time is spread evenly over the wall-clock span it covers and
 * split at local hour boundaries, so a stretch that crosses midnight or the edge of a period counts
 * on both sides (AntennaPod's stats put a whole episode in the month it was last played; this
 * doesn't).
 *
 * Weeks start on Monday (decided overnight 2026-11-27; please confirm). A day counts as a listening
 * day from [ACTIVE_DAY_MS] of listening.
 */
data class ListenEntry(
    val episodeId: String,
    val feedUrl: String,
    val podcastTitle: String,
    val episodeTitle: String,
    val startedAt: Long,
    val endedAt: Long,
    val listenedMs: Long,
    val toMs: Long,
    val speed: Float,
    val device: String,
)

/** The switchable ranges on the Stats page, ending today (like Stats.fm's 4 weeks / 6 months / lifetime). */
enum class StatsRange(val label: String) {
    WEEK("7 days"),
    FOUR_WEEKS("4 weeks"),
    SIX_MONTHS("6 months"),
    ALL("All time"),
}

/** Calendar periods for the week, month and year in review. */
enum class ReviewKind { WEEK, MONTH, YEAR }

enum class Granularity { DAY, WEEK, MONTH }

data class Period(val from: Long, val to: Long)

data class PodcastTotal(val feedUrl: String, val title: String, val ms: Long, val episodes: Int)

data class EpisodeTotal(val episodeId: String, val title: String, val podcastTitle: String, val feedUrl: String, val ms: Long)

/** A bar in the timeline (a day, week or month starting at [start]) or a single day. */
data class Bucket(val start: Long, val ms: Long)

data class Review(val kind: ReviewKind, val from: Long, val to: Long, val totalMs: Long, val inProgress: Boolean)

data class StatsResult(
    val from: Long,
    val to: Long,
    val totalMs: Long,
    val episodes: Int,
    val podcasts: Int,
    val activeDays: Int,
    val days: Int,
    /** The same length of time just before; null when history doesn't reach back that far. */
    val previousTotalMs: Long?,
    val topPodcasts: List<PodcastTotal>,
    val topEpisodes: List<EpisodeTotal>,
    /** Listening per local hour of the day, 0 = midnight to 1 AM. */
    val hours: LongArray,
    /** Listening per day of the week, 0 = Monday. */
    val weekdays: LongArray,
    val timeline: List<Bucket>,
    val granularity: Granularity,
    /** "phone", "car", "web" (always in that order, zeros included). */
    val devices: List<Pair<String, Long>>,
    /** Time saved by playing faster than 1× (Smart Speed's trimmed silences aren't in the history). */
    val savedBySpeedMs: Long,
    /** Speed weighted by time listened; 1 when nothing was heard. */
    val averageSpeed: Float,
    /** Episodes heard to within a minute of the end in this period; null when no lengths are known. */
    val finished: Int?,
    /** Podcasts first heard in this period; null when history starts inside the period (all would be "new"). */
    val newPodcasts: List<PodcastTotal>?,
    val longestStreak: Int,
    val busiestDay: Bucket?,
)

data class Streaks(val current: Int, val longest: Int)

object ListeningStats {
    const val ACTIVE_DAY_MS = 60_000L
    private const val HOUR_MS = 3_600_000L

    /** Start of the local day [ms] falls in. */
    fun startOfDay(ms: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    private fun date(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private fun LocalDate.ms(zone: ZoneId): Long = atStartOfDay(zone).toInstant().toEpochMilli()

    /** The range ending now: whole local days, today included. All time starts on the first day with history. */
    fun period(range: StatsRange, now: Long, zone: ZoneId, historyStart: Long?): Period {
        val today = date(now, zone)
        val to = today.plusDays(1).ms(zone)
        val fromDate = when (range) {
            StatsRange.WEEK -> today.minusDays(6)
            StatsRange.FOUR_WEEKS -> today.minusDays(27)
            StatsRange.SIX_MONTHS -> today.minusMonths(6).plusDays(1)
            StatsRange.ALL -> historyStart?.let { date(it, zone) } ?: today
        }
        return Period(fromDate.ms(zone), to)
    }

    /** The calendar week (Monday to Sunday), month or year containing [at]. */
    fun reviewPeriod(kind: ReviewKind, at: Long, zone: ZoneId): Period {
        val d = date(at, zone)
        val start = when (kind) {
            ReviewKind.WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            ReviewKind.MONTH -> d.withDayOfMonth(1)
            ReviewKind.YEAR -> d.withDayOfYear(1)
        }
        val end = when (kind) {
            ReviewKind.WEEK -> start.plusWeeks(1)
            ReviewKind.MONTH -> start.plusMonths(1)
            ReviewKind.YEAR -> start.plusYears(1)
        }
        return Period(start.ms(zone), end.ms(zone))
    }

    /** The calendar period just before [p] (same kind). */
    fun previousReview(kind: ReviewKind, p: Period, zone: ZoneId): Period = reviewPeriod(kind, p.from - 1, zone)

    /**
     * Splits each stretch at local hour boundaries, clipped to [from, to), and hands each piece to
     * [slice] with its share of the real listening time.
     */
    private inline fun slices(e: ListenEntry, from: Long, to: Long, zone: ZoneId, slice: (start: Long, ms: Double) -> Unit) {
        if (e.listenedMs <= 0) return
        val span = e.endedAt - e.startedAt
        if (span <= 0) {
            if (e.startedAt in from until to) slice(e.startedAt, e.listenedMs.toDouble())
            return
        }
        val start = maxOf(e.startedAt, from)
        val end = minOf(e.endedAt, to)
        if (end <= start) return
        var t = start
        var hour = Instant.ofEpochMilli(start).atZone(zone).truncatedTo(ChronoUnit.HOURS)
        while (t < end) {
            val next = minOf(hour.plusHours(1).toInstant().toEpochMilli(), end)
            if (next > t) slice(t, e.listenedMs.toDouble() * (next - t) / span)
            t = next
            hour = hour.plusHours(1)
        }
    }

    /** Listening per local day over all of [entries], keyed by the day's start. */
    private fun daily(entries: List<ListenEntry>, zone: ZoneId): Map<LocalDate, Double> {
        val m = HashMap<LocalDate, Double>()
        for (e in entries) slices(e, Long.MIN_VALUE, Long.MAX_VALUE, zone) { s, ms ->
            val d = date(s, zone)
            m[d] = (m[d] ?: 0.0) + ms
        }
        return m
    }

    /** Days in a row with listening: the run ending today (or yesterday, if today has none yet) and the longest ever. */
    fun streaks(entries: List<ListenEntry>, now: Long, zone: ZoneId): Streaks {
        val days = daily(entries, zone).filterValues { it >= ACTIVE_DAY_MS }.keys
        if (days.isEmpty()) return Streaks(0, 0)
        val sorted = days.sorted()
        var longest = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i - 1].plusDays(1) == sorted[i]) run + 1 else 1
            longest = maxOf(longest, run)
        }
        val today = date(now, zone)
        var d = if (today in days) today else today.minusDays(1)
        var current = 0
        while (d in days) {
            current++
            d = d.minusDays(1)
        }
        return Streaks(current, longest)
    }

    private fun granularityFor(p: Period): Granularity {
        val days = (p.to - p.from) / (24 * HOUR_MS)
        return when {
            days <= 45 -> Granularity.DAY
            days <= 200 -> Granularity.WEEK
            else -> Granularity.MONTH
        }
    }

    private fun bucketStart(d: LocalDate, g: Granularity): LocalDate = when (g) {
        Granularity.DAY -> d
        Granularity.WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Granularity.MONTH -> d.withDayOfMonth(1)
    }

    private fun nextBucket(d: LocalDate, g: Granularity): LocalDate = when (g) {
        Granularity.DAY -> d.plusDays(1)
        Granularity.WEEK -> d.plusWeeks(1)
        Granularity.MONTH -> d.plusMonths(1)
    }

    /**
     * Everything the Stats page shows for [p]. [feedKey] groups a podcast's feed addresses that
     * differ only in spelling (http/https, www, trailing slash). [durationOf] gives an episode's
     * length when known (for "finished").
     */
    fun compute(
        entries: List<ListenEntry>,
        p: Period,
        zone: ZoneId,
        feedKey: (String) -> String = { it },
        durationOf: (String) -> Long? = { null },
        previous: Period? = null,
    ): StatsResult {
        val historyStart = entries.minOfOrNull { it.startedAt }
        val hours = DoubleArray(24)
        val weekdays = DoubleArray(7)
        val byDay = HashMap<LocalDate, Double>()
        val pods = LinkedHashMap<String, Triple<String, String, Double>>() // key -> (feedUrl, title, ms)
        val podEpisodes = HashMap<String, MutableSet<String>>()
        val eps = LinkedHashMap<String, Pair<ListenEntry, Double>>()
        val devices = linkedMapOf("phone" to 0.0, "car" to 0.0, "web" to 0.0)
        var total = 0.0
        var speedWeighted = 0.0
        var saved = 0.0
        val reachedEnd = HashSet<String>()

        // Newest first, so a podcast or episode shows its most recent title.
        for (e in entries.sortedByDescending { it.startedAt }) {
            var inPeriod = 0.0
            slices(e, p.from, p.to, zone) { s, ms ->
                val z = Instant.ofEpochMilli(s).atZone(zone)
                hours[z.hour] += ms
                weekdays[z.dayOfWeek.value - 1] += ms
                val d = z.toLocalDate()
                byDay[d] = (byDay[d] ?: 0.0) + ms
                inPeriod += ms
            }
            if (inPeriod <= 0.0) continue
            total += inPeriod
            val speed = if (e.speed > 0f) e.speed.toDouble() else 1.0
            speedWeighted += inPeriod * speed
            saved += inPeriod * (speed - 1.0)
            val device = if (e.device in devices) e.device else "phone"
            devices[device] = devices.getValue(device) + inPeriod
            val key = feedKey(e.feedUrl).ifEmpty { "title:" + e.podcastTitle }
            val prev = pods[key]
            pods[key] = Triple(prev?.first ?: e.feedUrl, prev?.second?.takeIf { it.isNotBlank() } ?: e.podcastTitle, (prev?.third ?: 0.0) + inPeriod)
            podEpisodes.getOrPut(key) { HashSet() } += e.episodeId
            val pe = eps[e.episodeId]
            eps[e.episodeId] = (pe?.first ?: e) to ((pe?.second ?: 0.0) + inPeriod)
            val dur = durationOf(e.episodeId)
            if (dur != null && dur > 0 && e.toMs >= dur - 60_000) reachedEnd += e.episodeId
        }

        val topPodcasts = pods.map { (k, v) -> PodcastTotal(v.first, v.second, v.third.toLong(), podEpisodes[k]?.size ?: 0) }
            .filter { it.ms > 0 }
            .sortedByDescending { it.ms }
        val topEpisodes = eps.values.map { (e, ms) -> EpisodeTotal(e.episodeId, e.episodeTitle, e.podcastTitle, e.feedUrl, ms.toLong()) }
            .filter { it.ms > 0 }
            .sortedByDescending { it.ms }

        // Timeline: every bucket in the period, zeros included, so gaps show.
        val g = granularityFor(p)
        val timeline = ArrayList<Bucket>()
        val lastDay = date(p.to - 1, zone)
        var b = bucketStart(date(p.from, zone), g)
        val byBucket = HashMap<LocalDate, Double>()
        for ((d, ms) in byDay) byBucket[bucketStart(d, g)] = (byBucket[bucketStart(d, g)] ?: 0.0) + ms
        while (!b.isAfter(lastDay)) {
            timeline += Bucket(b.ms(zone), (byBucket[b] ?: 0.0).toLong())
            b = nextBucket(b, g)
        }

        // Streak inside the period.
        val active = byDay.filterValues { it >= ACTIVE_DAY_MS }.keys.sorted()
        var longest = if (active.isEmpty()) 0 else 1
        var run = 1
        for (i in 1 until active.size) {
            run = if (active[i - 1].plusDays(1) == active[i]) run + 1 else 1
            longest = maxOf(longest, run)
        }
        val busiest = byDay.maxByOrNull { it.value }?.takeIf { it.value >= ACTIVE_DAY_MS }?.let { Bucket(it.key.ms(zone), it.value.toLong()) }

        val previousTotal = previous?.takeIf { historyStart != null && startOfDay(historyStart, zone) <= it.from }?.let { prevP ->
            var t = 0.0
            for (e in entries) slices(e, prevP.from, prevP.to, zone) { _, ms -> t += ms }
            t.toLong()
        }

        val newPodcasts = if (historyStart == null || startOfDay(historyStart, zone) >= p.from) null else {
            val first = HashMap<String, Long>()
            for (e in entries) {
                val key = feedKey(e.feedUrl).ifEmpty { "title:" + e.podcastTitle }
                first[key] = minOf(first[key] ?: Long.MAX_VALUE, e.startedAt)
            }
            topPodcasts.filter { first[feedKey(it.feedUrl).ifEmpty { "title:" + it.title }]?.let { f -> f >= p.from } == true }
        }

        val anyDuration = eps.keys.any { (durationOf(it) ?: 0L) > 0 }
        val days = ChronoUnit.DAYS.between(date(p.from, zone), lastDay).toInt() + 1

        return StatsResult(
            from = p.from,
            to = p.to,
            totalMs = total.toLong(),
            episodes = topEpisodes.size,
            podcasts = topPodcasts.size,
            activeDays = active.size,
            days = days,
            previousTotalMs = previousTotal,
            topPodcasts = topPodcasts,
            topEpisodes = topEpisodes,
            hours = LongArray(24) { hours[it].toLong() },
            weekdays = LongArray(7) { weekdays[it].toLong() },
            timeline = timeline,
            granularity = g,
            devices = devices.map { it.key to it.value.toLong() },
            savedBySpeedMs = saved.toLong().coerceAtLeast(0),
            averageSpeed = if (total > 0) (speedWeighted / total).toFloat() else 1f,
            finished = if (anyDuration) reachedEnd.size else null,
            newPodcasts = newPodcasts,
            longestStreak = longest,
            busiestDay = busiest,
        )
    }

    /**
     * The 3-hour stretch of the day with the most listening (wrapping past midnight), as its first
     * hour; null when there's too little to say.
     */
    fun peakHours(hours: LongArray): Int? {
        if (hours.sum() < 10 * 60_000L) return null
        var best = -1L
        var bestAt = 0
        for (h in 0 until 24) {
            val sum = hours[h] + hours[(h + 1) % 24] + hours[(h + 2) % 24]
            if (sum > best) {
                best = sum
                bestAt = h
            }
        }
        return bestAt
    }

    /**
     * Past and current weeks, months and years that have listening, newest first: the last 12
     * weeks, the last 12 months and every year. Reviews are worked out from the history whenever
     * they're opened, so every past one stays available (Pocket Casts and AntennaPod Echo only show
     * the current year's).
     */
    fun reviews(entries: List<ListenEntry>, now: Long, zone: ZoneId): List<Review> {
        val days = daily(entries, zone)
        if (days.isEmpty()) return emptyList()
        val out = ArrayList<Review>()
        for ((kind, limit) in listOf(ReviewKind.WEEK to 12, ReviewKind.MONTH to 12, ReviewKind.YEAR to 100)) {
            val totals = LinkedHashMap<Long, Double>()
            for ((d, ms) in days) {
                val p = reviewPeriod(kind, d.ms(zone), zone)
                totals[p.from] = (totals[p.from] ?: 0.0) + ms
            }
            totals.entries.filter { it.value >= ACTIVE_DAY_MS }
                .sortedByDescending { it.key }
                .take(limit)
                .forEach { (from, ms) ->
                    val p = reviewPeriod(kind, from, zone)
                    out += Review(kind, p.from, p.to, ms.toLong(), inProgress = now < p.to)
                }
        }
        return out
    }
}
