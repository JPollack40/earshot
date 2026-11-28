package com.earshot.app.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "1h 02m", "32m", "45s" */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> "%dh %02dm".format(h, m)
        totalMin > 0 -> "${m}m"
        else -> "${ms / 1000}s"
    }
}

/** "1:02:03" or "32:10" for the player's time labels. */
fun formatClock(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0)) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "Nov 24" this year, "Nov 24, 2025" otherwise. */
fun formatDate(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val cal = Calendar.getInstance()
    val thisYear = cal.get(Calendar.YEAR)
    cal.timeInMillis = epochMs
    val pattern = if (cal.get(Calendar.YEAR) == thisYear) "MMM d" else "MMM d, yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMs))
}

/** Phase 17: the speed dialog's fine-tune slider: 0.5× to 3.0× in 0.05× steps. */
const val FINE_SPEED_MIN = 0.5f
const val FINE_SPEED_MAX = 3.0f
const val FINE_SPEED_STEPS = 49 // (3.0 - 0.5) / 0.05 - 1 positions between the ends

fun fineSpeed(raw: Float): Float = (kotlin.math.round(raw * 20f) / 20f).coerceIn(FINE_SPEED_MIN, FINE_SPEED_MAX)

fun formatSpeed(speed: Float): String {
    val s = if (speed % 1f == 0f) "%.1f".format(speed) else "%.2f".format(speed).trimEnd('0')
    return "$s×"
}

/** Time saved: "45s", "12m 05s", "3h 12m". */
fun formatSaved(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "%dh %02dm".format(h, m)
        m > 0 -> "%dm %02ds".format(m, s)
        else -> "${s}s"
    }
}

/**
 * Phase 16: the mini player's status: "Buffering…", or the time left at the playing speed ("23m left").
 * Empty when the length isn't known yet.
 */
fun miniPlayerStatus(isBuffering: Boolean, positionMs: Long, durationMs: Long, speed: Float): String {
    if (isBuffering) return "Buffering…"
    if (durationMs <= 0) return ""
    val left = ((durationMs - positionMs).coerceAtLeast(0) / speed.coerceAtLeast(0.25f)).toLong()
    return if (left < 60_000) "under 1m left" else "${formatDuration(left)} left"
}

/**
 * Phase 16: listening time left in the queue at each show's speed ([speedOf] gives a podcast's speed),
 * counting the playing episode from [playingPositionMs].
 */
fun queueTimeLeftMs(
    items: List<com.earshot.app.data.db.EpisodeWithPodcast>,
    speedOf: (Long) -> Float,
    playingId: String?,
    playingPositionMs: Long,
): Long = items.sumOf { item ->
    val e = item.episode
    val pos = if (e.id == playingId) playingPositionMs else e.positionMs
    ((e.durationMs - pos).coerceAtLeast(0) / speedOf(e.podcastId).coerceAtLeast(0.25f)).toLong()
}

/** "3h 12m left · done by 6:40 PM" (phase 16); empty when the lengths aren't known. */
fun queueTimeLabel(leftMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (leftMs < 60_000) return ""
    val doneBy = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date(nowMs + leftMs))
    val sameDay = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.DAY_OF_YEAR) ==
        Calendar.getInstance().apply { timeInMillis = nowMs + leftMs }.get(Calendar.DAY_OF_YEAR)
    val day = if (sameDay) "" else " " + SimpleDateFormat("EEE", Locale.getDefault()).format(Date(nowMs + leftMs))
    return "${formatDuration(leftMs)} left · done by $doneBy$day"
}

/** Phase 16: "12:34", "1:02:03", "(05:10)" in show notes; group 1 is the whole time. */
private val TIMESTAMP = Regex("""(?<![\d:.])((?:\d{1,2}:)?\d{1,2}:\d{2})(?![\d:])""")

/** The times found in [text], as (range, ms), keeping only those within [durationMs] when it's known. */
fun findTimestamps(text: String, durationMs: Long): List<Pair<IntRange, Long>> =
    TIMESTAMP.findAll(text).mapNotNull { m ->
        val parts = m.groupValues[1].split(":").map { it.toLong() }
        if (parts.drop(1).any { it > 59 }) return@mapNotNull null
        val ms = parts.fold(0L) { acc, n -> acc * 60 + n } * 1000
        if (durationMs > 0 && ms > durationMs) return@mapNotNull null
        m.groups[1]!!.range to ms
    }.toList()

/** Show notes with each timestamp made a link that calls [onTap] with its time. */
fun linkTimestamps(
    text: androidx.compose.ui.text.AnnotatedString,
    durationMs: Long,
    color: androidx.compose.ui.graphics.Color,
    onTap: (Long) -> Unit,
): androidx.compose.ui.text.AnnotatedString {
    val found = findTimestamps(text.text, durationMs)
    if (found.isEmpty()) return text
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        val style = androidx.compose.ui.text.TextLinkStyles(
            androidx.compose.ui.text.SpanStyle(color = color, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        )
        for ((range, ms) in found) {
            addLink(androidx.compose.ui.text.LinkAnnotation.Clickable("t$ms", style) { onTap(ms) }, range.first, range.last + 1)
        }
    }
}
