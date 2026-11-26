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
