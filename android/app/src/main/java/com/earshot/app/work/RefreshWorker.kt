package com.earshot.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.earshot.app.EarshotApp
import java.util.concurrent.TimeUnit

/** Checks all feeds for new episodes every couple of hours, then saves any new feed transcripts. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as EarshotApp
        return try {
            app.repository.refreshAll()
            // Free feed transcripts for queued, new and played episodes (never Gemini).
            runCatching { app.transcripts.fetchMissingFeedTranscripts() }
            app.sync.sync("feed refresh")
            // Phase 14: any bookmark still without a title gets another try.
            BookmarkTitleWorker.enqueue(app)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "feed-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(2, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
