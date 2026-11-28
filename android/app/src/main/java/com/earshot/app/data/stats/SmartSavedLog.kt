package com.earshot.app.data.stats

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase 22: Smart Speed's time saved per local day, kept on this phone for 400 days, so Stats can show
 * it for any range (the listening history only knows the speed, not the trimmed silences).
 */
object SmartSavedLog {
    private const val KEY = "smart_saved_days"
    private const val KEEP_DAYS = 400L

    /** Adds [ms] to [day] in [json] (an object of "yyyy-mm-dd" to ms), dropping days older than [KEEP_DAYS]. */
    fun add(json: String?, day: LocalDate, ms: Long): String {
        val o = runCatching { JSONObject(json ?: "{}") }.getOrDefault(JSONObject())
        val key = day.toString()
        o.put(key, o.optLong(key) + ms)
        val oldest = day.minusDays(KEEP_DAYS).toString()
        o.keys().asSequence().toList().filter { it < oldest }.forEach { o.remove(it) }
        return o.toString()
    }

    /** Total ms between [from] (inclusive) and [to] (exclusive), as local days. */
    fun between(json: String?, from: Long, to: Long, zone: ZoneId): Long {
        val o = runCatching { JSONObject(json ?: "{}") }.getOrDefault(JSONObject())
        val a = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().toString()
        val b = Instant.ofEpochMilli(to).atZone(zone).toLocalDate().toString()
        return o.keys().asSequence().filter { it >= a && it < b }.sumOf { o.optLong(it) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Synchronized
    fun record(context: Context, ms: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        if (ms <= 0) return
        val p = prefs(context)
        p.edit { putString(KEY, add(p.getString(KEY, null), Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), ms)) }
    }

    fun savedBetween(context: Context, from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        between(prefs(context).getString(KEY, null), from, to, zone)
}
