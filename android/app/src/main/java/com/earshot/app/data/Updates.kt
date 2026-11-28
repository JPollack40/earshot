package com.earshot.app.data

import androidx.core.content.edit
import android.content.Context
import com.earshot.app.BuildConfig
import com.earshot.app.data.net.Http
import org.json.JSONObject

/** One release's notes (phase 13, "What's new"). */
data class ReleaseNotes(val version: String, val notes: String, val pageUrl: String)

/** A newer release on GitHub than this build. [shaUrl] is the APK's published SHA-256 file (phase 16). */
data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val pageUrl: String,
    val notes: String,
    val shaUrl: String = "",
    val beta: Boolean = false,
)

/**
 * In-app update check (phase 9). Release builds are published as GitHub Releases (see RELEASES.md);
 * this asks GitHub for the latest one and compares versions. Installing is done by the person:
 * the download opens in the browser and Android asks to install it over the current app.
 */
object Updates {
    const val REPO = "JPollack40/earshot"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val LIST_API = "https://api.github.com/repos/$REPO/releases?per_page=30"
    private val GITHUB = mapOf("Accept" to "application/vnd.github+json")
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
        prefs(context).edit { putString("dismissed", version) }
    }

    /**
     * Phase 16: beta updates (Settings > About > Get beta updates, off by default). On, the check also
     * offers GitHub pre-releases (v1.2.0-beta.1 and so on); off, only stable releases.
     */
    fun betaUpdates(context: Context): Boolean = prefs(context).getBoolean("beta", false)

    fun setBetaUpdates(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean("beta", on).putLong("checked_at", 0) }
    }

    /** Asks GitHub. Returns the newer release, or null if this build is up to date. Throws if offline. */
    suspend fun check(context: Context): UpdateInfo? {
        val beta = betaUpdates(context)
        val testSource = BuildConfig.UPDATE_SOURCE
        val releases: List<JSONObject> = if (testSource.isNotBlank()) {
            // The side-by-side update test build: GitHub-shaped releases.json from the VM's test server.
            val a = org.json.JSONArray(Http.getText(testSource + "releases.json"))
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        } else if (beta) {
            val a = org.json.JSONArray(Http.getText(LIST_API, GITHUB))
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        } else {
            listOf(JSONObject(Http.getText(API, GITHUB)))
        }
        prefs(context).edit { putLong("checked_at", System.currentTimeMillis()) }
        // Phase 16: Serena's hold (app_config.hold_version) pulls a bad release: nothing newer is offered,
        // but the held version itself still is, to anyone older than it.
        val hold = RemoteConfig.current.holdVersion?.removePrefix("v")?.let(::order)
        var best = pick(releases, hold, beta)
        if (best == null && hold != null && !beta && testSource.isBlank()) {
            val heldTag = RemoteConfig.current.holdVersion!!.removePrefix("v")
            if (isNewer(heldTag, BuildConfig.VERSION_NAME)) {
                best = runCatching { JSONObject(Http.getText("https://api.github.com/repos/$REPO/releases/tags/v$heldTag", GITHUB)) }
                    .getOrNull()?.let { pick(listOf(it), hold, beta = false) }
            }
        }
        val o = best ?: return null
        val tag = o.optString("tag_name").removePrefix("v")
        if (!isNewer(tag, BuildConfig.VERSION_NAME)) return null
        var apk = ""
        var sha = ""
        val assets = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk")) apk = a.optString("browser_download_url")
                if (name.endsWith(".apk.sha256")) sha = a.optString("browser_download_url")
            }
        }
        return UpdateInfo(tag, apk, o.optString("html_url").ifBlank { RELEASES_PAGE }, o.optString("body"), sha, o.optBoolean("prerelease"))
    }

    /**
     * The newest usable release among [releases]: not a draft, a version this app understands, a
     * pre-release only when [beta], and not past [holdOrder]. Null if none.
     */
    internal fun pick(releases: List<JSONObject>, holdOrder: Long?, beta: Boolean): JSONObject? =
        releases
            .filter { !it.optBoolean("draft") && (beta || !it.optBoolean("prerelease")) }
            .mapNotNull { r -> order(r.optString("tag_name"))?.let { it to r } }
            .filter { (o, _) -> holdOrder == null || o <= holdOrder }
            .maxByOrNull { it.first }?.second

    // ---- What's new (phase 13): this version's release notes, shown once after updating ----

    /**
     * True once after this release build was installed over an older Earshot. A fresh install (or a
     * dev build) just records the version, so friends installing for the first time aren't shown it.
     */
    fun whatsNewDue(context: Context): Boolean {
        if (!automatic) return false
        val version = BuildConfig.VERSION_NAME
        val seen = prefs(context).getString("whats_new_seen", null)
        if (seen == version) return false
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val updated = info != null && info.lastUpdateTime > info.firstInstallTime + 1_000
        if (!updated) markWhatsNewSeen(context)
        return updated
    }

    fun markWhatsNewSeen(context: Context) {
        prefs(context).edit { putString("whats_new_seen", BuildConfig.VERSION_NAME) }
    }

    /** This version's release notes; a dev build (no release of its own) gets the latest release's. */
    suspend fun releaseNotes(): ReleaseNotes {
        val version = BuildConfig.VERSION_NAME
        val url = if (order(version) != null) "https://api.github.com/repos/$REPO/releases/tags/v$version" else API
        val o = JSONObject(Http.getText(url, mapOf("Accept" to "application/vnd.github+json")))
        return ReleaseNotes(o.optString("tag_name").removePrefix("v").ifBlank { version }, o.optString("body").trim(), o.optString("html_url").ifBlank { RELEASES_PAGE })
    }

    /** GitHub's Markdown, made readable as plain text (headings, bold and link syntax removed). */
    fun plainNotes(markdown: String): String = markdown
        .replace("\r\n", "\n")
        .lines()
        .joinToString("\n") { line ->
            line.replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("^\\s*[-*]\\s+"), "• ")
                .replace("**", "")
                .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
                .replace("`", "")
        }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /**
     * True when [candidate] is a later release than [current]. Stable beats its own betas
     * ("1.2.0" > "1.2.0-beta.3" > "1.2.0-beta.2" > "1.1.9"). A dev or CI build ("-dev", "-ci") counts as
     * the oldest, and a version this app doesn't understand is never newer.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val c = order(candidate) ?: return false
        val cur = order(current) ?: 0L
        return c > cur
    }

    /**
     * Phase 16: a version as one number, the same as its Android version code (set by the release
     * workflow): MAJOR*1,000,000 + MINOR*10,000 + PATCH*100 + n, where n is the beta number (1 to 98)
     * and 99 for the stable release. Null for dev/CI builds and anything else.
     */
    fun order(version: String): Long? {
        val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?$""").find(version.trim()) ?: return null
        val (maj, min, pat) = m.groupValues.drop(1).take(3).map { it.toLong() }
        if (min > 99 || pat > 99) return null
        val n = m.groupValues[4].takeIf { it.isNotEmpty() }?.toLong() ?: 99L
        if (n !in 1..98 && m.groupValues[4].isNotEmpty()) return null
        return maj * 1_000_000 + min * 10_000 + pat * 100 + n
    }
}
