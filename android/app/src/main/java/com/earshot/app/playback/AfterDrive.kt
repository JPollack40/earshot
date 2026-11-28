package com.earshot.app.playback

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.earshot.app.EarshotApp
import com.earshot.app.R
import com.earshot.app.ui.MainActivity

/**
 * Phase 20 (C2): one quiet notification after a drive (Android Auto disconnected), when something happened
 * in the car: the bookmarks made and the episodes finished. Tapping it opens Earshot to tidy them.
 */
object AfterDrive {
    private const val ID = 0x0D51

    /** "2 bookmarks · finished 1: Title". Null when nothing happened on the drive. */
    fun text(bookmarks: Int, finished: List<String>): String? {
        if (bookmarks == 0 && finished.isEmpty()) return null
        val parts = mutableListOf<String>()
        if (bookmarks > 0) parts += if (bookmarks == 1) "1 bookmark" else "$bookmarks bookmarks"
        if (finished.isNotEmpty()) parts += "finished ${finished.size}: " + finished.joinToString(", ") { it.take(60) }
        return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }

    fun notify(context: Context, bookmarks: Int, finished: List<String>) {
        val text = text(bookmarks, finished) ?: return
        val open = PendingIntent.getActivity(
            context, ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, EarshotApp.CHANNEL_DRIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("After your drive")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(ID, n) }
    }
}
