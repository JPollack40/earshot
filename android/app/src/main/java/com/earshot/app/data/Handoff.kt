package com.earshot.app.data

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.earshot.app.data.sync.Supabase
import com.earshot.app.data.sync.SupabaseException
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Phase 20 (C5): hand playback between this phone and the web player (or another phone).
 * The server keeps one row per person (013_now_playing.sql): the device that holds playback,
 * its episode and position. Starting to play takes it; while playing the phone reports every
 * 10 s, and a report that comes back with another device means that device took over, so the
 * phone pauses. Opening the app while another device plays offers "Continue here".
 */
object Handoff {
    private const val TAG = "Handoff"
    const val REPORT_MS = 10_000L
    /** A device still playing is offered when it reported in the last 2 minutes. */
    const val PLAYING_FRESH_MS = 2 * 60_000L
    /** A paused one, when it paused in the last 12 hours. */
    const val PAUSED_FRESH_MS = 12 * 60 * 60_000L

    data class Holder(
        val device: String,
        val deviceName: String,
        val episodeId: String,
        val positionMs: Long,
        val playing: Boolean,
        val updatedAt: Long,
    )

    /** False once the server says the handoff function isn't there (013 not run): stops trying until the app restarts. */
    @Volatile private var available = true

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("handoff", true)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit { putBoolean("handoff", on) }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * This phone's id for the handoff row: made once, kept across app updates. Phase 22: kept in its own
     * file ("device"), which Android's backup leaves out, so a restored or transferred phone gets its own.
     */
    fun deviceId(context: Context): String {
        val d = context.getSharedPreferences("device", Context.MODE_PRIVATE)
        d.getString("handoff_device", null)?.let { return it }
        val p = prefs(context)
        val id = p.getString("handoff_device", null) ?: ("android:" + UUID.randomUUID().toString().take(8))
        d.edit { putString("handoff_device", id) }
        p.edit { remove("handoff_device") }
        return id
    }

    fun deviceName(): String = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL)
        .joinToString(" ").ifBlank { "Phone" }

    /** Reports this phone's playback ([claim]: it just started). Returns the holder now, or null when it couldn't. */
    suspend fun report(context: Context, supabase: Supabase, episodeId: String, positionMs: Long, playing: Boolean, claim: Boolean): Holder? {
        if (!available || !enabled(context)) return null
        val args = JSONObject()
            .put("p_device", deviceId(context))
            .put("p_name", deviceName())
            .put("p_episode", episodeId)
            .put("p_position", positionMs.coerceAtLeast(0))
            .put("p_playing", playing)
            .put("p_claim", claim)
        return call { parse(JSONObject(supabase.rpc("handoff", args))) }
    }

    /** Where playback is now, for "Continue here". */
    suspend fun fetch(context: Context, supabase: Supabase): Holder? {
        if (!available || !enabled(context)) return null
        return call {
            val rows = supabase.select("now_playing?select=*&limit=1")
            if (rows.length() == 0) null else parse(rows.getJSONObject(0))
        }
    }

    private suspend fun call(block: suspend () -> Holder?): Holder? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SupabaseException) {
        // 404: the table or function isn't on the server yet (013 not run).
        if (e.status == 404) {
            available = false
            Log.i(TAG, "Handoff isn't set up on the server yet; not trying again until restart")
        } else {
            Log.w(TAG, "Handoff call failed: ${e.message}")
        }
        null
    } catch (e: Exception) {
        Log.w(TAG, "Handoff call failed", e)
        null
    }

    fun parse(o: JSONObject): Holder? {
        val device = o.optString("device").ifBlank { return null }
        val episode = o.optString("episode_id").ifBlank { return null }
        return Holder(
            device = device,
            deviceName = o.optString("device_name"),
            episodeId = episode,
            positionMs = o.optLong("position_ms"),
            playing = o.optBoolean("playing"),
            updatedAt = parseTime(o.optString("updated_at")),
        )
    }

    fun parseList(json: String): Holder? = runCatching { JSONArray(json).optJSONObject(0)?.let(::parse) }.getOrNull()

    /** Postgres timestamptz text ("2026-11-28 08:30:00.123+00" or ISO) to ms; 0 when it can't be read. */
    fun parseTime(s: String): Long {
        if (s.isBlank()) return 0
        val iso = s.trim().replace(' ', 'T').let { t ->
            // "+00" -> "+00:00" for Instant.parse.
            Regex("([+-]\\d{2})$").replace(t) { m -> m.groupValues[1] + ":00" }
        }
        return runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(iso).toEpochMilli() }
            .getOrDefault(0)
    }

    /** Whether to offer "Continue here" for [h]: another device, recently playing or recently paused. */
    fun offer(h: Holder?, myDevice: String, nowMs: Long): Boolean {
        if (h == null || h.device == myDevice || h.updatedAt <= 0) return false
        val age = nowMs - h.updatedAt
        return if (h.playing) age in 0..PLAYING_FRESH_MS else age in 0..PAUSED_FRESH_MS
    }

    /** "the web player" / "Galaxy Z Fold6". */
    fun label(h: Holder): String = when {
        h.device == "web" -> "the web player"
        h.deviceName.isNotBlank() -> h.deviceName
        else -> "another device"
    }
}
