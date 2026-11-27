package com.earshot.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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
import androidx.work.OneTimeWorkRequest
import com.earshot.app.EarshotApp
import com.earshot.app.data.Settings
import com.earshot.app.data.db.DownloadState
import com.earshot.app.data.db.Episode
import com.earshot.app.data.db.EpisodeDao
import com.earshot.app.data.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Downloads one episode to app storage. Retries with backoff; after 3 failed attempts the
 * episode is marked FAILED so the UI can offer a manual retry instead of failing silently.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val app = applicationContext as EarshotApp
        val dao = app.db.episodes()
        val episode = dao.get(id) ?: return Result.failure()

        // Android 12+ can refuse to start a foreground job while the app is in the background;
        // in that case the download just runs as a normal background job.
        runCatching { setForeground(foregroundInfo(episode.title, 0)) }
        dao.setDownload(id, DownloadState.DOWNLOADING, 0, null)

        return try {
            val file = withContext(Dispatchers.IO) { downloadTo(episode, dao) }
            dao.markDownloaded(id, file.absolutePath, System.currentTimeMillis())
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.setDownload(id, DownloadState.NONE, 0, null) }
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 2) {
                dao.setDownload(id, DownloadState.QUEUED, 0, null)
                Result.retry()
            } else {
                dao.setDownload(id, DownloadState.FAILED, 0, null)
                Result.failure()
            }
        }
    }

    private suspend fun downloadTo(episode: Episode, dao: EpisodeDao): File {
        val dir = File(applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir, "episodes")
        dir.mkdirs()
        val target = File(dir, episode.id + extensionFor(episode))
        val partial = File(dir, episode.id + ".part")

        val conn = Http.open(episode.audioUrl)
        try {
            val total = conn.contentLengthLong
            var read = 0L
            var lastPercent = -1
            var lastUpdate = 0L
            conn.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isStopped) throw CancellationException("Download stopped")
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt()
                            val now = System.currentTimeMillis()
                            if (percent != lastPercent && now - lastUpdate > 1000) {
                                lastPercent = percent
                                lastUpdate = now
                                dao.setDownloadProgress(episode.id, percent)
                                runCatching { setForeground(foregroundInfo(episode.title, percent)) }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) throw java.io.IOException("Couldn't save download")
        return target
    }

    private fun extensionFor(episode: Episode): String {
        val path = episode.audioUrl.substringBefore('?').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in setOf("mp3", "m4a", "aac", "ogg", "opus", "mp4", "wav", "flac")) return ".$ext"
        return when (episode.mimeType?.lowercase()) {
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> ".m4a"
            "audio/ogg" -> ".ogg"
            else -> ".mp3"
        }
    }

    private fun foregroundInfo(title: String, percent: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, EarshotApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setProgress(100, percent, percent <= 0)
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
        private const val NOTIFICATION_BASE = 20_000

        private fun workName(id: String) = "download-$id"

        /** Phase 14b: Settings > Downloads > Download over: Wi-Fi only (unmetered) or any network. */
        private fun constraints(context: Context): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (Settings.downloadWifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        private fun request(context: Context, episodeId: String, workId: UUID? = null): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .apply { if (workId != null) setId(workId) }
                .setInputData(workDataOf(KEY_ID to episodeId))
                .setConstraints(constraints(context))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("download")
                .build()

        fun enqueue(context: Context, episodeId: String) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(episodeId), ExistingWorkPolicy.KEEP, request(context, episodeId))
        }

        /**
         * Phase 14b: after the Download over setting changes, waiting downloads take the new network
         * rule. A download already running finishes (WorkManager applies the change to its next run).
         */
        fun applyNetworkSetting(context: Context, episodeIds: List<String>) {
            val wm = WorkManager.getInstance(context)
            for (id in episodeIds) {
                val infos = runCatching { wm.getWorkInfosForUniqueWork(workName(id)).get() }.getOrNull() ?: continue
                for (info in infos) {
                    if (info.state.isFinished) continue
                    runCatching { wm.updateWork(request(context, id, info.id)).get() }
                }
            }
        }

        fun cancel(context: Context, episodeId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(episodeId))
        }
    }
}
