package com.earshot.app.playback

import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.earshot.app.EarshotApp
import com.earshot.app.data.Settings
import com.earshot.app.data.db.EpisodeWithPodcast
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class NowPlaying(
    val episodeId: String? = null,
    val title: String = "",
    val podcastTitle: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
)

sealed interface SleepTimer {
    data object Off : SleepTimer
    data class At(val endsAtMs: Long) : SleepTimer
    data object EndOfEpisode : SleepTimer
}

/**
 * App-wide handle on the playback service. Lives as long as the process (owned by EarshotApp),
 * so the UI can come and go while audio keeps playing.
 */
class PlayerConnection(private val app: EarshotApp) {
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(NowPlaying(speed = Settings.speed(app)))
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    private val _sleep = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    val sleepTimer: StateFlow<SleepTimer> = _sleep.asStateFlow()
    private var sleepJob: Job? = null
    private var tickJob: Job? = null

    fun connect() {
        if (controller != null || future != null) return
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    this@PlayerConnection.controller = null
                    future = null
                    tickJob?.cancel()
                }
            })
            .buildAsync()
        future = f
        f.addListener({
            val c = try {
                f.get()
            } catch (e: Exception) {
                future = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            updateState()
            val queued = pending.toList()
            pending.clear()
            queued.forEach { it(c) }
            startTicker()
        }, ContextCompat.getMainExecutor(app))
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            block(c)
        } else {
            pending += block
            connect()
        }
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = app.appScope.launch {
            while (true) {
                delay(500)
                if (controller?.isPlaying == true) updateState()
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // With a queue (phase 3) this is where "sleep at end of episode" kicks in.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && _sleep.value == SleepTimer.EndOfEpisode) {
                controller?.pause()
                _sleep.value = SleepTimer.Off
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && _sleep.value == SleepTimer.EndOfEpisode) {
                _sleep.value = SleepTimer.Off
            }
        }
    }

    private fun updateState() {
        val c = controller ?: return
        val md = c.mediaMetadata
        _state.value = NowPlaying(
            episodeId = c.currentMediaItem?.mediaId,
            title = md.title?.toString().orEmpty(),
            podcastTitle = md.artist?.toString().orEmpty(),
            artworkUrl = md.artworkUri?.toString(),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            speed = c.playbackParameters.speed,
        )
    }

    // ---- Commands ----

    fun play(item: EpisodeWithPodcast) {
        val e = item.episode
        val localFile = e.localPath?.let { File(it) }?.takeIf { it.exists() }
        val uri: Uri = localFile?.let { Uri.fromFile(it) } ?: Uri.parse(e.audioUrl)
        val nearEnd = e.durationMs > 0 && e.positionMs > e.durationMs - 5_000
        val start = if (e.played || nearEnd) 0L else e.positionMs

        val mediaItem = MediaItem.Builder()
            .setMediaId(e.id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(e.title)
                    .setArtist(item.podcastTitle)
                    .setAlbumTitle(item.podcastTitle)
                    .setArtworkUri(item.artworkUrl?.let { Uri.parse(it) })
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()

        withController { c ->
            if (c.currentMediaItem?.mediaId == e.id) {
                c.play()
                return@withController
            }
            // Save where we were in the previous episode before switching.
            val prevId = c.currentMediaItem?.mediaId
            val prevPos = c.currentPosition
            if (prevId != null && c.playbackState != Player.STATE_ENDED) {
                app.appScope.launch {
                    app.repository.episodes.savePosition(prevId, prevPos, System.currentTimeMillis())
                }
            }
            c.setMediaItem(mediaItem, start)
            c.prepare()
            c.play()
        }
    }

    fun togglePlay() = withController { if (it.isPlaying) it.pause() else it.play() }
    fun seekBack() = withController { it.seekBack() }
    fun seekForward() = withController { it.seekForward() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    fun setSpeed(speed: Float) {
        Settings.setSpeed(app, speed)
        withController { it.setPlaybackSpeed(speed) }
    }

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) {
            _sleep.value = SleepTimer.Off
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _sleep.value = SleepTimer.At(endsAt)
        sleepJob = app.appScope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            _sleep.value = SleepTimer.Off
        }
    }

    fun sleepAtEndOfEpisode() {
        sleepJob?.cancel()
        _sleep.value = SleepTimer.EndOfEpisode
    }
}
