package com.earshot.app.data

import com.earshot.app.data.db.Episode
import com.earshot.app.data.db.Podcast

/**
 * Phase 22 (B6): Library check-up, a card on Stats. Suggestions only: nothing changes until a fix is tapped.
 *  - Often passed over: the show's last 5 episodes that are done with (out of New Episodes and the queue,
 *    released over 2 weeks ago) were all left unplayed. Fix: stop adding its new episodes.
 *  - Gone quiet: nothing new for 90 days and the feed doesn't say it's finished. Fix: open it (to unsubscribe).
 *  - Always first: 4 of its last 5 episodes were started within a day of release, and new ones don't already
 *    go to the top of the queue. Fix: new episodes go to the top of the queue.
 *  - Stale queue: queued episodes released over 60 days ago and never started. Fix: take them out of the queue.
 */
object LibraryCheckup {
    const val DAY = 24 * 60 * 60_000L
    const val LOOK_AT = 5
    const val SETTLED_MS = 14 * DAY
    const val QUIET_MS = 90 * DAY
    const val STALE_MS = 60 * DAY

    enum class Kind { PASSED_OVER, QUIET, ALWAYS_FIRST, STALE_QUEUE }

    data class Finding(
        val kind: Kind,
        val podcastId: Long?,
        val title: String,
        val detail: String,
        /** Stale queue: the episodes it would take out. */
        val episodeIds: List<String> = emptyList(),
    )

    fun check(podcasts: List<Podcast>, episodes: List<Episode>, now: Long): List<Finding> {
        val byPodcast = episodes.groupBy { it.podcastId }
        val out = mutableListOf<Finding>()
        for (p in podcasts.filter { it.subscribed }) {
            val eps = byPodcast[p.id].orEmpty().sortedByDescending { it.pubDate }
            if (eps.isEmpty()) continue
            val action = Automation.NewEpisodeAction.of(p.newEpisodeAction)

            val settled = eps.filter { it.pubDate in 1 until now - SETTLED_MS && !it.inInbox && it.queuePosition == null }.take(LOOK_AT)
            if (action != Automation.NewEpisodeAction.SKIP && settled.size == LOOK_AT && settled.all { !it.played && it.positionMs == 0L && it.lastPlayedAt == 0L }) {
                out += Finding(Kind.PASSED_OVER, p.id, p.title, "Your last $LOOK_AT episodes went unplayed. Stop adding new ones?")
            }

            val newest = eps.first().pubDate
            if (!p.complete && newest in 1 until now - QUIET_MS) {
                out += Finding(Kind.QUIET, p.id, p.title, "Nothing new for ${(now - newest) / DAY} days.")
            }

            val recent = eps.filter { it.pubDate in 1 until now - DAY }.take(LOOK_AT)
            val quick = recent.count { it.lastPlayedAt > 0 && it.lastPlayedAt - it.pubDate in 0..DAY }
            if (action != Automation.NewEpisodeAction.QUEUE_NEXT && recent.size == LOOK_AT && quick >= LOOK_AT - 1) {
                out += Finding(Kind.ALWAYS_FIRST, p.id, p.title, "You start new episodes within a day. Put them at the top of your queue?")
            }
        }
        val stale = episodes.filter {
            it.queuePosition != null && it.pubDate in 1 until now - STALE_MS && it.positionMs == 0L && !it.played
        }
        if (stale.size >= 3) {
            out += Finding(Kind.STALE_QUEUE, null, "Your queue", "${stale.size} queued episodes are over 2 months old and never started.", stale.map { it.id })
        }
        return out
    }
}
