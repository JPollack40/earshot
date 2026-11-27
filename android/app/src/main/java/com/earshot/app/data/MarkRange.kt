package com.earshot.app.data

import com.earshot.app.data.db.Episode

/**
 * "Mark older played" / "Mark newer played" (phase 7b). Pure logic, no Android, so it's
 * unit-tested (MarkRangeTest). The web player has the same rules in web/src/markRange.ts.
 *
 * From one episode of a podcast, picks every episode of that podcast released before it (older)
 * or after it (newer), by release date. The episode itself is never included (it has its own
 * Played button), and neither are episodes released at exactly the same time. Already played
 * episodes don't count. Queued episodes and the one loaded in the player are left alone and
 * counted as skipped, so a catch-up never empties the queue or stops what's playing.
 */
object MarkRange {
    enum class Direction { OLDER, NEWER }

    data class Result(val ids: List<String>, val skipped: Int)

    fun pick(all: List<Episode>, anchor: Episode, direction: Direction, loadedId: String?): Result {
        val ids = ArrayList<String>()
        var skipped = 0
        for (e in all) {
            if (e.id == anchor.id || e.podcastId != anchor.podcastId || e.played) continue
            val inRange = when (direction) {
                Direction.OLDER -> e.pubDate < anchor.pubDate
                Direction.NEWER -> e.pubDate > anchor.pubDate
            }
            if (!inRange) continue
            if (e.queuePosition != null || e.id == loadedId) skipped++ else ids += e.id
        }
        return Result(ids, skipped)
    }
}
