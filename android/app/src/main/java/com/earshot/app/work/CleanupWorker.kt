package com.earshot.app.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.earshot.app.EarshotApp
import com.earshot.app.playback.PlaybackService
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Once a day, deletes downloads that the retention settings say are no longer needed. */
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as EarshotApp
        return try {
            val n = app.repository.cleanupDownloads(PlaybackService.protectedEpisodeId)
            Log.i("Earshot", "Daily download cleanup: $n deleted")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Earshot", "Daily download cleanup failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "download-cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
