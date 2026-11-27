package com.earshot.app.data

import android.content.Context
import com.earshot.app.data.db.Podcast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-wide playback defaults. A podcast can override any of them (see [Podcast]). */
data class PlaybackDefaults(
    val speed: Float = 1.0f,
    val smartSpeed: Boolean = false,
    val voiceBoost: Boolean = false,
    val skipBackMs: Long = 15_000,
    val skipForwardMs: Long = 30_000,
)

/** The settings that actually apply to an episode: its podcast's overrides, else the defaults. */
data class PlaybackPrefs(
    val speed: Float,
    val smartSpeed: Boolean,
    val voiceBoost: Boolean,
    val skipBackMs: Long,
    val skipForwardMs: Long,
) {
    companion object {
        fun resolve(defaults: PlaybackDefaults, podcast: Podcast?) = PlaybackPrefs(
            speed = podcast?.speed ?: defaults.speed,
            smartSpeed = podcast?.smartSpeed ?: defaults.smartSpeed,
            voiceBoost = podcast?.voiceBoost ?: defaults.voiceBoost,
            skipBackMs = podcast?.skipBackMs ?: defaults.skipBackMs,
            skipForwardMs = podcast?.skipForwardMs ?: defaults.skipForwardMs,
        )
    }
}

/** Time saved across all listening, split by what saved it. */
data class TimeSaved(val bySpeedMs: Long = 0, val bySmartSpeedMs: Long = 0) {
    val totalMs get() = bySpeedMs + bySmartSpeedMs
}

/** Small app-wide preferences. Per-podcast settings live in the database. */
object Settings {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---- New-episode notifications (phase 10): "all", "pinned" or "off". Per phone, sent to the server with its token.

    const val NOTIFY_ALL = "all"
    const val NOTIFY_PINNED = "pinned"
    const val NOTIFY_OFF = "off"

    fun newEpisodeNotify(context: Context): String = prefs(context).getString("new_episode_notify", NOTIFY_ALL) ?: NOTIFY_ALL

    fun setNewEpisodeNotify(context: Context, mode: String) {
        prefs(context).edit().putString("new_episode_notify", mode).apply()
    }

    /** Skip lengths offered by the long-press picker and the settings screens. */
    val SKIP_CHOICES_MS = listOf(5_000L, 10_000L, 15_000L, 30_000L, 60_000L, 300_000L, 900_000L)

    val SPEED_CHOICES = listOf(0.8f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.7f, 2.0f, 2.5f, 3.0f)

    // ---- Playback defaults (observed by the playback service, so changes apply right away) ----

    private var playback: MutableStateFlow<PlaybackDefaults>? = null

    fun playbackDefaults(context: Context): StateFlow<PlaybackDefaults> = playbackFlow(context).asStateFlow()

    private fun playbackFlow(context: Context): MutableStateFlow<PlaybackDefaults> = synchronized(this) {
        playback ?: run {
            val p = prefs(context)
            MutableStateFlow(
                PlaybackDefaults(
                    speed = p.getFloat("speed", 1.0f),
                    smartSpeed = p.getBoolean("smart_speed", false),
                    voiceBoost = p.getBoolean("voice_boost", false),
                    skipBackMs = p.getLong("skip_back_ms", 15_000),
                    skipForwardMs = p.getLong("skip_forward_ms", 30_000),
                ),
            ).also { playback = it }
        }
    }

    fun updatePlaybackDefaults(context: Context, change: (PlaybackDefaults) -> PlaybackDefaults) {
        val flow = playbackFlow(context)
        val d = synchronized(this) { change(flow.value).also { flow.value = it } }
        savePlayback(context, d, changedAt = System.currentTimeMillis(), dirty = true)
    }

    private fun savePlayback(context: Context, d: PlaybackDefaults, changedAt: Long, dirty: Boolean) {
        prefs(context).edit()
            .putFloat("speed", d.speed)
            .putBoolean("smart_speed", d.smartSpeed)
            .putBoolean("voice_boost", d.voiceBoost)
            .putLong("skip_back_ms", d.skipBackMs)
            .putLong("skip_forward_ms", d.skipForwardMs)
            .putLong("playback_changed_at", changedAt)
            .putBoolean("playback_dirty", dirty)
            .apply()
    }

    // Sync of the playback defaults (one row on the server).
    fun playbackChangedAt(context: Context): Long = prefs(context).getLong("playback_changed_at", 0)
    fun playbackDirty(context: Context): Boolean = prefs(context).getBoolean("playback_dirty", true)

    fun markPlaybackClean(context: Context, changedAt: Long) {
        if (playbackChangedAt(context) == changedAt) prefs(context).edit().putBoolean("playback_dirty", false).apply()
    }

    /** Defaults changed on another device. */
    fun applyRemotePlayback(context: Context, d: PlaybackDefaults, changedAt: Long) {
        val flow = playbackFlow(context)
        synchronized(this) { flow.value = d }
        savePlayback(context, d, changedAt, dirty = false)
    }

    // ---- Tidy-up defaults (phase 7), synced in the same settings row as the playback defaults ----

    private var tidy: MutableStateFlow<TidyDefaults>? = null

    fun tidyDefaults(context: Context): StateFlow<TidyDefaults> = tidyFlow(context).asStateFlow()

