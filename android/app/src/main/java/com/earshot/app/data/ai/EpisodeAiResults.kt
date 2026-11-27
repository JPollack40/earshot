package com.earshot.app.data.ai

/**
 * Phase 11: what the "ai" edge function makes from a transcript, in plain Kotlin (no JSON here,
 * so the logic can be unit tested). Parsing lives in [EpisodeAiStore].
 */
object AiKinds {
    const val CHAPTERS = "chapters"
    const val SUMMARY = "summary"
    const val ADS = "ads"
}

/** One ad read or ad break, from [startMs] to where the episode's own content resumes ([endMs]). */
data class AdSpan(val startMs: Long, val endMs: Long, val label: String)

/** A key moment in a summary. */
data class SummaryPoint(val atMs: Long, val text: String)

/** Phase 13: an answer to a typed question about an episode, with supporting quotes and their times. */
data class EpisodeAnswer(val question: String, val answer: String, val quotes: List<SummaryPoint>, val model: String = "")

data class EpisodeSummary(val text: String, val points: List<SummaryPoint>, val model: String = "")

/** "auto" skips found ads by itself; "button" shows a Skip ad button instead; "off" ignores them. */
object AdSkip {
    const val AUTO = "auto"
    const val BUTTON = "button"
    const val OFF = "off"

    fun label(mode: String) = when (mode) {
        BUTTON -> "Show a Skip ad button"
        OFF -> "Off"
        else -> "Skip automatically"
    }

    /**
     * The ad playing at [positionMs], if any. An ad is only "playing" from its start up to 1.5 s
     * before its end, so seeking to its end never lands back inside it.
     */
    fun current(ads: List<AdSpan>, positionMs: Long): AdSpan? =
        ads.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 1_500 }

    /**
     * Where automatic skipping should jump to from [positionMs], or null to carry on. Back-to-back
     * ads (gap under 5 s) are skipped together. Ads already skipped once while this episode was
     * loaded ([alreadySkipped], by start time) are left alone, so going back into one plays it.
     */
    fun skipTarget(ads: List<AdSpan>, positionMs: Long, alreadySkipped: Set<Long> = emptySet()): Long? {
        var ad = current(ads, positionMs) ?: return null
        if (ad.startMs in alreadySkipped) return null
        var end = ad.endMs
        while (true) {
            val next = ads.firstOrNull { it.startMs > ad.startMs && it.startMs <= end + 5_000 && it.endMs > end } ?: break
            ad = next
            end = next.endMs
        }
        return end
    }
}
