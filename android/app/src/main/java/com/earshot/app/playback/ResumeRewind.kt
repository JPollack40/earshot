package com.earshot.app.playback

/**
 * Phase 13: on resuming after a pause or an interruption (a call, a navigation prompt), back up a
 * little so the listener catches the end of the interrupted sentence. Longer pauses back up more.
 */
object ResumeRewind {
    /**
     * How far to back up after [pausedForMs] of silence: nothing under a second (a quick tap), 2 s
     * under a minute, 5 s under an hour, 10 s after that. An [interrupted] stop (another app took the
     * audio) always backs up at least 3 s, since it cut in mid-sentence.
     */
    fun amountMs(pausedForMs: Long, interrupted: Boolean): Long {
        val base = when {
            pausedForMs < 1_000 -> 0L
            pausedForMs < 60_000 -> 2_000L
            pausedForMs < 60 * 60_000 -> 5_000L
            else -> 10_000L
        }
        return if (interrupted && pausedForMs >= 500) maxOf(base, 3_000L) else base
    }

    /** The start position for an episode loaded again [sinceLastPlayMs] after it was last played. */
    fun startFor(positionMs: Long, sinceLastPlayMs: Long): Long =
        if (positionMs <= 0) positionMs else (positionMs - amountMs(sinceLastPlayMs, interrupted = false)).coerceAtLeast(0)
}
