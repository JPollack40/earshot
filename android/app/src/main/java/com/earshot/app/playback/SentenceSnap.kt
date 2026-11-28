package com.earshot.app.playback

import com.earshot.app.data.transcript.Segment

/**
 * Phase 19 (A1, A3): sentence starts from a timed transcript, so skip back and the rewind after a pause
 * land at the start of a sentence instead of mid-word, and "Say that again" can replay the last one.
 *
 * A segment starts a sentence when the one before it ends with . ? ! or … (closing quotes allowed). Some
 * feed transcripts have no punctuation at all; then every segment start counts.
 */
object SentenceSnap {
    /** How much further back than asked a skip may go to reach a sentence start. */
    const val MAX_EXTRA_MS = 5_000L

    private val END = Regex("""[.?!…]["'”’)\]]*$""")

    fun starts(segments: List<Segment>): LongArray {
        if (segments.isEmpty()) return LongArray(0)
        val sorted = segments.sortedBy { it.startMs }
        val sentences = sorted.filterIndexed { i, s -> i == 0 || END.containsMatchIn(sorted[i - 1].text.trim()) || s.startMs == 0L }
        val chosen = if (sentences.size * 10 < sorted.size) sorted else sentences
        return chosen.map { it.startMs }.distinct().toLongArray()
    }

    /**
     * Where a skip back from [fromMs] to [rawTargetMs] should land: the latest sentence start at or before
     * [rawTargetMs], if it's no more than [maxExtraMs] earlier; otherwise [rawTargetMs] itself.
     */
    fun snapBack(starts: LongArray, rawTargetMs: Long, fromMs: Long, maxExtraMs: Long = MAX_EXTRA_MS): Long {
        val raw = rawTargetMs.coerceAtLeast(0)
        if (starts.isEmpty() || raw >= fromMs) return raw
        var lo = 0
        var hi = starts.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= raw) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (best < 0) return raw
        val s = starts[best]
        return if (raw - s <= maxExtraMs) s else raw
    }

    /**
     * "Say that again, slower": the start of the sentence playing 1.5 s ago (so a sentence that has only
     * just begun counts as the one before), at most 15 s back; 10 s back without a transcript.
     */
    fun againFrom(starts: LongArray, positionMs: Long): Long {
        val fallback = (positionMs - 10_000).coerceAtLeast(0)
        val raw = (positionMs - 1_500).coerceAtLeast(0)
        val s = starts.lastOrNull { it <= raw } ?: return fallback
        return if (raw - s <= 13_500) s else fallback
    }
}
