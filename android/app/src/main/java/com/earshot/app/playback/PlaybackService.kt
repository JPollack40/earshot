package com.earshot.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateFormat
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
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.earshot.app.EarshotApp
import com.earshot.app.R
import com.earshot.app.data.PlaybackPrefs
import com.earshot.app.data.Settings
import com.earshot.app.data.db.EpisodeWithPodcast
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
 * The queue lives in the database. The player holds a copy of it (the playing episode first, then
 * the rest of the queue) so Android Auto can show the whole queue, but it never moves on by itself:
 * it pauses at the end of each episode, which is then marked played (leaving the queue), and the
 * next queued episode is loaded at its saved position. Whatever is played goes to the top of the
 * queue. Play position is saved every few seconds.
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
        player.setPauseAtEndOfMediaItems(true)
        player.addListener(listener)
        skipPlayer = SkipLengthPlayer(player)
        skipPlayer.onJump = ::jumpTo
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
            .setMediaButtonPreferences(buttons())
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )

        // The Sleep button's label follows the sleep timer, wherever it was set.
        scope.launch {
            app.player.sleepTimer.drop(1).collect { refreshButtons() }
        }

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

        // Keep the player's copy of the queue up to date, without interrupting the playing episode.
        scope.launch {
            repo.observeQueue().collect(::syncPlaylist)
        }

        // Android Auto caches browse lists: tell it when one changes. The database reports every
        // write (including the position saves every few seconds), so compare a signature that leaves
        // out the play position and only notify when it really changed.
        scope.launch {
            repo.observeQueue().map { list -> list.map(::signature) }.distinctUntilChanged().drop(1)
                .collect { session?.notifyChildrenChanged(EpisodeMedia.QUEUE, it.size, null) }
        }
        scope.launch {
            repo.observeInbox().map { list -> list.map(::signature) }.distinctUntilChanged().drop(1)
                .collect { session?.notifyChildrenChanged(EpisodeMedia.INBOX, it.size, null) }
        }
        scope.launch {
            repo.observeLibrary()
                .map { list -> list.map { Triple(it.podcast.id, it.podcast.title + "|" + it.podcast.pinned, it.podcast.imageUrl) } }
                .distinctUntilChanged().drop(1)
                .collect { session?.notifyChildrenChanged(EpisodeMedia.PODCASTS, it.size, null) }
        }
        scope.launch {
            // Latest, and each podcast's episode list (only the podcasts whose episodes changed).
            var last: Map<Long, List<EpisodeSignature>>? = null
            repo.observeLatest()
                .map { list -> list.groupBy({ it.episode.podcastId }, ::signature) }
                .distinctUntilChanged()
                .collect { byPodcast ->
                    val before = last
                    last = byPodcast
                    if (before == null) return@collect
                    val s = session ?: return@collect
                    s.notifyChildrenChanged(EpisodeMedia.LATEST, byPodcast.values.sumOf { it.size }.coerceAtMost(LATEST_LIMIT), null)
                    for (id in byPodcast.keys + before.keys) {
                        if (byPodcast[id] != before[id]) {
                            s.notifyChildrenChanged(EpisodeMedia.PODCAST_PREFIX + id, byPodcast[id]?.size ?: 0, null)
                        }
                    }
                }
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
        if (old.skipBackMs != p.skipBackMs || old.skipForwardMs != p.skipForwardMs || old.speed != p.speed) {
            refreshButtons()
        }
    }

    // ---- Playing-screen buttons: skip back / forward, Speed, Sleep, Mark played ----

    private fun buttons() =
        PlayerButtons.all(prefs.skipBackMs, prefs.skipForwardMs, prefs.speed, app.player.sleepTimer.value, sleepLabel())

    private fun refreshButtons() {
        session?.setMediaButtonPreferences(buttons())
    }

    private fun sleepLabel(): String = when (val t = app.player.sleepTimer.value) {
        SleepTimer.Off -> "Sleep timer"
        SleepTimer.EndOfEpisode -> "Sleep: end of episode"
        is SleepTimer.At -> "Sleep at " + DateFormat.getTimeFormat(this).format(java.util.Date(t.endsAtMs))
    }

    /** Next speed in [PlayerButtons.SPEED_STEPS], saved where the app would save it. */
    private fun stepSpeed() {
        val speed = PlayerButtons.nextSpeed(prefs.speed)
        val id = player.currentMediaItem?.mediaId
        scope.launch {
            val podcast = id?.let { repo.episodes.getWithPodcast(it) }?.episode?.podcastId?.let { repo.podcasts.get(it) }
            // Same rule as the app: a podcast with its own audio settings keeps the change to itself.
            if (podcast != null && (podcast.speed != null || podcast.smartSpeed != null || podcast.voiceBoost != null)) {
                repo.updatePodcastPlayback(podcast.id) { it.copy(speed = speed) }
            } else {
                Settings.updatePlaybackDefaults(this@PlaybackService) { it.copy(speed = speed) }
            }
        }
    }

    /** Off, 15, 30, 60 minutes, end of episode, off. */
    private fun stepSleep() {
        val conn = app.player
        conn.connect() // the timer pauses through the app's connection, which may not be open in the car
        when (val step = PlayerButtons.nextSleep(conn.sleepTimer.value)) {
            PlayerButtons.SleepStep.Off -> conn.setSleepTimer(null)
            is PlayerButtons.SleepStep.Minutes -> conn.setSleepTimer(step.minutes)
            PlayerButtons.SleepStep.EndOfEpisode -> conn.sleepAtEndOfEpisode()
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

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Paused by setPauseAtEndOfMediaItems: the episode has played to the end.
            if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
            val id = player.currentMediaItem?.mediaId ?: return
            finishCurrent(id, playNext = true, atEnd = true)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            player.setPauseAtEndOfMediaItems(true)
            updateProtected()
            val id = player.currentMediaItem?.mediaId ?: return
            finishCurrent(id, playNext = true, atEnd = true)
            // The ended episode is no longer protected, so its download can go now if it was played.
            app.cleanupDownloads("episode ended")
        }
    }

    // ---- Moving through the queue ----

    private var finishingId: String? = null
    private var finishingAt = 0L

    /**
     * The episode [id] is done: it played to the end ([atEnd]) or was marked played. Mark it played
     * (which takes it out of the queue) and load the next queued episode at its saved position,
     * playing if [playNext] and no "end of episode" sleep timer is set.
     */
    private fun finishCurrent(id: String, playNext: Boolean, atEnd: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (finishingId == id && now - finishingAt < 10_000) return // the pause and the end both report it
        finishingId = id
        finishingAt = now
        // Read now: the app side also resets the sleep timer when it sees the end.
        val stopHere = app.player.sleepTimer.value == SleepTimer.EndOfEpisode
        if (stopHere) app.player.setSleepTimer(null)
        scope.launch {
            repo.setPlayed(id, true) // also leaves the queue
            if (player.currentMediaItem?.mediaId == id) {
                val next = repo.queueHead()
                if (next != null) {
                    Log.i(TAG, "Up next: ${next.episode.title}")
                    load(next, EpisodeMedia.startPosition(next), play = playNext && !stopHere)
                } else if (atEnd && player.playbackState != Player.STATE_ENDED) {
                    // Nothing left: let the finished episode end, as it did before the player held the queue.
                    val i = player.currentMediaItemIndex
                    if (i + 1 < player.mediaItemCount) player.removeMediaItems(i + 1, player.mediaItemCount)
                    if (i > 0) player.removeMediaItems(0, i)
                    player.setPauseAtEndOfMediaItems(false)
                    player.play()
                } else if (!atEnd) {
                    player.pause()
                }
            }
            updateProtected()
            app.cleanupDownloads("episode finished")
        }
    }

    /** The playlist for [ep]: it first, then the rest of the queue. */
    private suspend fun playlist(ep: EpisodeWithPodcast): List<MediaItem> =
        listOf(EpisodeMedia.item(ep)) + repo.episodes.getQueue().filter { it.episode.id != ep.episode.id }.map { EpisodeMedia.item(it) }

    private suspend fun load(ep: EpisodeWithPodcast, startMs: Long, play: Boolean) {
        val items = playlist(ep)
        player.setPauseAtEndOfMediaItems(true)
        player.setMediaItems(items, 0, startMs)
        player.prepare()
        if (play) player.play()
    }

    /** Another queued episode was picked (in Android Auto's queue): save this one's place and switch. */
    private fun jumpTo(index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        val target = player.getMediaItemAt(index).mediaId
        val prevId = player.currentMediaItem?.mediaId
        val prevPos = player.currentPosition
        val prevEnded = player.playbackState == Player.STATE_ENDED
        scope.launch {
            if (prevId != null && prevId != target && !prevEnded) {
                repo.episodes.savePosition(prevId, prevPos, System.currentTimeMillis())
            }
            val ep = repo.episodes.getWithPodcast(target) ?: return@launch
            repo.queueFirst(target)
            load(ep, EpisodeMedia.startPosition(ep), play = true)
        }
    }

    /** Brings the player's copy of the queue in line with the database, leaving the playing episode alone. */
    private fun syncPlaylist(queue: List<EpisodeWithPodcast>) {
        val count = player.mediaItemCount
        if (count == 0) return
        val index = player.currentMediaItemIndex
        val current = player.currentMediaItem?.mediaId ?: return
        val rest = queue.filter { it.episode.id != current }
        val have = (0 until count).filter { it != index }.map { player.getMediaItemAt(it).mediaId }
        if (index == 0 && have == rest.map { it.episode.id }) return
        if (index > 0) player.removeMediaItems(0, index)
        player.replaceMediaItems(1, player.mediaItemCount, rest.map { EpisodeMedia.item(it) })
    }

    /** What Android Auto shows for an episode, minus the exact play position. */
    private data class EpisodeSignature(val id: String, val played: Boolean, val started: Boolean, val download: Int, val title: String)

    private fun signature(e: EpisodeWithPodcast) =
        EpisodeSignature(e.episode.id, e.episode.played, e.episode.positionMs > 0, e.episode.downloadState, e.episode.title)

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
            // "Hey Google, play ... on Earshot" arrives with a search query and no id.
            val query = requested.requestMetadata.searchQuery
                ?.takeIf { requested.mediaId.isEmpty() }
            // Save where we were in the current episode before it's replaced.
            val prevId = player.currentMediaItem?.mediaId
            val prevPos = player.currentPosition
            val prevEnded = player.playbackState == Player.STATE_ENDED
            return future {
                val ep = if (query != null) {
                    voiceSearch(query) ?: throw IllegalArgumentException("Nothing found for \"$query\"")
                } else {
                    repo.episodes.getWithPodcast(requested.mediaId)
                        ?: throw IllegalArgumentException("Unknown episode ${requested.mediaId}")
                }
                if (prevId != null && prevId != ep.episode.id && !prevEnded) {
                    repo.episodes.savePosition(prevId, prevPos, System.currentTimeMillis())
                }
                repo.queueFirst(ep.episode.id)
                val start = if (startPositionMs != C.TIME_UNSET && startPositionMs > 0) startPositionMs else EpisodeMedia.startPosition(ep)
                player.setPauseAtEndOfMediaItems(true)
                finishingId = null
                MediaItemsWithStartPosition(playlist(ep), 0, start)
            }
        }

        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            // Start from Media3's defaults for this controller (full control when trusted, read-only
            // otherwise) and add the Speed, Sleep and Mark played buttons. Android Auto shows the
            // buttons the media notification's controller has, and its taps are checked against its
            // own controller, so every controller gets them. They only change Earshot's own settings.
            val defaults = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(
                    defaults.availableSessionCommands.buildUpon()
                        .add(PlayerButtons.speedCommand)
                        .add(PlayerButtons.sleepCommand)
                        .add(PlayerButtons.markPlayedCommand)
                        .build(),
                )
            return Futures.immediateFuture(builder.build())
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                PlayerButtons.SPEED -> stepSpeed()
                PlayerButtons.SLEEP -> stepSleep()
                PlayerButtons.MARK_PLAYED -> player.currentMediaItem?.mediaId?.let {
                    finishCurrent(it, playNext = player.playWhenReady, atEnd = false)
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
            player.setPauseAtEndOfMediaItems(true)
            MediaItemsWithStartPosition(playlist(head), 0, EpisodeMedia.startPosition(head))
        }

        // ---- Browse tree for Android Auto: Queue, Inbox, Latest, Podcasts (4 tabs) ----

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Defaults for the whole tree: episodes as lists, folders (podcasts) as an artwork grid.
            val extras = Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            }
            val rootParams = LibraryParams.Builder()
                .setOffline(params?.isOffline == true)
                .setRecent(params?.isRecent == true)
                .setSuggested(params?.isSuggested == true)
                .setExtras(extras)
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(EpisodeMedia.folder(EpisodeMedia.ROOT, "Earshot"), rootParams))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            // Folders must resolve too: Media3 only accepts a browser's subscription to a list
            // (which is how Android Auto hears about changes) if onGetItem returns it as browsable.
            val folder = when {
                mediaId == EpisodeMedia.ROOT -> EpisodeMedia.folder(EpisodeMedia.ROOT, "Earshot")
                mediaId.startsWith(EpisodeMedia.PODCAST_PREFIX) ->
                    mediaId.removePrefix(EpisodeMedia.PODCAST_PREFIX).toLongOrNull()
                        ?.let { repo.podcasts.get(it) }?.let(EpisodeMedia::podcastFolder)
                else -> tabs().firstOrNull { it.mediaId == mediaId }
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
                parentId == EpisodeMedia.ROOT -> tabs()
                parentId == EpisodeMedia.QUEUE -> dao.getQueue().map { EpisodeMedia.item(it) }
                parentId == EpisodeMedia.INBOX -> EpisodeMedia.itemsByDate(dao.getInbox())
                parentId == EpisodeMedia.LATEST -> EpisodeMedia.itemsByDate(dao.getLatest(LATEST_LIMIT))
                parentId == EpisodeMedia.PODCASTS -> repo.podcasts.getAll().map(EpisodeMedia::podcastFolder)
                parentId.startsWith(EpisodeMedia.PODCAST_PREFIX) -> {
                    val id = parentId.removePrefix(EpisodeMedia.PODCAST_PREFIX).toLongOrNull()
                    if (id == null) emptyList() else dao.getForPodcast(id, 100).map { EpisodeMedia.item(it) }
                }
                else -> return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            }
            LibraryResult.ofItemList(pageOf(items, page, pageSize), params)
        }

        // ---- Search (the car's search button, typed or spoken) ----

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = future {
            val count = search(query).size
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            LibraryResult.ofItemList(pageOf(search(query), page, pageSize), params)
        }
    }

    private fun pageOf(items: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        val from = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
        val to = (from + pageSize).coerceAtMost(items.size)
        return ImmutableList.copyOf(items.subList(from, to))
    }

    /** The four tabs at the top of Android Auto, with their icons. */
    private fun tabs(): List<MediaItem> {
        val list = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        val grid = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        return listOf(
            EpisodeMedia.folder(EpisodeMedia.QUEUE, "Queue", icon = iconUri(R.drawable.ic_tab_queue), childStyle = list),
            EpisodeMedia.folder(EpisodeMedia.INBOX, "New Episodes", icon = iconUri(R.drawable.ic_tab_new), childStyle = list),
            EpisodeMedia.folder(EpisodeMedia.LATEST, "Latest", icon = iconUri(R.drawable.ic_tab_latest), childStyle = list),
            EpisodeMedia.folder(
                EpisodeMedia.PODCASTS,
                "Podcasts",
                MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS,
                icon = iconUri(R.drawable.ic_tab_podcasts),
                childStyle = grid,
            ),
        )
    }

    private fun iconUri(res: Int): Uri = Uri.parse("android.resource://$packageName/$res")

    private fun likePattern(query: String): String =
        "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /** Matching podcasts (as folders) first, then matching episodes, newest first. */
    private suspend fun search(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        val pattern = likePattern(query)
        val podcasts = repo.podcasts.search(pattern).map(EpisodeMedia::podcastFolder)
        val episodes = repo.episodes.search(pattern, 50).map { EpisodeMedia.item(it) }
        return podcasts + episodes
    }

    /**
     * What "play [query]" should start: nothing named, the top of the queue; a podcast's name, its
     * newest unplayed episode; otherwise the newest episode whose title matches.
     */
    private suspend fun voiceSearch(query: String): EpisodeWithPodcast? {
        if (query.isBlank()) return repo.queueHead()
        val pattern = likePattern(query)
        repo.podcasts.search(pattern).firstOrNull()?.let { p ->
            val episodes = repo.episodes.getForPodcast(p.id, 50)
            (episodes.firstOrNull { !it.episode.played } ?: episodes.firstOrNull())?.let { return it }
        }
        return repo.episodes.search(pattern, 1).firstOrNull()
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
        private const val LATEST_LIMIT = 100

        /**
         * Id of the episode loaded in the player, unless it has played to the end. Download cleanup
         * never deletes this one. Null when nothing is loaded or the service isn't running.
         */
        @Volatile
        var protectedEpisodeId: String? = null
            private set
    }
}
