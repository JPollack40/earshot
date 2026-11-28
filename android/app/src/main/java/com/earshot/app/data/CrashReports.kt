package com.earshot.app.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.earshot.app.BuildConfig
import com.earshot.app.data.sync.Supabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Phase 16: crash and "why did it stop" reports, sent to Serena's own Supabase (table crash_reports,
 * supabase/migrations/010_safe_updates.sql). Nothing goes to Google or anyone else.
 *
 * - A crash is written to a file by an uncaught-exception handler (there's no time to send it then).
 * - On the next start, Android's exit reasons (ApplicationExitInfo, Android 11+) add what a crash handler
 *   can't see: "app not responding" (with its trace), and kills for low memory or too much resource use
 *   while Earshot was playing or on screen. Ordinary kills of a cached app in the background are left out.
 * - Settings > About > Report a problem sends a note with a recent log, after a preview.
 * Reports wait in files until the person is signed in and online; at most [MAX_PENDING] are kept and at
 * most [MAX_PER_DAY] automatic ones are sent a day, so a crash loop can't flood the table.
 */
object CrashReports {
    private const val TAG = "Earshot"
    private const val PREFS = "crash_reports"
    private const val MAX_PENDING = 20
    private const val MAX_PER_DAY = 10
    private const val MAX_DETAILS = 90_000
    private const val MAX_LOG = 250_000

    private val sendLock = Mutex()

    private fun dir(context: Context) = File(context.filesDir, "crash-reports").apply { mkdirs() }
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Installs the crash handler. Call first thing in Application.onCreate. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val summary = "${error.javaClass.name}: ${error.message.orEmpty()}".take(900)
                save(app, report(app, "crash", summary, "Thread: ${thread.name}\n\n" + Log.getStackTraceString(error), recentLog(maxLines = 400, waitMs = 1_500), "", System.currentTimeMillis()))
            } catch (_: Throwable) {
                // never let the reporter hide the crash
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** One report as the JSON row crash_reports takes. */
    fun report(context: Context, kind: String, summary: String, details: String, log: String, note: String, happenedAt: Long): JSONObject =
        JSONObject()
            .put("report_id", UUID.randomUUID().toString())
            .put("kind", kind)
            .put("happened_at", Instant.ofEpochMilli(happenedAt).toString())
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("build_type", BuildConfig.BUILD_TYPE)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".take(200))
            .put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("summary", summary.take(900))
            .put("details", details.take(MAX_DETAILS))
            .put("log", log.takeLast(MAX_LOG))
            .put("note", note.take(4_500))

    private fun save(context: Context, row: JSONObject) {
        val d = dir(context)
        val files = d.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
        if (files.size >= MAX_PENDING) files.take(files.size - MAX_PENDING + 1).forEach { it.delete() }
        File(d, "${System.currentTimeMillis()}-${row.getString("report_id")}.json").writeText(row.toString())
    }

    /** This app's own recent log lines (no permission needed for its own process). */
    fun recentLog(maxLines: Int = 1_500, waitMs: Long = 4_000): String = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", maxLines.toString(), "--pid", android.os.Process.myPid().toString())
            .redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().use { out.append(it.readText()) } } }
        reader.start()
        if (!p.waitFor(waitMs, TimeUnit.MILLISECONDS)) p.destroy()
        reader.join(500)
        synchronized(out) { out.toString() }
    } catch (e: Exception) {
        "(log unavailable: ${e.message})"
    }

    /**
     * Reads Android's exit reasons since the last start and saves the ones worth reporting. Call once per
     * start, off the main thread. The first run on a phone only records where to start from.
     */
    suspend fun collectExitReasons(context: Context) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext
        val p = prefs(context)
        val am = context.getSystemService(ActivityManager::class.java) ?: return@withContext
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 16) }.getOrNull().orEmpty()
        val newest = exits.maxOfOrNull { it.timestamp } ?: 0L
        val since = p.getLong("exits_seen_until", -1L)
        p.edit { putLong("exits_seen_until", maxOf(newest, since)) }
        if (since < 0) return@withContext // first run with this feature: don't report old history
        for (e in exits.filter { it.timestamp > since }.sortedBy { it.timestamp }) {
            val kind = exitKind(e) ?: continue
            val details = buildString {
                append("Reason: ").append(reasonName(e.reason)).append(" (").append(e.reason).append(")\n")
                append("Description: ").append(e.description.orEmpty()).append('\n')
                append("Importance: ").append(e.importance).append(", status: ").append(e.status).append('\n')
                append("Memory: pss ").append(e.pss / 1024).append(" MB, rss ").append(e.rss / 1024).append(" MB\n")
                if (e.reason == ApplicationExitInfo.REASON_ANR) {
                    val trace = runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    if (!trace.isNullOrBlank()) append("\n").append(trace.take(MAX_DETAILS - 1_000))
                }
            }
            val summary = "${reasonName(e.reason)}: ${e.description.orEmpty()}".trim().trimEnd(':')
            save(context, report(context, kind, summary, details, "", "", e.timestamp))
            Log.i(TAG, "Crash reports: saved exit reason ${reasonName(e.reason)} from ${Instant.ofEpochMilli(e.timestamp)}")
        }
    }

    /** Which exits are reported. Java crashes are already saved by the handler, so they're skipped here. */
    private fun exitKind(e: ApplicationExitInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val inUse = e.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        val desc = e.description.orEmpty().lowercase()
        return when (e.reason) {
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "crash"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed"
            ApplicationExitInfo.REASON_LOW_MEMORY -> if (inUse) "killed" else null
            // Android 17's memory limiter and similar system kills (not swiping the app away).
            ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_UNKNOWN ->
                if (inUse && ("memory" in desc || "limit" in desc)) "killed" else null
            else -> null
        }
    }

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_ANR -> "App not responding"
        ApplicationExitInfo.REASON_CRASH -> "Crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Start-up failure"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Killed for using too many resources"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed for low memory"
        ApplicationExitInfo.REASON_OTHER -> "Killed by the system"
        else -> "Exit reason $r"
    }

    /** How many reports are waiting to be sent. */
    fun pendingCount(context: Context): Int = dir(context).listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

    /** Saves a "Report a problem" note (sent with [sendPending]). */
    fun saveProblemReport(context: Context, row: JSONObject) = save(context, row)

    /**
     * Sends waiting reports when signed in. Problem reports always go; automatic ones up to
     * [MAX_PER_DAY] a day (the rest are dropped, oldest first). Returns how many were sent.
     */
    suspend fun sendPending(context: Context, supabase: Supabase, signedIn: Boolean): Int = sendLock.withLock {
        if (!signedIn || !supabase.configured) return 0
        withContext(Dispatchers.IO) {
            val p = prefs(context)
            val today = System.currentTimeMillis() / 86_400_000L
            if (p.getLong("day", -1) != today) p.edit { putLong("day", today).putInt("sent_today", 0) }
            var sentToday = p.getInt("sent_today", 0)
            var sent = 0
            val files = dir(context).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
            for (f in files) {
                val row = runCatching { JSONObject(f.readText()) }.getOrNull()
                if (row == null) {
                    f.delete()
                    continue
                }
                val automatic = row.optString("kind") != "report"
                if (automatic && sentToday >= MAX_PER_DAY) {
                    f.delete()
                    continue
                }
                try {
                    supabase.insertNew("crash_reports", "user_id,report_id", JSONArray().put(row))
                    f.delete()
                    sent++
                    if (automatic) sentToday++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: com.earshot.app.data.sync.SupabaseException) {
                    if (e.status in listOf(400, 403, 409, 413, 422)) {
                        Log.w(TAG, "Crash reports: server refused one (${e.status} ${e.message}), dropped")
                        f.delete()
                        continue
                    }
                    Log.w(TAG, "Crash reports: sending failed, will retry: ${e.status} ${e.message}")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Crash reports: sending failed, will retry: ${e.message}")
                    break
                }
            }
            p.edit { putInt("sent_today", sentToday) }
            if (sent > 0) Log.i(TAG, "Crash reports: sent $sent")
            sent
        }
    }
}
