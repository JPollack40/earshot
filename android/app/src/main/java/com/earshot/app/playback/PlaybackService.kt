package com.earshot.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.earshot.app.EarshotApp
import com.earshot.app.data.PlaybackPrefs
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var saveJob: Job? = null
    private val app get() = application as EarshotApp
    private val repo get() = app.repository

    override fun onCreate() {
        super.onCreate()
        // Our own audio chain, for Smart Speed and Voice Boost.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .setAudioProcessorChain(audio)
                .build()
        }
        player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplug
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener)
        skipPlayer = SkipLengthPlayer(player)
        prefs = PlaybackPrefs.resolve(Settings.playbackDefaults(this).value, null)
        player.setPlaybackSpeed(prefs.speed)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, skipPlayer, LibraryCallback())
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(skipButtons(prefs.skipBackMs, prefs.skipForwardMs))
            .build()

        // Apply the loaded episode's settings (its podcast's, else the defaults) whenever the
        // episode or either set of settings changes.
        scope.launch {
            loadedEpisodeId
                .flatMapLatest { id -> if (id == null) flowOf(null) else repo.episodes.observeWithPodcast(id) }
                .map { it?.episode?.podcastId }
                .distinctUntilChanged()
                .flatMapLatest { pid -> if (pid == null) flowOf(null) else repo.podcasts.observe(pid) }
                .combine(Settings.playbackDefaults(this@PlaybackService)) { podcast, defaults ->
                    PlaybackPrefs.resolve(defaults, podcast)
                }
                .distinctUntilChanged()
                .collect(::applyPrefs)
        }

        // Android Auto caches browse lists: tell it when the queue, inbox or podcast list changes.
        scope.launch {
            repo.observeQueue().drop(1).collect { session?.notifyChildrenChanged(EpisodeMedia.QUEUE, it.size, null) }
        }
        scope.launch {
            repo.observeInbox().drop(1).collect { session?.notifyChildrenChanged(EpisodeMedia.INBOX, it.size, null) }
        }
        scope.launch {
            repo.observeLibrary().drop(1).collect { session?.notifyChildrenChanged(EpisodeMedia.PODCASTS, it.size, null) }
        }

        saveJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(1_000)
                trackTimeSaved()
                if (++tick % 5 == 0) {
                    flushTimeSaved()
                    if (player.isPlaying) savePosition()
                }
            }
        }
    }

    // ---- Playback settings: speed, Smart Speed, Voice Boost, skip lengths ----

    private val audio = SmartAudioChain()
    private lateinit var skipPlayer: SkipLengthPlayer
    private lateinit var prefs: PlaybackPrefs
    private val loadedEpisodeId = MutableStateFlow<String?>(null)

    private fun applyPrefs(p: PlaybackPrefs) {
        val old = prefs
        prefs = p
        Log.i(TAG, "Playback settings: $p")
        if (player.playbackParameters.speed != p.speed) player.setPlaybackSpeed(p.speed)
        if (player.skipSilenceEnabled != p.smartSpeed) player.skipSilenceEnabled = p.smartSpeed
        audio.voiceBoost.enabled = p.voiceBoost
        skipPlayer.backMs = p.skipBackMs
        skipPlayer.forwardMs = p.skipForwardMs
        if (old.skipBackMs != p.skipBackMs || old.skipForwardMs != p.skipForwardMs) {
            session?.setMediaButtonPreferences(skipButtons(p.skipBackMs, p.skipForwardMs))
        }
    }

    // ---- Time saved by speed and Smart Speed ----

    private var lastTickAt = 0L
    private var lastSkippedFrames = 0L
    private var pendingEpisodeId: String? = null
    private var pendingSpeedMs = 0.0
    private var pendingSmartMs = 0.0

    /**
     * Called every second. Listening for t seconds at speed s covers t*s of the episode, saving
     * t*(s-1). Smart Speed's skipped silence (in episode time) would have taken skipped/s to hear.
     */
    private fun trackTimeSaved() {
        val now = SystemClock.elapsedRealtime()
        val frames = audio.silence.skippedFrames
        // The count restarts from zero when the audio is flushed (seeks, new episode).
        val newFrames = if (frames >= lastSkippedFrames) frames - lastSkippedFrames else frames
        lastSkippedFrames = frames
        val elapsed = if (lastTickAt == 0L) 0L else now - lastTickAt
        lastTickAt = now
        val id = player.currentMediaItem?.mediaId
        if (!player.isPlaying || id == null || elapsed <= 0 || elapsed > 5_000) return
        if (id != pendingEpisodeId) {
            flushTimeSaved()
            pendingEpisodeId = id
        }
        val speed = player.playbackParameters.speed
        pendingSpeedMs += elapsed * (speed - 1.0)
        val rate = player.audioFormat?.sampleRate ?: 0
        if (newFrames > 0 && rate > 0) pendingSmartMs += newFrames * 1000.0 / rate / speed
    }

    private fun flushTimeSaved() {
        val id = pendingEpisodeId ?: return
        val speedMs = pendingSpeedMs.toLong()
        val smartMs = pendingSmartMs.toLong()
        if (speedMs == 0L && smartMs == 0L) return
        pendingSpeedMs -= speedMs
        pendingSmartMs -= smartMs
        Settings.addTimeSaved(this, speedMs, smartMs)
        val dao = repo.episodes
        app.appScope.launch { dao.addTimeSaved(id, speedMs + smartMs) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) scope.launch { savePosition() }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            updateProtected()
            loadedEpisodeId.value = player.currentMediaItem?.mediaId
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
            // Folders must resolve too: Media3 only accepts a browser's subscription to a list
            // (which is how Android Auto hears about changes) if onGetItem returns it as browsable.
            val folder = when {
                mediaId == EpisodeMedia.ROOT -> EpisodeMedia.folder(EpisodeMedia.ROOT, "Earshot")
                mediaId == EpisodeMedia.QUEUE -> EpisodeMedia.folder(EpisodeMedia.QUEUE, "Queue")
                mediaId == EpisodeMedia.INBOX -> EpisodeMedia.folder(EpisodeMedia.INBOX, "New Episodes")
                mediaId == EpisodeMedia.LATEST -> EpisodeMedia.folder(EpisodeMedia.LATEST, "Latest")
                mediaId == EpisodeMedia.PODCASTS ->
                    EpisodeMedia.folder(EpisodeMedia.PODCASTS, "Podcasts", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
                mediaId.startsWith(EpisodeMedia.PODCAST_PREFIX) ->
                    mediaId.removePrefix(EpisodeMedia.PODCAST_PREFIX).toLongOrNull()
                        ?.let { repo.podcasts.get(it) }?.let(EpisodeMedia::podcastFolder)
                else -> null
            }
            val item = folder ?: repo.episodes.getWithPodcast(mediaId)?.let(EpisodeMedia::item)
            if (item != null) LibraryResult.ofItem(item, null)
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
                    EpisodeMedia.folder(EpisodeMedia.INBOX, "New Episodes"),
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
        trackTimeSaved()
        flushTimeSaved()
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
