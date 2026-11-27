package com.earshot.app.data

/**
 * Smart playlists (phase 7): saved filters over the episodes of subscribed podcasts. The filters
 * are stored as JSON (same keys on the phone and the web, see [PlaylistJson]) and synced; each
 * device works out the list from its own library. Pure logic here, unit-tested in SmartPlaylistsTest.
 */
data class PlaylistFilters(
    /** Feed addresses of the podcasts to include; empty = every subscribed podcast. */
    val podcasts: List<String> = emptyList(),
    val status: Status = Status.UNPLAYED,
    /** Only downloaded episodes (phone only: the web player has no downloads and ignores it). */
    val downloaded: Boolean = false,
    val minMinutes: Int? = null,
    val maxMinutes: Int? = null,
    /** Only episodes released in the last N days. */
    val withinDays: Int? = null,
    /** Comma-separated words or phrases; the title must contain one of them. Blank = any title. */
    val keywords: String = "",
    val sort: Sort = Sort.NEWEST,
    /** At most this many episodes. */
    val limit: Int = 100,
) {
    enum class Status(val key: String, val label: String) {
        UNPLAYED("unplayed", "Unplayed (incl. in progress)"),
        NOT_STARTED("not_started", "Not started"),
        IN_PROGRESS("in_progress", "In progress"),
        PLAYED("played", "Played"),
        ANY("any", "Any"),
        ;

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: UNPLAYED
        }
    }

    enum class Sort(val key: String, val label: String) {
        NEWEST("newest", "Newest first"),
        OLDEST("oldest", "Oldest first"),
        SHORTEST("shortest", "Shortest first"),
        LONGEST("longest", "Longest first"),
        ;

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: NEWEST
        }
    }

    /** A one-line summary of the filters, e.g. "3 podcasts · unplayed · under 30 min · newest first". */
    fun summary(podcastTitles: (String) -> String?): String {
        val parts = mutableListOf<String>()
        parts += when (podcasts.size) {
            0 -> "All podcasts"
            1 -> podcastTitles(podcasts[0]) ?: "1 podcast"
            else -> "${podcasts.size} podcasts"
        }
        if (status != Status.ANY) parts += status.label.substringBefore(" (").lowercase()
        if (downloaded) parts += "downloaded"
        val min = minMinutes?.takeIf { it > 0 }
        val max = maxMinutes?.takeIf { it > 0 }
        when {
            min != null && max != null -> parts += "$min–$max min"
            min != null -> parts += "over $min min"
            max != null -> parts += "under $max min"
        }
        withinDays?.takeIf { it > 0 }?.let { parts += "last $it days" }
        if (Automation.words(keywords).isNotEmpty()) parts += "\"${keywords.trim()}\""
        parts += sort.label.lowercase()
        return parts.joinToString(" · ")
    }

    companion object {
        val DAY_CHOICES = listOf(1, 3, 7, 14, 30, 60, 90, 365)
        val LIMIT_CHOICES = listOf(10, 25, 50, 100, 200)
    }
}

/** What the filters look at for one episode. */
data class PlaylistCandidate(
    val id: String,
    /** [Repository.feedKey] of its podcast's feed. */
    val feedKey: String,
    val title: String,
    val pubDate: Long,
    val durationMs: Long,
    val positionMs: Long,
    val played: Boolean,
    val downloaded: Boolean,
)

object SmartPlaylists {
    /** The ids of the episodes in the playlist, in order. [feedKey] turns a feed address into its key. */
    fun select(
        filters: PlaylistFilters,
        candidates: List<PlaylistCandidate>,
        now: Long,
        feedKey: (String) -> String,
    ): List<String> {
        val keys = filters.podcasts.map(feedKey).toSet()
        val words = Automation.words(filters.keywords)
        val min = filters.minMinutes?.takeIf { it > 0 }
        val max = filters.maxMinutes?.takeIf { it > 0 }
        val since = filters.withinDays?.takeIf { it > 0 }?.let { now - it * 24L * 60 * 60 * 1000 }
        val matching = candidates.filter { c ->
            (keys.isEmpty() || c.feedKey in keys) &&
                when (filters.status) {
                    PlaylistFilters.Status.UNPLAYED -> !c.played
                    PlaylistFilters.Status.NOT_STARTED -> !c.played && c.positionMs == 0L
                    PlaylistFilters.Status.IN_PROGRESS -> !c.played && c.positionMs > 0
                    PlaylistFilters.Status.PLAYED -> c.played
                    PlaylistFilters.Status.ANY -> true
                } &&
                (!filters.downloaded || c.downloaded) &&
                // Unknown length (0) only passes when there's no length filter.
                (min == null || c.durationMs >= min * 60_000L) &&
                (max == null || (c.durationMs in 1..max * 60_000L)) &&
                (since == null || c.pubDate >= since) &&
                (words.isEmpty() || Automation.normalize(c.title).let { t -> words.any { it in t } })
        }
        val sorted = when (filters.sort) {
            PlaylistFilters.Sort.NEWEST -> matching.sortedByDescending { it.pubDate }
            PlaylistFilters.Sort.OLDEST -> matching.sortedBy { it.pubDate }
            // Unknown lengths go last either way.
            PlaylistFilters.Sort.SHORTEST -> matching.sortedWith(compareBy<PlaylistCandidate> { it.durationMs <= 0 }.thenBy { it.durationMs })
            PlaylistFilters.Sort.LONGEST -> matching.sortedWith(compareBy<PlaylistCandidate> { it.durationMs <= 0 }.thenByDescending { it.durationMs })
        }
        return sorted.take(filters.limit.coerceIn(1, 500)).map { it.id }
    }
}
