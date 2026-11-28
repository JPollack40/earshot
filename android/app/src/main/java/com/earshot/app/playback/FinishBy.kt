package com.earshot.app.playback

import kotlin.math.ceil

/**
 * Phase 20 (C4): "Finish by". The playing episode's speed is nudged up, never past [maxSpeed],
 * so that it ends by [deadlineMs] (wall clock). It ends by itself when the episode changes or the
 * time is reached; the listener's own speed then comes back.
 */
data class FinishBy(val episodeId: String, val deadlineMs: Long, val maxSpeed: Float)

object FinishByMath {
    /** Speeds move in these steps, so small drifts don't keep changing the speed. */
    const val STEP = 0.05f
    /** How often the speed is worked out again while playing. */
    const val CHECK_MS = 15_000L
    val MAX_CHOICES = listOf(1.5f, 1.75f, 2f, 2.5f, 3f)

    /** The speed that ends [remainingMs] of episode in the time left, never below [base] or above [max]. */
    fun speedFor(remainingMs: Long, nowMs: Long, deadlineMs: Long, base: Float, max: Float): Float {
        val top = maxOf(base, max)
        if (remainingMs <= 0) return base
        val left = deadlineMs - nowMs
        if (left <= 0) return top
        val needed = remainingMs.toDouble() / left
        val stepped = (ceil(needed / STEP - 1e-6) * STEP).toFloat()
        return stepped.coerceIn(base, top)
    }

    /** When the episode would end at [speed] from now. */
    fun endsAt(remainingMs: Long, nowMs: Long, speed: Float): Long =
        nowMs + (remainingMs / speed.coerceAtLeast(0.1f)).toLong()

    /** The "up to" speed offered first: half a step up from [base] or more, else the fastest choice. */
    fun defaultMax(base: Float): Float = MAX_CHOICES.firstOrNull { it >= base + 0.5f - 1e-3f } ?: MAX_CHOICES.last()

    /** The choices worth offering above [base]. */
    fun maxChoices(base: Float): List<Float> = MAX_CHOICES.filter { it > base + 1e-3f }.ifEmpty { listOf(MAX_CHOICES.last()) }

    /**
     * The deadline for a picked clock time ([hour]:[minute]) seen from [nowMs]: today, or tomorrow
     * when that time has already passed today.
     */
    fun deadlineFor(hour: Int, minute: Int, nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long {
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
        var at = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return at.toInstant().toEpochMilli()
    }
}
