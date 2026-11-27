package com.earshot.app.work

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
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
import com.earshot.app.data.ai.AdSkip
import com.earshot.app.data.ai.AiKinds
import com.earshot.app.data.ai.EpisodeAiStore
import com.earshot.app.data.transcript.TranscribeState
import kotlinx.coroutines.CancellationException

/**
 * Phase 14: the "AI sweep" button (broom) on the Android Auto playing screen and in the media
 * notification. For the playing episode, in order, skipping what's already there:
 *  1. a timed transcript: the feed's own (free) if it has one, else Deepgram (Transcribe);
 *  2. generated chapters (Gemini), whose "Ad break" / "Sponsor" chapters also count as found ads;
 *  3. Find ads (Gemini), which then wins over the chapters' ad breaks.
 * Runs in the background with a notification, so it carries on with the screen off. A failed step
 * doesn't stop the next one when that one can still run. Ends with a notification saying what it did.
 */
class AiSweepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val app = applicationContext as EarshotApp
        val item = app.db.episodes().getWithPodcast(id) ?: return Result.failure()
        val episode = item.episode
        val title = episode.title
        runCatching { setForeground(foregroundInfo(title, "Starting")) }
        val done = mutableListOf<String>()
        val problems = mutableListOf<String>()

        // 1. Transcript.
        var t = app.db.transcripts().get(id)
        if (t == null || !t.timed) {
            try {
                if (t == null) app.transcripts.ensureFeedTranscript(episode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "AI sweep: feed transcript failed for $title", e)
            }
            t = app.db.transcripts().get(id)
        }
        if (t == null || !t.timed) {
            try {
                runCatching { setForeground(foregroundInfo(title, "Transcribing")) }
                app.transcripts.setState(id, TranscribeState.Running(0, 1))
                app.transcripts.transcribeWithGemini(episode) { part, parts ->
                    app.transcripts.setState(id, TranscribeState.Running(part, parts))
                }
                app.transcripts.setState(id, null)
                done += "transcript"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.transcripts.setState(id, TranscribeState.Failed(e.message ?: e.toString()))
                Log.w(TAG, "AI sweep: transcription failed for $title", e)
                notifyResult(title, "Couldn't transcribe: ${e.message ?: e}")
                return Result.failure()
            }
        }

        // 2. Chapters, 3. ads (each only if not made yet).
        val ai = app.db.episodeAi()
        for ((kind, label) in listOf(AiKinds.CHAPTERS to "chapters", AiKinds.ADS to "ads")) {
            if (ai.get(id, kind) != null) continue
            try {
                runCatching { setForeground(foregroundInfo(title, if (kind == AiKinds.ADS) "Finding ads" else "Making chapters")) }
                app.ai.generate(episode, item.podcastTitle, kind)
                done += label
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "AI sweep: $kind failed for $title", e)
                problems += "$label: ${e.message ?: e}"
            }
        }

        val ads = ai.get(id, AiKinds.ADS)?.let { EpisodeAiStore.parseAds(it.dataJson) }
            ?: ai.get(id, AiKinds.CHAPTERS)?.let { r -> AdSkip.fromChapters(EpisodeAiStore.parseChapters(r.dataJson).map { it.startMs to it.title }) }
            ?: emptyList()
        val summary = buildString {
            append(if (done.isEmpty()) "Already had a transcript, chapters and ads" else "Made " + done.joinToString(", "))
            append(". ${ads.size} ${if (ads.size == 1) "ad" else "ads"} found.")
            if (problems.isNotEmpty()) append(" Failed: " + problems.joinToString("; "))
        }
        Log.i(TAG, "AI sweep for $title: $summary")
        notifyResult(title, summary)
        return Result.success()
    }

    private fun notifyResult(title: String, text: String) {
        val n = NotificationCompat.Builder(applicationContext, EarshotApp.CHANNEL_TRANSCRIPTS)
            .setSmallIcon(com.earshot.app.R.drawable.ic_ai_sweep)
            .setContentTitle("AI sweep: $title")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(RESULT_ID_BASE + (id.hashCode() and 0xFFFF), n) }
    }

    private fun foregroundInfo(title: String, step: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, EarshotApp.CHANNEL_TRANSCRIPTS)
            .setSmallIcon(com.earshot.app.R.drawable.ic_ai_sweep)
            .setContentTitle("AI sweep: $step")
            .setContentText(title)
            .setProgress(0, 0, true)
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
        private const val TAG = "Earshot"
        private const val KEY_ID = "episode_id"
        private const val NOTIFICATION_BASE = 50_000
        private const val RESULT_ID_BASE = 51_000_000

        fun enqueue(context: Context, episodeId: String) {
            val request = OneTimeWorkRequestBuilder<AiSweepWorker>()
                .setInputData(workDataOf(KEY_ID to episodeId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("ai-sweep")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("ai-sweep-$episodeId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
