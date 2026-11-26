package com.earshot.app.data

import android.content.Context

/** Small app-wide preferences. Per-podcast settings move into the database in phase 4. */
object Settings {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun speed(context: Context): Float = prefs(context).getFloat("speed", 1.0f)

    fun setSpeed(context: Context, speed: Float) {
        prefs(context).edit().putFloat("speed", speed).apply()
    }

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
