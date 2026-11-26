package com.earshot.app.data

import android.content.Context

/** Small app-wide preferences. Per-podcast settings move into the database in phase 4. */
object Settings {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun speed(context: Context): Float = prefs(context).getFloat("speed", 1.0f)

    fun setSpeed(context: Context, speed: Float) {
        prefs(context).edit().putFloat("speed", speed).apply()
    }
}
