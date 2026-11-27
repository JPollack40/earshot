package com.earshot.app.data

import android.content.Context
import com.earshot.app.BuildConfig
import com.earshot.app.data.net.Http
import org.json.JSONObject

/** A newer release on GitHub than this build. */
data class UpdateInfo(val version: String, val apkUrl: String, val pageUrl: String, val notes: String)

/**
 * In-app update check (phase 9). Release builds are published as GitHub Releases (see RELEASES.md);
 * this asks GitHub for the latest one and compares versions. Installing is done by the person:
 * the download opens in the browser and Android asks to install it over the current app.
 */
object Updates {
    const val REPO = "JPollack40/earshot"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** Only release builds check by themselves (debug builds have a "-dev" version). */
    val automatic: Boolean get() = BuildConfig.BUILD_TYPE == "release"

    /** True when the daily automatic check is due. */
    fun due(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        automatic && now - prefs(context).getLong("checked_at", 0) >= DAY_MS

    /** The version the person chose to skip (its banner stays hidden). */
    fun dismissed(context: Context): String? = prefs(context).getString("dismissed", null)

    fun dismiss(context: Context, version: String) {
        prefs(context).edit().putString("dismissed", version).apply()
    }

    /** Asks GitHub. Returns the newer release, or null if this build is up to date. Throws if offline. */
    suspend fun check(context: Context): UpdateInfo? {
        val text = Http.getText(API, mapOf("Accept" to "application/vnd.github+json"))
        prefs(context).edit().putLong("checked_at", System.currentTimeMillis()).apply()
        val o = JSONObject(text)
        val tag = o.optString("tag_name").removePrefix("v")
        if (tag.isBlank() || !isNewer(tag, BuildConfig.VERSION_NAME)) return null
        val assets = o.optJSONArray("assets")
        var apk = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
            }
        }
        return UpdateInfo(tag, apk, o.optString("html_url").ifBlank { RELEASES_PAGE }, o.optString("body"))
    }

    /** "1.2.10" > "1.2.9"; anything after a "-" (like "-dev") is ignored. A dev build counts as 0.0.0. */
    fun isNewer(candidate: String, current: String): Boolean {
        val c = parts(candidate) ?: return false
        val cur = if (current.contains("-dev") || current.contains("-ci")) listOf(0, 0, 0) else parts(current) ?: listOf(0, 0, 0)
        for (i in 0 until 3) {
            if (c[i] != cur[i]) return c[i] > cur[i]
        }
        return false
    }

    private fun parts(v: String): List<Int>? {
        val m = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(v.trim().removePrefix("v")) ?: return null
        return m.groupValues.drop(1).map { it.toInt() }
    }
}
