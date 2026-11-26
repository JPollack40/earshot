package com.earshot.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.earshot.app.EarshotApp
import com.earshot.app.data.Settings
import com.earshot.app.ui.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "Earshot"

/**
 * Owns the ExoPlayer and the media session. The app, the notification, lock screen, headset buttons
 * and Android Auto all talk to this session.
 *
 * The player holds one episode at a time; the queue lives in the database. Whatever is played goes
 * to the top of the queue, and when it finishes it's marked played (which removes it from the queue)
 * and the next queued episode starts. Play position is saved every few seconds.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var saveJob: Job? = null
    private val app get() = application as EarshotApp
    private val repo get() = app.repository

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplug
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player.setPlaybackSpeed(Settings.speed(this))
        player.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Back 15 / forward 30 in the previous/next slots of the notification and Android Auto.
        val buttons = ImmutableList.of(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setDisplayName("Back 15 seconds")
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setDisplayName("Forward 30 seconds")
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(buttons)
            .build()

        saveJob = scope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) savePosition()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) scope.launch { savePosition() }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            updateProtected()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The previous episode is no longer loaded, so a finished one can now lose its download.
            updateProtected()
            app.cleanupDownloads("moved to next episode")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val id = player.currentMediaItem?.mediaId ?: return
            // Read now: the app side resets the sleep timer when it sees the end too.
            val stopHere = app.player.sleepTimer.value == SleepTimer.EndOfEpisode
            updateProtected()
            scope.launch {
                repo.setPlayed(id, true) // also leaves the queue
                if (stopHere) {
                    app.cleanupDownloads("episode finished")
                    return@launch
                }
                val next = repo.queueHead()
                if (next == null) {
                    app.cleanupDownloads("episode finished")
                    return@launch
                }
                Log.i(TAG, "Up next: ${next.episode.title}")
                player.setMediaItem(EpisodeMedia.item(next), EpisodeMedia.startPosition(next))
                player.prepare()
                player.play()
            }
        }
    }

    /** The loaded episode keeps its download until it has ended or the player moves on. */
    private fun updateProtected() {
        protectedEpisodeId = if (player.playbackState == Player.STATE_ENDED) null else player.currentMediaItem?.mediaId
    }

    private suspend fun savePosition() {
        val id = player.currentMediaItem?.mediaId ?: return
        if (player.playbackState == Player.STATE_ENDED) return
        val dao = repo.episodes
        dao.savePosition(id, player.currentPosition, System.currentTimeMillis())
        val duration = player.duration
        if (duration > 0) dao.setDuration(id, duration)
    }

    /** Runs [block] in the service scope and hands back its result as a future, for session callbacks. */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch {
            try {
                f.set(block())
            } catch (e: CancellationException) {
                f.cancel(false)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Media session request failed", e)
                f.setException(e)
            }
        }
        return f
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        /**
         * Every "play this episode" (from the app or from Android Auto) arrives here with just the
         * episode id. Load it from the database, put it at the top of the queue, and resume where
         * it was left off.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val requested = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()
                ?: return Futures.immediateFailedFuture(IllegalArgumentException("Nothing to play"))
            // Save where we were in the current episode before it's replaced.
            val prevId = player.currentMediaItem?.mediaId
            val prevPos = player.currentPosition
            val prevEnded = player.playbackState == Player.STATE_ENDED
            return future {
                if (prevId != null && prevId != requested.mediaId && !prevEnded) {
                    repo.episodes.savePosition(prevId, prevPos, System.currentTimeMillis())
                }
                val ep = repo.episodes.getWithPodcast(requested.mediaId)
                    ?: throw IllegalArgumentException("Unknown episode ${requested.mediaId}")
                repo.queueFirst(ep.episode.id)
                val start = if (startPositionMs != C.TIME_UNSET && startPositionMs > 0) startPositionMs else EpisodeMedia.startPosition(ep)
                MediaItemsWithStartPosition(listOf(EpisodeMedia.item(ep)), 0, start)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future {
            mediaItems.mapNotNull { repo.episodes.getWithPodcast(it.mediaId)?.let(EpisodeMedia::item) }.toMutableList()
        }

        /** "Resume" from Bluetooth, the car, or the system media controls when the app isn't running. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> = future {
            val head = repo.queueHead() ?: throw IllegalStateException("Queue is empty")
            MediaItemsWithStartPosition(listOf(EpisodeMedia.item(head)), 0, EpisodeMedia.startPosition(head))
        }

        // ---- Browse tree for Android Auto: Queue, Inbox, Latest, Podcasts (4 tabs) ----

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(EpisodeMedia.folder(EpisodeMedia.ROOT, "Earshot"), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            val ep = repo.episodes.getWithPodcast(mediaId)
            if (ep != null) LibraryResult.ofItem(EpisodeMedia.item(ep), null)
            else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val dao = repo.episodes
            val items: List<MediaItem> = when {
                parentId == EpisodeMedia.ROOT -> listOf(
                    EpisodeMedia.folder(EpisodeMedia.QUEUE, "Queue"),
                    EpisodeMedia.folder(EpisodeMedia.INBOX, "Inbox"),
                    EpisodeMedia.folder(EpisodeMedia.LATEST, "Latest"),
                    EpisodeMedia.folder(EpisodeMedia.PODCASTS, "Podcasts", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
                )
                parentId == EpisodeMedia.QUEUE -> dao.getQueue().map(EpisodeMedia::item)
                parentId == EpisodeMedia.INBOX -> dao.getInbox().map(EpisodeMedia::item)
                parentId == EpisodeMedia.LATEST -> dao.getLatest(100).map(EpisodeMedia::item)
                parentId == EpisodeMedia.PODCASTS -> repo.podcasts.getAll().map(EpisodeMedia::podcastFolder)
                parentId.startsWith(EpisodeMedia.PODCAST_PREFIX) -> {
                    val id = parentId.removePrefix(EpisodeMedia.PODCAST_PREFIX).toLongOrNull()
                    if (id == null) emptyList() else dao.getForPodcast(id, 100).map(EpisodeMedia::item)
                }
                else -> return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            }
            val from = (page * pageSize).coerceAtMost(items.size)
            val to = (from + pageSize).coerceAtMost(items.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // Final position save, then download cleanup (nothing is loaded any more). Uses the app scope
        // because this service's scope is about to be cancelled.
        val saveId = player.currentMediaItem?.mediaId?.takeIf { player.playbackState != Player.STATE_ENDED }
        val position = player.currentPosition
        val dao = repo.episodes
        player.removeListener(listener)
        protectedEpisodeId = null
        app.appScope.launch {
            if (saveId != null) dao.savePosition(saveId, position, System.currentTimeMillis())
            app.cleanupDownloads("playback session ended")
        }
        saveJob?.cancel()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        /**
         * Id of the episode loaded in the player, unless it has played to the end. Download cleanup
         * never deletes this one. Null when nothing is loaded or the service isn't running.
         */
        @Volatile
        var protectedEpisodeId: String? = null
            private set
    }
}
