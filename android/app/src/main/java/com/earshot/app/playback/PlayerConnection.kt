package com.earshot.app.playback

import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
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

/** After a big jump, where "Back to mm:ss" returns to (phase 10). Shown until [untilMs] (wall clock). */
data class JumpBack(val episodeId: String, val fromMs: Long, val untilMs: Long)

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

    private val _state = MutableStateFlow(NowPlaying(speed = Settings.playbackDefaults(app).value.speed))
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    private val _sleep = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    val sleepTimer: StateFlow<SleepTimer> = _sleep.asStateFlow()
    private var sleepJob: Job? = null
    private var tickJob: Job? = null

    private val _jumpBack = MutableStateFlow<JumpBack?>(null)
    val jumpBack: StateFlow<JumpBack?> = _jumpBack.asStateFlow()
    private var jumpJob: Job? = null

    /**
     * A jump of 30 s or more within the playing episode (chapter, transcript line, bookmark,
     * scrubber) offers a way back for a few seconds. Skip buttons don't count.
     */
    private fun noteJump(c: MediaController, targetMs: Long) {
        val id = c.currentMediaItem?.mediaId ?: return
        val from = c.currentPosition
        if (kotlin.math.abs(targetMs - from) < JUMP_MIN_MS) return
        val j = JumpBack(id, from, System.currentTimeMillis() + JUMP_SHOW_MS)
        _jumpBack.value = j
        jumpJob?.cancel()
        jumpJob = app.appScope.launch {
            delay(JUMP_SHOW_MS)
            if (_jumpBack.value == j) _jumpBack.value = null
        }
    }

    /** "Back to mm:ss": returns to where the last big jump started. */
    fun undoJump() {
        val j = _jumpBack.value ?: return
        _jumpBack.value = null
        withController { c ->
            if (c.currentMediaItem?.mediaId == j.episodeId) c.seekTo(j.fromMs)
        }
    }

    fun dismissJump() {
        _jumpBack.value = null
    }

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
            loadQueueHead(c)
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

        override fun onPlaybackStateChanged(playbackState: Int) {
            // The service checks this flag before starting the next queued episode.
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

    /**
     * Plays an episode. Only the id is sent: the service looks the episode up, moves it to the top
     * of the queue and resumes from the saved position (the same path Android Auto uses).
     */
    fun play(item: EpisodeWithPodcast) {
        val id = item.episode.id
        withController { c ->
            // Same episode still loaded: just resume. After it has ended, load it again instead,
            // because its download may have been deleted when it finished.
            if (c.currentMediaItem?.mediaId == id && c.playbackState != Player.STATE_ENDED) {
                app.appScope.launch { app.repository.queueFirst(id) }
                c.play()
                return@withController
            }
            c.setMediaItem(MediaItem.Builder().setMediaId(id).build())
            c.prepare()
            c.play()
        }
    }

    /**
     * Phase 15: "Hey Google, play <show> on Earshot" on the phone (MEDIA_PLAY_FROM_SEARCH). Hands the
     * words to the service, which picks the episode the same way as a voice request in the car.
     * A blank query resumes what's loaded.
     */
    fun playFromSearch(query: String?) {
        withController { c ->
            if (query.isNullOrBlank()) {
                if (c.mediaItemCount > 0) {
                    c.play()
                } else {
                    app.appScope.launch { app.repository.queueHead()?.let { play(it) } }
                }
                return@withController
            }
            c.setMediaItem(
                MediaItem.Builder()
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build())
                    .build(),
            )
            c.prepare()
            c.play()
        }
    }

    /**
     * Plays an episode from [positionMs] (a transcript line, a bookmark or a search result). If it's
     * already loaded it just seeks there.
     */
    fun playAt(item: EpisodeWithPodcast, positionMs: Long) {
        val id = item.episode.id
        withController { c ->
            if (c.currentMediaItem?.mediaId == id && c.playbackState != Player.STATE_ENDED) {
                noteJump(c, positionMs)
                c.seekTo(positionMs)
                c.play()
                return@withController
            }
            c.setMediaItem(MediaItem.Builder().setMediaId(id).build(), positionMs.coerceAtLeast(1))
            c.prepare()
            c.play()
        }
    }

    /** At app start, show the top of the queue in the mini player, paused, ready to resume. */
    private fun loadQueueHead(c: MediaController) {
        if (c.mediaItemCount > 0) return
        app.appScope.launch {
            val head = app.repository.queueHead() ?: return@launch
            if (c.mediaItemCount > 0) return@launch
            c.setMediaItem(MediaItem.Builder().setMediaId(head.episode.id).build())
            c.prepare()
        }
    }

    fun togglePlay() = withController { if (it.isPlaying) it.pause() else it.play() }

    /** Skips by an exact length (the app knows the current podcast's skip lengths). */
    fun skipBy(deltaMs: Long) = withController { c ->
        val target = (c.currentPosition + deltaMs).coerceAtLeast(0)
        val d = c.duration
        c.seekTo(if (d > 0) target.coerceAtMost(d) else target)
    }

    fun seekTo(positionMs: Long) = withController {
        noteJump(it, positionMs)
        it.seekTo(positionMs)
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
            withController { it.pause() } // phase 17: also when only the car or the service is connected
            _sleep.value = SleepTimer.Off
        }
    }

    fun sleepAtEndOfEpisode() {
        sleepJob?.cancel()
        _sleep.value = SleepTimer.EndOfEpisode
    }

    private companion object {
        const val JUMP_MIN_MS = 30_000L
        const val JUMP_SHOW_MS = 8_000L
    }
}
