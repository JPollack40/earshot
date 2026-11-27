package com.earshot.app.data

import com.earshot.app.data.db.Podcast

/**
 * Automation rules (phase 7). Pure logic, no Android, so it's unit-tested (AutomationTest).
 *
 * New-episode rules are per podcast and run when a refresh finds new episodes:
 *  - filters: "only if the title contains" (any of a comma-separated list), "skip if the title
 *    contains" (any), and a length range in minutes (episodes of unknown length pass);
 *  - an episode that fails a filter is skipped (not added anywhere, like archiving it);
 *  - one that passes goes where [NewEpisodeAction] says: New Episodes (the default), the top of
 *    the queue (play next), the end of the queue, or nowhere (skip).
 *
 * Tidy-up rules keep New Episodes short. Both are off by default; each podcast can override the
 * app-wide default (Settings > Automation):
 *  - archive New Episodes released more than N days ago;
 *  - keep only the latest N of a podcast in New Episodes.
 * Queued episodes are never touched (they aren't in New Episodes anyway).
 */
object Automation {
    /** Where a new episode goes. Stored in [Podcast.newEpisodeAction]; null = [INBOX]. */
    enum class NewEpisodeAction(val key: String, val label: String) {
        INBOX("inbox", "New Episodes"),
        QUEUE_NEXT("queue_next", "Top of queue (play next)"),
        QUEUE_LAST("queue_last", "End of queue"),
        SKIP("skip", "Skip (don't add)"),
        ;

        companion object {
            fun of(key: String?): NewEpisodeAction = entries.firstOrNull { it.key == key } ?: INBOX
        }
    }

    /** Choices offered for "archive New Episodes after"; 0 = never. */
    val INBOX_DAY_CHOICES = listOf(0, 3, 7, 14, 30, 60)

    /** Choices offered for "keep only the latest"; 0 = no limit. */
    val INBOX_KEEP_CHOICES = listOf(0, 1, 2, 3, 5, 10)

    /** Choices for the length filters, in minutes. */
    val MINUTE_CHOICES = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)

    /** Comma-separated words or phrases, trimmed and lower-cased; blanks dropped. */
    fun words(list: String?): List<String> =
        list.orEmpty().split(',').map { normalize(it).trim() }.filter { it.isNotEmpty() }

    /** Lower case, with curly quotes and apostrophes made straight, so "don't" matches "don’t". */
    fun normalize(s: String): String = s.lowercase()
        .replace('‘', '\'').replace('’', '\'')
        .replace('“', '"').replace('”', '"')

    /** True if a new episode passes the podcast's filters. */
    fun passesFilters(
        title: String,
        durationMs: Long,
        includeWords: String?,
        excludeWords: String?,
        minMinutes: Int?,
        maxMinutes: Int?,
    ): Boolean {
        val t = normalize(title)
        val include = words(includeWords)
        if (include.isNotEmpty() && include.none { it in t }) return false
        if (words(excludeWords).any { it in t }) return false
        if (durationMs > 0) {
            val minutes = durationMs / 60_000.0
            if (minMinutes != null && minMinutes > 0 && minutes < minMinutes) return false
            if (maxMinutes != null && maxMinutes > 0 && minutes > maxMinutes) return false
        }
        return true
    }

    /** What to do with a new episode of [podcast]. */
    fun actionFor(podcast: Podcast, title: String, durationMs: Long): NewEpisodeAction =
        if (!passesFilters(title, durationMs, podcast.includeWords, podcast.excludeWords, podcast.minMinutes, podcast.maxMinutes)) {
            NewEpisodeAction.SKIP
        } else {
            NewEpisodeAction.of(podcast.newEpisodeAction)
        }

    /** True when the podcast has any new-episode rule set (shown as "Rules on" on its page). */
    fun hasRules(p: Podcast): Boolean =
        NewEpisodeAction.of(p.newEpisodeAction) != NewEpisodeAction.INBOX ||
            words(p.includeWords).isNotEmpty() || words(p.excludeWords).isNotEmpty() ||
            (p.minMinutes ?: 0) > 0 || (p.maxMinutes ?: 0) > 0 ||
            p.inboxDays != null || p.inboxKeep != null

    /** A New Episodes entry, as far as tidying is concerned. */
    data class InboxEntry(val id: String, val podcastId: Long, val pubDate: Long)

    /**
     * Which New Episodes entries the tidy-up rules archive now. [days] and [keep] give each
     * podcast's effective values (its own, else the defaults); 0 = off.
     */
    fun toArchive(
        inbox: List<InboxEntry>,
        now: Long,
        days: (Long) -> Int,
        keep: (Long) -> Int,
    ): Set<String> {
        val out = mutableSetOf<String>()
        for ((podcastId, entries) in inbox.groupBy { it.podcastId }) {
            val d = days(podcastId)
            if (d > 0) {
                val cutoff = now - d * DAY_MS
                entries.filter { it.pubDate in 1 until cutoff }.forEach { out += it.id }
            }
            val k = keep(podcastId)
            if (k > 0) {
                entries.sortedByDescending { it.pubDate }.drop(k).forEach { out += it.id }
            }
        }
        return out
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}

/** App-wide tidy-up defaults (Settings > Automation). Synced with the playback defaults. 0 = off. */
data class TidyDefaults(val inboxDays: Int = 0, val inboxKeep: Int = 0)
