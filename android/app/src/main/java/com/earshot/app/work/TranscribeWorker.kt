package com.earshot.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.earshot.app.EarshotApp
import com.earshot.app.data.transcript.TranscribeState
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Transcribes one episode with Deepgram (only when asked, from the episode's menu or its transcript
 * page). Runs as a foreground job with a progress notification, because a long episode takes several
 * minutes (one call per 15 minutes of audio). Parts already done survive a retry.
 */
class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val app = applicationContext as EarshotApp
        val episode = app.db.episodes().get(id)
        if (episode == null) {
            app.transcripts.setState(id, TranscribeState.Failed("The episode isn't in the library any more"))
            return Result.failure()
        }
        runCatching { setForeground(foregroundInfo(episode.title, 0, 0)) }
        return try {
            app.transcripts.transcribeWithGemini(episode) { done, parts ->
                app.transcripts.setState(id, TranscribeState.Running(done, parts))
                runCatching { setForeground(foregroundInfo(episode.title, done, parts)) }
            }
            app.transcripts.setState(id, null)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Earshot", "Transcription failed for ${episode.title} (attempt ${runAttemptCount + 1})", e)
            val message = e.message ?: e.toString()
            // Rate limits and network hiccups are worth another go; a refusal isn't.
            val retryable = message.contains("429") || message.contains("HTTP 5") || message.contains("timeout", ignoreCase = true) ||
                e is java.io.IOException && e !is com.earshot.app.data.sync.SupabaseException
            if (retryable && runAttemptCount < 3) {
                app.transcripts.setState(id, TranscribeState.Failed("$message (trying again shortly)"))
                Result.retry()
            } else {
                app.transcripts.setState(id, TranscribeState.Failed(message))
                Result.failure()
            }
        }
    }

    private fun foregroundInfo(title: String, done: Int, parts: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, EarshotApp.CHANNEL_TRANSCRIPTS)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(if (parts > 0) "Transcribing (part ${minOf(done + 1, parts)} of $parts)" else "Transcribing")
            .setContentText(title)
            .setProgress(parts.coerceAtLeast(1), done, parts == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val notificationId = NOTIFICATION_BASE + (id.hashCode() and 0xFFFF)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        private const val KEY_ID = "episode_id"
        private const val NOTIFICATION_BASE = 40_000

        private fun workName(id: String) = "transcribe-$id"

        fun enqueue(context: Context, episodeId: String) {
            val request = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setInputData(workDataOf(KEY_ID to episodeId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .addTag("transcribe")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(episodeId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, episodeId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(episodeId))
        }
    }
}
