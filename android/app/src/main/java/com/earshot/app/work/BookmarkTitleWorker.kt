package com.earshot.app.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.earshot.app.EarshotApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Phase 14: titles bookmarks that don't have a title yet, once their episode has a timed transcript.
 * Runs when a transcript arrives, after a failed first try, and with every feed refresh. When Gemini is
 * busy or the phone is offline it tries again later (2, 4, 8, 16 minutes), then waits for the next
 * refresh. The server also titles leftovers every 15 minutes, and either one's title syncs to the other.
 */
class BookmarkTitleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as EarshotApp
        return try {
            val run = app.transcripts.titleUntitled()
            if (run.titled > 0) runCatching { app.sync.sync("bookmark titles") }
            if (run.retryable > 0 && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Earshot", "Bookmark titles failed (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val NAME = "bookmark-titles"
        private const val MAX_RETRIES = 4

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<BookmarkTitleWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
                .addTag(NAME)
                .build()
            // A run already going might have read the list before this transcript arrived, so one more
            // run is queued after it (a run with nothing to do finishes at once).
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
