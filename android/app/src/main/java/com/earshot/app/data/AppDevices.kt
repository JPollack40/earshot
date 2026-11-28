package com.earshot.app.data

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.earshot.app.BuildConfig
import com.earshot.app.data.sync.Supabase
import com.earshot.app.data.sync.SupabaseException
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Phase 21 (D1): after a sync, at most hourly, this phone tells the server its app version, Android
 * version and when it last synced (app_devices, 014_owner_console.sql), for Serena's owner console.
 * Nothing else about the phone is sent.
 */
object AppDevices {
    private const val TAG = "AppDevices"
    private const val EVERY_MS = 60 * 60_000L

    /** Skipped for the rest of this run once the server says the table isn't there (014 not run). */
    @Volatile private var available = true

    fun row(context: Context, lastSyncAt: Long): JSONObject = JSONObject()
        .put("device_id", Handoff.deviceId(context))
        .put("app_version", BuildConfig.VERSION_NAME)
        .put("version_code", BuildConfig.VERSION_CODE)
        .put("build_type", BuildConfig.BUILD_TYPE)
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".take(200))
        .put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        .put("last_sync_at", Instant.ofEpochMilli(lastSyncAt).toString())
        .put("last_seen_at", Instant.now().toString())

    suspend fun report(context: Context, supabase: Supabase, lastSyncAt: Long) {
        if (!available) return
        val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val sentAt = p.getLong("app_device_sent_at", 0)
        val sentVersion = p.getInt("app_device_version", 0)
        // Hourly, or at once after an update.
        if (System.currentTimeMillis() - sentAt < EVERY_MS && sentVersion == BuildConfig.VERSION_CODE) return
        try {
            supabase.upsert("app_devices", "user_id,device_id", JSONArray().put(row(context, lastSyncAt)))
            p.edit {
                putLong("app_device_sent_at", System.currentTimeMillis())
                putInt("app_device_version", BuildConfig.VERSION_CODE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SupabaseException) {
            if (e.status == 404) available = false
            Log.w(TAG, "App device report failed: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "App device report failed", e)
        }
    }
}