    private fun tidyFlow(context: Context): MutableStateFlow<TidyDefaults> = synchronized(this) {
        tidy ?: run {
            val p = prefs(context)
            MutableStateFlow(TidyDefaults(p.getInt("inbox_days", 0), p.getInt("inbox_keep", 0))).also { tidy = it }
        }
    }

    /** A change made on this phone: saved, and the settings row is marked for upload. */
    fun updateTidyDefaults(context: Context, change: (TidyDefaults) -> TidyDefaults) {
        val flow = tidyFlow(context)
        val t = synchronized(this) { change(flow.value).also { flow.value = it } }
        prefs(context).edit()
            .putInt("inbox_days", t.inboxDays)
            .putInt("inbox_keep", t.inboxKeep)
            .putLong("playback_changed_at", System.currentTimeMillis())
            .putBoolean("playback_dirty", true)
            .apply()
    }

    /** Tidy-up defaults changed on another device (called with [applyRemotePlayback]). */
    fun applyRemoteTidy(context: Context, t: TidyDefaults) {
        val flow = tidyFlow(context)
        synchronized(this) { flow.value = t }
        prefs(context).edit().putInt("inbox_days", t.inboxDays).putInt("inbox_keep", t.inboxKeep).apply()
    }

    // ---- Found ads (phase 11), synced in the same settings row: "auto", "button" or "off" ----

    private var adSkipFlow: MutableStateFlow<String>? = null

    fun adSkip(context: Context): StateFlow<String> = adSkipState(context).asStateFlow()

    private fun adSkipState(context: Context): MutableStateFlow<String> = synchronized(this) {
        adSkipFlow ?: MutableStateFlow(prefs(context).getString("ad_skip", "auto") ?: "auto").also { adSkipFlow = it }
    }

    fun setAdSkip(context: Context, mode: String) {
        adSkipState(context).value = mode
        prefs(context).edit()
            .putString("ad_skip", mode)
            .putLong("playback_changed_at", System.currentTimeMillis())
            .putBoolean("playback_dirty", true)
            .apply()
    }

    /** Changed on another device (called with [applyRemotePlayback]). */
    fun applyRemoteAdSkip(context: Context, mode: String) {
        if (mode !in listOf("auto", "button", "off")) return
        adSkipState(context).value = mode
        prefs(context).edit().putString("ad_skip", mode).apply()
    }

    // ---- Time saved ----

    private var timeSaved: MutableStateFlow<TimeSaved>? = null

    fun timeSaved(context: Context): StateFlow<TimeSaved> = timeSavedFlow(context).asStateFlow()

    private fun timeSavedFlow(context: Context): MutableStateFlow<TimeSaved> = synchronized(this) {
        timeSaved ?: run {
            val p = prefs(context)
            MutableStateFlow(TimeSaved(p.getLong("saved_speed_ms", 0), p.getLong("saved_smart_ms", 0)))
                .also { timeSaved = it }
        }
    }

    fun addTimeSaved(context: Context, bySpeedMs: Long, bySmartSpeedMs: Long) {
        if (bySpeedMs == 0L && bySmartSpeedMs == 0L) return
        val flow = timeSavedFlow(context)
        val t = synchronized(this) {
            val o = flow.value
            TimeSaved(o.bySpeedMs + bySpeedMs, o.bySmartSpeedMs + bySmartSpeedMs).also { flow.value = it }
        }
        prefs(context).edit().putLong("saved_speed_ms", t.bySpeedMs).putLong("saved_smart_ms", t.bySmartSpeedMs).apply()
    }

    // ---- Appearance (this phone only; not synced) ----

    /** "glow" (default while Serena reviews it) or "classic" (the look before phase 5c). */
    const val THEME_GLOW = "glow"
    const val THEME_CLASSIC = "classic"

    private var theme: MutableStateFlow<String>? = null

    fun appTheme(context: Context): StateFlow<String> = themeFlow(context).asStateFlow()

    private fun themeFlow(context: Context): MutableStateFlow<String> = synchronized(this) {
        theme ?: MutableStateFlow(prefs(context).getString("app_theme", THEME_GLOW) ?: THEME_GLOW).also { theme = it }
    }

    fun setAppTheme(context: Context, value: String) {
        themeFlow(context).value = value
        prefs(context).edit().putString("app_theme", value).apply()
    }

    // ---- Downloads ----

    /** Choices offered for [expireDays]; 0 means never. */
    val EXPIRE_DAY_CHOICES = listOf(0, 3, 7, 14, 30)

    /** Delete an episode's download as soon as it's finished (played). */
    fun deleteWhenFinished(context: Context): Boolean = prefs(context).getBoolean("delete_when_finished", true)

    fun setDeleteWhenFinished(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("delete_when_finished", enabled).apply()
    }

    /**
     * Unfinished downloads are deleted this many days after the later of download time and last
     * play. 0 means never.
     */
    fun expireDays(context: Context): Int = prefs(context).getInt("expire_days", 7)

    fun setExpireDays(context: Context, days: Int) {
        prefs(context).edit().putInt("expire_days", days).apply()
    }

    /** Download an episode as soon as it's added to the queue. */
    fun autoDownloadQueued(context: Context): Boolean = prefs(context).getBoolean("auto_download_queued", true)

    fun setAutoDownloadQueued(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("auto_download_queued", enabled).apply()
    }
}
