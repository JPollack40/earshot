package com.earshot.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.earshot.app.data.OpmlImporter
import com.earshot.app.data.Repository
import com.earshot.app.data.db.AppDatabase
import com.earshot.app.playback.PlaybackService
import com.earshot.app.playback.PlayerConnection
import com.earshot.app.work.CleanupWorker
import com.earshot.app.work.RefreshWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EarshotApp : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var repository: Repository
        private set
    lateinit var importer: OpmlImporter
        private set

    /** Survives screen changes; used for work that must finish even if the UI goes away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val player: PlayerConnection by lazy { PlayerConnection(this) }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.build(this)
        repository = Repository(this, db)
        importer = OpmlImporter(repository, appScope)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
        RefreshWorker.schedule(this)
        CleanupWorker.schedule(this)
        cleanupDownloads("app start")
    }

    /** Applies the download-retention rules now, never touching the episode loaded in the player. */
    fun cleanupDownloads(trigger: String) {
        appScope.launch {
            try {
                val n = repository.cleanupDownloads(PlaybackService.protectedEpisodeId)
                if (n > 0) Log.i("Earshot", "Download cleanup ($trigger): $n deleted")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Earshot", "Download cleanup ($trigger) failed", e)
            }
        }
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
    }
}
