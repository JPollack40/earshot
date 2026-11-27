package com.earshot.app.data

/**
 * Trimming ads stitched onto an episode (phase 10). Dynamic ad insertion makes the file longer
 * than the length the feed states; when a podcast's ads always go at the start (or the end), that
 * extra length can be skipped there. Per podcast, off by default: Podcast.adTrim.
 */
object AdTrim {
    const val START = "start"
    const val END = "end"

    /** Differences below this are rounding or encoder padding, not ads. */
    const val MIN_EXTRA_MS = 10_000L

    /** More than this is more likely a wrong length in the feed than inserted ads. */
    const val MAX_EXTRA_MS = 20 * 60_000L

    /** The extra length to trim, or 0 when the lengths are unknown or don't suggest inserted ads. */
    fun extraMs(feedDurationMs: Long, realDurationMs: Long): Long {
        if (feedDurationMs <= 0 || realDurationMs <= 0) return 0
        val d = realDurationMs - feedDurationMs
        return if (d in MIN_EXTRA_MS..MAX_EXTRA_MS) d else 0
    }

    fun label(mode: String?): String = when (mode) {
        START -> "At the start"
        END -> "At the end"
        else -> "Off"
    }
}
