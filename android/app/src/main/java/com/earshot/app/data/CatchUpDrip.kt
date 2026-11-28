package com.earshot.app.data

import com.earshot.app.data.db.Episode

/**
 * Phase 20 (B3): catch-up drip for serial shows. When an episode finishes, the next one in the
 * show's order joins the front of the queue, so a serial plays through one episode at a time
 * without queueing the whole back catalogue.
 */
object CatchUpDrip {
    /** Share of episodes that must carry an episode number before numbers decide the order. */
    private const val NUMBERED_SHARE = 0.8

    /** The show's episodes in listening order: by season and number when nearly all are numbered, else oldest first. */
    fun inOrder(episodes: List<Episode>): List<Episode> {
        val numbered = episodes.count { it.episodeNo != null }
        return if (episodes.isNotEmpty() && numbered >= episodes.size * NUMBERED_SHARE) {
            episodes.sortedWith(compareBy({ it.episodeNo == null }, { it.season ?: 0 }, { it.episodeNo ?: 0.0 }, { it.pubDate }))
        } else {
            episodes.sortedBy { it.pubDate }
        }
    }

    /**
     * The episode to queue after [finishedId], or null: the first unplayed one after it in order.
     * Null when that one is already queued (it's coming anyway) or nothing unplayed follows.
     */
    fun next(episodes: List<Episode>, finishedId: String): Episode? {
        val order = inOrder(episodes)
        val at = order.indexOfFirst { it.id == finishedId }
        if (at < 0) return null
        val next = order.drop(at + 1).firstOrNull { !it.played } ?: return null
        return next.takeIf { it.queuePosition == null }
    }
}
