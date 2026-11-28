package com.earshot.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.earshot.app.BuildConfig
import com.earshot.app.EarshotApp
import com.earshot.app.R
import com.earshot.app.data.Settings
import com.earshot.app.data.sync.SupabaseException
import com.earshot.app.ui.MainActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * "New episode" notifications (phase 10). The server's new-episodes function finds new episodes
 * every 15 minutes and sends this phone a Firebase Cloud Messaging data message; the app then syncs
 * (so the episode is here) and shows a notification with a Play button.
 *
 * Inert until Firebase is set up: android/app/google-services.json provides the BuildConfig values.
 */
object Push {
    private const val TAG = "Earshot"
    const val CHANNEL = "new_episodes"
    const val ACTION_PLAY = "com.earshot.app.PLAY_EPISODE"
    const val EXTRA_EPISODE = "episode_id"

    val configured: Boolean get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

    /** Starts Firebase (when configured) and registers this phone. Call from Application.onCreate. */
    fun init(app: EarshotApp) {
        app.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "New episodes", NotificationManager.IMPORTANCE_DEFAULT),
        )
        if (!configured) {
            Log.i(TAG, "Push: Firebase isn't set up (no google-services.json), notifications are off")
            return
        }
        try {
            if (FirebaseApp.getApps(app).isEmpty()) {
                FirebaseApp.initializeApp(
                    app,
                    FirebaseOptions.Builder()
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build(),
                )
            }
            register(app)
        } catch (e: Exception) {
            Log.w(TAG, "Push: couldn't start Firebase", e)
        }
    }

    /** Sends this phone's token and notification choice to Supabase (when signed in). */
    fun register(app: EarshotApp) {
        if (!configured || FirebaseApp.getApps(app).isEmpty()) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> upload(app, token) }
            .addOnFailureListener { Log.w(TAG, "Push: no token", it) }
    }

    fun upload(app: EarshotApp, token: String) {
        app.appScope.launch {
            if (!app.sync.canSync) return@launch
            try {
                val row = JSONObject()
                    .put("token", token)
                    .put("platform", "android")
                    .put("notify", Settings.newEpisodeNotify(app))
                    .put("updated_at", java.time.Instant.now().toString())
                app.sync.supabase.upsert("push_devices", "user_id,token", JSONArray().put(row))
                Log.i(TAG, "Push: registered (${Settings.newEpisodeNotify(app)})")
            } catch (e: CancellationException) {
                throw e
            } catch (e: SupabaseException) {
                Log.w(TAG, "Push: couldn't register (has 004_listening_upgrades.sql been run?)", e)
            } catch (e: Exception) {
                Log.w(TAG, "Push: couldn't register", e)
            }
        }
    }

    /** A new episode arrived from the server: sync it in, then show the notification. */
    fun onNewEpisode(app: EarshotApp, data: Map<String, String>) {
        val id = data["episode_id"] ?: return
        if (Settings.newEpisodeNotify(app) == Settings.NOTIFY_OFF) return
        app.appScope.launch {
            runCatching { app.sync.sync("new episode notification") }
            show(app, id, data["title"].orEmpty(), data["podcast"].orEmpty(), data["queued"] == "1")
        }
    }

    /** Phase 21 (D2): a warning for Serena from the server (e.g. Deepgram credit is low). */
    fun onOwnerAlert(app: EarshotApp, data: Map<String, String>) {
        val title = data["title"]?.ifBlank { null } ?: return
        val body = data["body"].orEmpty()
        val open = PendingIntent.getActivity(
            app, title.hashCode(),
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(app).notify("owner_alert".hashCode(), n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Push: notifications not allowed", e)
        }
    }

    private fun show(context: Context, episodeId: String, title: String, podcast: String, queued: Boolean) {
        val open = PendingIntent.getActivity(
            context,
            episodeId.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val play = PendingIntent.getActivity(
            context,
            episodeId.hashCode() + 1,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_EPISODE, episodeId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(podcast.ifBlank { "New episode" })
            .setContentText(title + if (queued) " (added to your queue)" else "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(title + if (queued) "\nAdded to your queue." else ""))
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, "Play", play)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(episodeId.hashCode(), n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Push: notifications not allowed", e)
        }
    }
}
