package com.earshot.app.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.earshot.app.BuildConfig
import com.earshot.app.data.sync.Supabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Phase 16: Serena's switches for every copy of Earshot (Supabase table app_config, one row, see
 * supabase/migrations/010_safe_updates.sql). Everything defaults to ON, so a phone that has never
 * read the table, is offline, or reads a broken row behaves exactly as before.
 */
data class AppConfig(
    /** Release builds older than this show "Update needed" on the phone. */
    val minSupportedVersion: String? = null,
    /** The update check offers nothing newer than this (to pull a bad release). */
    val holdVersion: String? = null,
    val aiOn: Boolean = true,
    val adFindingOn: Boolean = true,
    val transcribeOn: Boolean = true,
    val browseAiOn: Boolean = true,
    val pausedMessage: String = DEFAULT_PAUSED,
    /** A short note shown at the top of the app; null = none. */
    val banner: String? = null,
) {
    /** True when this build is a release older than [minSupportedVersion]. Dev builds are never blocked. */
    val updateRequired: Boolean
        get() = Updates.automatic && minSupportedVersion != null &&
            Updates.isNewer(minSupportedVersion, BuildConfig.VERSION_NAME)

    companion object {
        const val DEFAULT_PAUSED = "Paused by Serena for now. It will be back soon."

        /** Reads one app_config row. Missing or wrongly typed fields keep their defaults. */
        fun fromJson(o: JSONObject): AppConfig {
            fun text(k: String) = if (o.isNull(k)) null else o.optString(k).trim().ifBlank { null }
            fun flag(k: String) = o.opt(k) != false // only an explicit false switches something off
            return AppConfig(
                minSupportedVersion = text("min_supported_version"),
                holdVersion = text("hold_version"),
                aiOn = flag("ai_on"),
                adFindingOn = flag("ad_finding_on"),
                transcribeOn = flag("transcribe_on"),
                browseAiOn = flag("browse_ai_on"),
                pausedMessage = text("paused_message") ?: DEFAULT_PAUSED,
                banner = text("banner"),
            )
        }
    }
}

/** A feature Serena has switched off; its message is shown in place of the feature's result. */
class FeaturePausedException(message: String) : IllegalStateException(message)

object RemoteConfig {
    private const val TAG = "Earshot"
    private const val HOUR_MS = 60L * 60 * 1000
    private const val PREFS = "remote_config"

    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()
    val current: AppConfig get() = _config.value

    private val lock = Mutex()
    private var loaded = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Loads the last good copy saved on this phone (call once at app start, before anything reads it). */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val saved = prefs(context).getString("json", null) ?: return
        runCatching { _config.value = AppConfig.fromJson(JSONObject(saved)) }
    }

    /**
     * Reads app_config from Supabase, at most hourly unless [force]. On any failure the last good copy
     * stays in use: a failed read never turns anything off.
     */
    suspend fun refresh(context: Context, supabase: Supabase, force: Boolean = false) {
        if (!supabase.configured) return
        lock.withLock {
            val p = prefs(context)
            val now = System.currentTimeMillis()
            if (!force && now - p.getLong("fetched_at", 0) < HOUR_MS) return
            try {
                val rows = supabase.selectPublic("app_config?id=eq.1&select=*")
                p.edit { putLong("fetched_at", now) }
                val row = rows.optJSONObject(0) ?: return // table empty or 010 not run: keep what we have
                val next = AppConfig.fromJson(row)
                p.edit { putString("json", row.toString()) }
                if (next != _config.value) Log.i(TAG, "App config: $next")
                _config.value = next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "App config read failed (keeping the last copy): ${e.message}")
            }
        }
    }

    /** Throws [FeaturePausedException] with Serena's message when [on] is false. */
    fun require(on: (AppConfig) -> Boolean) {
        val c = current
        if (!on(c)) throw FeaturePausedException(c.pausedMessage)
    }

    // ---- Banner: dismissed per text, so a new banner shows again ----

    fun bannerDismissed(context: Context, text: String): Boolean =
        prefs(context).getInt("banner_dismissed", 0) == text.hashCode()

    fun dismissBanner(context: Context, text: String) {
        prefs(context).edit { putInt("banner_dismissed", text.hashCode()) }
    }
}
