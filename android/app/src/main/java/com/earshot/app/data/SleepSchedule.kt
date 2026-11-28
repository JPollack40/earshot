package com.earshot.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Phase 17: the automatic sleep timer (Settings > Playback > Automatic sleep timer). When you press play
 * inside the window on one of the chosen days and no timer is running, a timer is set for you: [minutes],
 * or to the end of the episode when [minutes] is 0. A window can cross midnight (22:30 to 06:00 on
 * Monday covers Monday night into Tuesday morning). Cancelling it holds until you next press play.
 */
data class SleepSchedule(
    val on: Boolean = false,
    /** Minutes after midnight. */
    val startMin: Int = 22 * 60,
    val endMin: Int = 6 * 60,
    /** ISO days, 1 = Monday to 7 = Sunday. */
    val days: Set<Int> = (1..7).toSet(),
    /** 0 = end of episode. */
    val minutes: Int = 30,
) {
    fun inWindow(t: LocalDateTime): Boolean {
        if (!on || days.isEmpty() || startMin == endMin) return false
        val day = t.dayOfWeek.value
        val m = t.hour * 60 + t.minute
        return if (startMin < endMin) {
            day in days && m >= startMin && m < endMin
        } else {
            val yesterday = t.dayOfWeek.minus(1).value
            (day in days && m >= startMin) || (yesterday in days && m < endMin)
        }
    }

    fun toJson(): String = JSONObject()
        .put("on", on).put("start", startMin).put("end", endMin)
        .put("days", JSONArray(days.sorted())).put("minutes", minutes)
        .toString()

    companion object {
        val MINUTE_CHOICES = listOf(15, 30, 45, 60, 90, 0)

        fun minutesLabel(m: Int) = if (m == 0) "End of episode" else "$m min"

        fun timeLabel(min: Int): String {
            val h = min / 60
            val mm = min % 60
            val h12 = if (h % 12 == 0) 12 else h % 12
            return "%d:%02d %s".format(h12, mm, if (h < 12) "AM" else "PM")
        }

        fun dayLetter(d: Int) = DayOfWeek.of(d).name.take(2).lowercase().replaceFirstChar { it.uppercase() }

        fun fromJson(s: String?): SleepSchedule {
            if (s.isNullOrBlank()) return SleepSchedule()
            return try {
                val o = JSONObject(s)
                val arr = o.optJSONArray("days")
                val days = if (arr == null) (1..7).toSet() else (0 until arr.length()).map { arr.getInt(it) }.filter { it in 1..7 }.toSet()
                SleepSchedule(
                    on = o.optBoolean("on", false),
                    startMin = o.optInt("start", 22 * 60).coerceIn(0, 24 * 60 - 1),
                    endMin = o.optInt("end", 6 * 60).coerceIn(0, 24 * 60 - 1),
                    days = days,
                    minutes = o.optInt("minutes", 30).coerceAtLeast(0),
                )
            } catch (_: Exception) {
                SleepSchedule()
            }
        }
    }
}
