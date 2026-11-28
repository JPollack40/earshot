package com.earshot.app.playback

import android.content.Context
import androidx.core.content.edit
import com.earshot.app.data.transcript.Segment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlin.math.pow

/**
 * Phase 24 (lab): per-speaker controls. With a speaker-labelled transcript you can make one voice
 * louder or quieter than the others, and play one speaker a little faster or slower. While the episode
 * plays, the player follows the transcript: whoever is speaking gets their level and speed.
 *
 * Levels work by turning the others down (the player can't go above full volume), so raising a quiet
 * guest by 6 dB plays the host 6 dB quieter; turn the phone up a little to make up for it. Speeds
 * multiply whatever speed is playing (your own, a podcast's, Finish by).
 *
 * Per episode (Deepgram's speaker numbers differ between episodes), on this phone only, newest 50 kept.
 * Off until switched on in Settings > Playback.
 */
object SpeakerLab {
    private const val PREFS = "speaker_lab"
    private const val KEY_ON = "on"
    private const val KEEP = 50
    val LEVEL_CHOICES_DB = listOf(-9, -6, -3, 0, 3, 6, 9)
    val SPEED_CHOICES = listOf(0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.4f)

    data class Adjust(val levelDb: Int = 0, val speed: Float = 1f) {
        val isNeutral get() = levelDb == 0 && speed == 1f
    }

    private val _version = MutableStateFlow(0)
    /** Changes whenever the switch or an adjustment changes. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_ON, on) }
        _version.value++
    }

    fun adjustments(context: Context, episodeId: String): Map<String, Adjust> {
        val raw = prefs(context).getString("ep:$episodeId", null) ?: return emptyMap()
        return runCatching { parse(JSONObject(raw)) }.getOrDefault(emptyMap())
    }

    fun set(context: Context, episodeId: String, speaker: String, adjust: Adjust) {
        val now = adjustments(context, episodeId).toMutableMap()
        if (adjust.isNeutral) now.remove(speaker) else now[speaker] = adjust
        val p = prefs(context)
        p.edit {
            if (now.isEmpty()) {
                remove("ep:$episodeId")
            } else {
                putString("ep:$episodeId", toJson(now).toString())
                putLong("at:$episodeId", System.currentTimeMillis())
            }
            // Keep the newest few episodes.
            val stamps = p.all.keys.filter { it.startsWith("at:") }.associateWith { p.getLong(it, 0) }
            if (stamps.size > KEEP) {
                for (k in stamps.entries.sortedBy { it.value }.take(stamps.size - KEEP).map { it.key }) {
                    remove(k)
                    remove("ep:" + k.removePrefix("at:"))
                }
            }
        }
        _version.value++
    }

    fun clear(context: Context, episodeId: String) {
        prefs(context).edit { remove("ep:$episodeId"); remove("at:$episodeId") }
        _version.value++
    }

    internal fun parse(o: JSONObject): Map<String, Adjust> =
        o.keys().asSequence().associateWith { k ->
            val a = o.getJSONObject(k)
            Adjust(a.optInt("db", 0).coerceIn(-12, 12), a.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 2f))
        }

    internal fun toJson(m: Map<String, Adjust>): JSONObject {
        val o = JSONObject()
        for ((k, a) in m) o.put(k, JSONObject().put("db", a.levelDb).put("speed", a.speed.toDouble()))
        return o
    }

    // ---- the maths (tested) ----

    /**
     * Player volume (0 to 1) for each speaker in [adjust]; everyone else plays at [others]. The loudest
     * setting plays at full volume and the rest relative to it.
     */
    fun volumes(adjust: Map<String, Adjust>): Pair<Map<String, Float>, Float> {
        val top = maxOf(0, adjust.values.maxOfOrNull { it.levelDb } ?: 0)
        fun v(db: Int) = 10.0.pow((db - top) / 20.0).toFloat()
        return adjust.mapValues { v(it.value.levelDb) } to v(0)
    }

    /**
     * Who is speaking at [positionMs]: the segment around it, or the one just before when it falls in a
     * short gap (under 3 s) between segments. Null when nobody is (or the transcript has no speakers).
     * [segments] are in time order.
     */
    fun speakerAt(segments: List<Segment>, positionMs: Long): String? {
        if (segments.isEmpty()) return null
        var lo = 0
        var hi = segments.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (segments[mid].startMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (found < 0) return null
        val s = segments[found]
        return if (positionMs <= s.endMs + 3_000) s.speaker else null
    }
}
