package com.earshot.app.playback

import androidx.core.net.toUri
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import android.widget.Toast
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.earshot.app.EarshotApp
import com.earshot.app.R
import com.earshot.app.data.AdTrim
import com.earshot.app.data.ai.AdSkip
import com.earshot.app.data.ai.AdSpan
import com.earshot.app.data.ai.AiKinds
import com.earshot.app.data.ai.EpisodeAiStore
import com.earshot.app.data.PlaybackPrefs
import com.earshot.app.data.feed.SkippedChapters
import com.earshot.app.data.PlaylistJson
import com.earshot.app.data.Settings
import com.earshot.app.data.VoiceMatch
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    private val listening by lazy { ListeningLog(app) }

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
        // Phase 14b: about 10 minutes buffered ahead when streaming, and a disk cache of streamed audio.
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(StreamCache.dataSourceFactory(this)))
            .setLoadControl(StreamCache.loadControl())
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
        app.appScope.launch(Dispatchers.IO) { runCatching { StreamCache.dropStale(applicationContext) } }
        listening.finishOrphans()
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
        // Phase 12 scaffold: the templated car app (same process) hands this to the car host.
        platformToken = session?.platformToken
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )

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
            // Phase 13: Continue listening (its row in the Queue tab, and its list).
            repo.episodes.observeContinue(CONTINUE_LIMIT).map { list -> list.map { it.episode.id to it.episode.title } }.distinctUntilChanged().drop(1)
                .collect {
                    session?.notifyChildrenChanged(EpisodeMedia.CONTINUE, it.size, null)
                    session?.notifyChildrenChanged(EpisodeMedia.QUEUE, repo.episodes.getQueue().size + 4, null)
                }
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
            // Each podcast's episode list (only the podcasts whose episodes changed), from the newest episodes.
            var last: Map<Long, List<EpisodeSignature>>? = null
            repo.observeLatest()
                .map { list -> list.groupBy({ it.episode.podcastId }, ::signature) }
                .distinctUntilChanged()
                .collect { byPodcast ->
                    val before = last
                    last = byPodcast
                    if (before == null) return@collect
                    val s = session ?: return@collect
                    for (id in byPodcast.keys + before.keys) {
                        if (byPodcast[id] != before[id]) {
                            s.notifyChildrenChanged(EpisodeMedia.PODCAST_PREFIX + id, byPodcast[id]?.size ?: 0, null)
                        }
                    }
                }
        }

        scope.launch {
            // Playlists tab (phase 7): the list of playlists, and each playlist's episodes.
            var last: Map<String, List<String>>? = null
            combine(repo.observePlaylists(), repo.episodes.observePlaylistRows()) { list, rows ->
                val byId = rows.associateBy { it.id }
                list.associate { p ->
                    // What the car shows for each episode, minus the exact play position.
                    p.id to (listOf(p.name) + repo.selectPlaylistIds(PlaylistJson.parse(p.filtersJson), rows).map { id ->
                        byId[id]?.let { "$id|${it.played}|${it.positionMs > 0}|${it.downloadState}|${it.title}" } ?: id
                    })
                }
            }
                .flowOn(Dispatchers.Default)
                .distinctUntilChanged()
                .collect { now ->
                    val before = last
                    last = now
                    if (before == null) return@collect
                    val s = session ?: return@collect
                    if (now.keys.toList() != before.keys.toList() || now.any { (id, v) -> before[id]?.firstOrNull() != v.first() }) {
                        s.notifyChildrenChanged(EpisodeMedia.PLAYLISTS, now.size, null)
                    }
                    for ((id, v) in now) if (before[id] != v) s.notifyChildrenChanged(EpisodeMedia.PLAYLIST_PREFIX + id, v.size - 1, null)
                }
        }
        scope.launch {
            // The "More from <podcast>" row at the top of the Queue tab follows the loaded episode.
            loadedEpisodeId.map { id -> id?.let { repo.episodes.get(it)?.podcastId } }.distinctUntilChanged().drop(1)
                .collect { session?.notifyChildrenChanged(EpisodeMedia.QUEUE, repo.episodes.getQueue().size + 2, null) }
        }

        // Phase 10: chapters to skip and ads to trim follow the loaded episode.
        scope.launch {
            loadedEpisodeId
                .flatMapLatest { id -> if (id == null) flowOf(null) else repo.episodes.observeWithPodcast(id) }
                .collect { ep ->
                    loaded = ep
                    val id = ep?.episode?.id
                    if (id != chaptersFor) {
                        chaptersFor = id
                        chapters = emptyList()
                        chapterJob?.cancel()
                        if (ep != null) chapterJob = scope.launch { setChapters(app.chapters.load(ep.episode)) } else setChapters(emptyList())
                    }
                }
        }
        // Phase 11: chapters generated (here or on another device) replace the empty list.
        scope.launch {
            app.chapters.changes.drop(1).collect {
                val ep = loaded ?: return@collect
                chapterJob?.cancel()
                chapterJob = scope.launch { setChapters(app.chapters.load(ep.episode)) }
            }
        }
        // Phase 11: ads found in the transcript, for automatic skipping.
        scope.launch {
            loadedEpisodeId
                .flatMapLatest<String?, Pair<String?, List<AdSpan>>> { id ->
                    if (id == null) flowOf(null to emptyList())
                    else app.db.episodeAi().observe(id).map { rows ->
                        // Phase 14: Find ads wins; without it, the ad breaks in generated chapters.
                        id to (
                            rows.firstOrNull { it.kind == AiKinds.ADS }?.let { EpisodeAiStore.parseAds(it.dataJson) }
                                ?: rows.firstOrNull { it.kind == AiKinds.CHAPTERS }?.let { r ->
                                    AdSkip.fromChapters(EpisodeAiStore.parseChapters(r.dataJson).map { it.startMs to it.title })
                                }
                                ?: emptyList()
                            )
                    }
                }
                .collect { (id, list) ->
                    if (id != adsFor) {
                        adsFor = id
                        adsSkipped = emptySet()
                    }
                    ads = list
                }
        }
        scope.launch {
            while (isActive) {
                delay(500)
                if (player.isPlaying) checkSkips()
                updateAdButton()
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
        if (old.skipBackMs != p.skipBackMs || old.skipForwardMs != p.skipForwardMs) {
            refreshButtons()
        }
        if (old.speed != p.speed) {
            // The speed list and its row at the top of the Queue tab show the current speed.
            session?.notifyChildrenChanged(EpisodeMedia.SPEED, Settings.SPEED_CHOICES.size, null)
            scope.launch { session?.notifyChildrenChanged(EpisodeMedia.QUEUE, repo.episodes.getQueue().size + 1, null) }
        }
    }

    // ---- Skipped chapters and ad trim (phase 10) ----

    private var loaded: EpisodeWithPodcast? = null
    private var chaptersFor: String? = null
    private var chapters: List<com.earshot.app.data.feed.Chapter> = emptyList()

    /** New chapters for the loaded episode: the car's Queue tab shows (or hides) its Chapters row. */
    private fun setChapters(list: List<com.earshot.app.data.feed.Chapter>) {
        val changed = list.size != chapters.size || list.map { it.startMs } != chapters.map { it.startMs }
        chapters = list
        if (!changed) return
        val s = session ?: return
        s.notifyChildrenChanged(EpisodeMedia.CHAPTERS, list.size, null)
        scope.launch { s.notifyChildrenChanged(EpisodeMedia.QUEUE, repo.episodes.getQueue().size + 3, null) }
    }

    /** The chapter playing now (by title), for the Chapters row. */
    private fun currentChapterIndex(): Int {
        val pos = player.currentPosition
        return chapters.indexOfLast { it.startMs <= pos }
    }
    private var chapterJob: Job? = null
    private var skippedJson: String? = null
    private var skipped: Set<Long> = emptySet()
    private var adsFor: String? = null
    private var ads: List<AdSpan> = emptyList()
    /** Ads (by start) already skipped while this episode is loaded; going back into one plays it. */
    private var adsSkipped: Set<Long> = emptySet()
    /** Episode whose ad trim result was already shown, so the notice appears once per episode. */
    private var adTrimReportedFor: String? = null

    /** Says what ad trim did, once per episode, so it's clear whether it acted. */
    private fun reportAdTrim(id: String, text: String) {
        if (adTrimReportedFor == id) return
        adTrimReportedFor = id
        Log.i(TAG, "Ad trim: $text")
        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
    }

    private fun mmss(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

    /**
     * Runs twice a second while playing. Jumps over unticked chapters (carrying on past any that
     * follow), skips ads stitched onto the start, and finishes the episode where ads stitched onto
     * the end begin. Works the same from the app, the notification and Android Auto.
     */
    private fun checkSkips() {
        val ep = loaded ?: return
        val id = player.currentMediaItem?.mediaId ?: return
        if (ep.episode.id != id || player.playbackState != Player.STATE_READY) return
        val pos = player.currentPosition
        val duration = player.duration.takeIf { it > 0 } ?: return
        val extra = when (ep.podcastAdTrim) {
            AdTrim.START, AdTrim.END -> AdTrim.extraMs(ep.episode.feedDurationMs, duration)
            else -> 0L
        }
        if (ep.podcastAdTrim == AdTrim.START || ep.podcastAdTrim == AdTrim.END) {
            when {
                ep.episode.feedDurationMs <= 0 -> reportAdTrim(id, "Ad trim: the feed doesn't give this episode's length yet. Refresh the podcast and try again.")
                extra == 0L -> reportAdTrim(id, "Ad trim: no inserted ads found (file ${mmss(duration)}, feed says ${mmss(ep.episode.feedDurationMs)})")
                ep.podcastAdTrim == AdTrim.END -> reportAdTrim(id, "Ad trim: will skip the last ${mmss(extra)} (inserted ads)")
            }
        }
        if (extra > 0 && ep.podcastAdTrim == AdTrim.START && pos < extra - 1_000) {
            Log.i(TAG, "Ad trim: skipping the first ${extra / 1000} s")
            player.seekTo(extra)
            adTrimReportedFor = null
            reportAdTrim(id, "Skipped ${mmss(extra)} of inserted ads at the start")
            return
        }
        val endAt = if (extra > 0 && ep.podcastAdTrim == AdTrim.END) duration - extra else duration
        if (endAt < duration && pos >= endAt) {
            Log.i(TAG, "Ad trim: the last ${extra / 1000} s are ads, finishing \"${ep.episode.title}\"")
            finishCurrent(id, playNext = true, atEnd = false)
            return
        }
        if (ep.episode.skippedChapters != skippedJson) {
            skippedJson = ep.episode.skippedChapters
            skipped = SkippedChapters.parse(skippedJson)
        }
        if (ads.isNotEmpty() && adsFor == id && Settings.adSkip(app).value == AdSkip.AUTO) {
            val to = AdSkip.skipTarget(ads, pos, adsSkipped)
            if (to != null) {
                adsSkipped = adsSkipped + ads.filter { it.startMs in pos - 1..to }.map { it.startMs } +
                    listOfNotNull(AdSkip.current(ads, pos)?.startMs)
                if (to >= endAt - 500) {
                    Log.i(TAG, "Found ad runs to the end: finishing \"${ep.episode.title}\"")
                    finishCurrent(id, playNext = true, atEnd = false)
                } else {
                    Log.i(TAG, "Skipping ad: $pos -> $to ms")
                    player.seekTo(to)
                }
                return
            }
        }
        val target = SkippedChapters.skipTarget(chapters, skipped, pos, duration) ?: return
        if (target >= endAt - 500) {
            Log.i(TAG, "Skipped chapters run to the end: finishing \"${ep.episode.title}\"")
            finishCurrent(id, playNext = true, atEnd = false)
        } else {
            Log.i(TAG, "Skipping chapter: $pos -> $target ms")
            player.seekTo(target)
        }
    }

    // ---- Rewind after a pause or interruption (phase 13) ----

    private var pausedAt = 0L
    private var pausedId: String? = null
    private var pausedByInterruption = false

    private fun rewindAfterPause() {
        val at = pausedAt
        pausedAt = 0L
        if (at == 0L || !Settings.resumeRewind(app).value) return
        if (player.currentMediaItem?.mediaId != pausedId) return
        val back = ResumeRewind.amountMs(SystemClock.elapsedRealtime() - at, pausedByInterruption)
        if (back <= 0) return
        val to = (player.currentPosition - back).coerceAtLeast(0)
        Log.i(TAG, "Resume rewind: back ${back / 1000} s after ${(SystemClock.elapsedRealtime() - at) / 1000} s" + if (pausedByInterruption) " (interrupted)" else "")
        player.seekTo(to)
        pausedAt = 0L // the seek above isn't a listener's choice; nothing more to do
    }

    /** Where a freshly loaded episode starts: its saved place, backed up a little after a long break. */
    private fun resumeStart(ep: EpisodeWithPodcast): Long {
        val start = EpisodeMedia.startPosition(ep)
        if (!Settings.resumeRewind(app).value || start <= 0 || ep.episode.lastPlayedAt <= 0) return start
        return ResumeRewind.startFor(start, System.currentTimeMillis() - ep.episode.lastPlayedAt)
    }

    // ---- Playing-screen buttons: skip back / forward, Mark played ----

    /** True for a few seconds after a bookmark from the car or notification (filled icon). */
    private var bookmarkFlash = false
    private var bookmarkFlashJob: Job? = null

    private fun buttons() = PlayerButtons.all(prefs.skipBackMs, prefs.skipForwardMs, bookmarkFlash, skipAd = adNow != null)

    /** Phase 13: the found ad playing now when ads are on Manual (the car shows Skip ad), else null. */
    private var adNow: AdSpan? = null

    private fun updateAdButton() {
        val id = player.currentMediaItem?.mediaId
        val ad = if (id != null && adsFor == id && ads.isNotEmpty() && Settings.adSkip(app).value == AdSkip.BUTTON &&
            player.playbackState == Player.STATE_READY
        ) AdSkip.current(ads, player.currentPosition) else null
        if (ad != adNow) {
            Log.i(TAG, "Skip ad button ${if (ad != null) "on (${ad.startMs}-${ad.endMs} ms)" else "off"} at ${player.currentPosition} ms")
            adNow = ad
            refreshButtons()
        }
    }

    /** Skip ad from the car or notification: past this ad (and any straight after it). */
    private fun skipAdNow() {
        val id = player.currentMediaItem?.mediaId ?: return
        val to = AdSkip.skipTarget(ads, player.currentPosition) ?: return
        val duration = player.duration
        Log.i(TAG, "Skip ad (button): ${player.currentPosition} -> $to ms")
        if (duration > 0 && to >= duration - 500) finishCurrent(id, playNext = true, atEnd = false) else player.seekTo(to)
        updateAdButton()
    }

    /** Bookmarks the playing moment (Bookmark button in Android Auto or the notification). */
    private fun bookmarkNow() {
        val id = player.currentMediaItem?.mediaId ?: return
        val position = player.currentPosition
        scope.launch {
            val b = app.transcripts.addBookmark(id, position) ?: return@launch
            Log.i(TAG, "Bookmarked ${b.episodeTitle} at ${b.positionMs} ms")
            bookmarkFlash = true
            refreshButtons()
            bookmarkFlashJob?.cancel()
            bookmarkFlashJob = scope.launch {
                delay(3_000)
                bookmarkFlash = false
                refreshButtons()
            }
        }
    }

    private fun refreshButtons() {
        session?.setMediaButtonPreferences(buttons())
    }

    /** Sets the speed, saved where the app would save it. */
    private fun setSpeed(speed: Float) {
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
        listening.tick(id, player.currentPosition, elapsed, speed, inCar())
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
        // Phase 15: the episode's share rides along with the next position save (one database write
        // every 5 s instead of three, since every write makes every open list re-query).
        if (unsavedTimeSavedId != null && unsavedTimeSavedId != id) writeTimeSaved()
        unsavedTimeSavedId = id
        unsavedTimeSavedMs += speedMs + smartMs
        if (!player.isPlaying) writeTimeSaved()
    }

    private var unsavedTimeSavedId: String? = null
    private var unsavedTimeSavedMs = 0L

    /** Writes the episode's time saved not yet written with a position save. */
    private fun writeTimeSaved() {
        val id = unsavedTimeSavedId ?: return
        val ms = unsavedTimeSavedMs
        unsavedTimeSavedId = null
        unsavedTimeSavedMs = 0L
        if (ms == 0L) return
        val dao = repo.episodes
        app.appScope.launch { dao.addTimeSaved(id, ms) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** True while Android Auto is connected (for the listening history's "car"). */
    private fun inCar(): Boolean =
        session?.connectedControllers?.any { it.packageName == ANDROID_AUTO_PACKAGE } == true

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                listening.finish()
                scope.launch { savePosition() }
                // Phase 13: remember a real pause or interruption (not buffering or the end) for the rewind.
                if (player.playbackState == Player.STATE_READY) {
                    pausedAt = SystemClock.elapsedRealtime()
                    pausedId = player.currentMediaItem?.mediaId
                    pausedByInterruption = player.playWhenReady && player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
                }
            } else {
                rewindAfterPause()
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // A seek or skip while paused means the listener chose where to carry on: don't rewind from there.
            if (!player.isPlaying) pausedAt = 0L
        }

        override fun onEvents(player: Player, events: Player.Events) {
            updateProtected()
            loadedEpisodeId.value = player.currentMediaItem?.mediaId
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            pausedAt = 0L
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
        val position = player.currentPosition
        val duration = player.duration
        if (unsavedTimeSavedId == id) {
            val ms = unsavedTimeSavedMs
            unsavedTimeSavedId = null
            unsavedTimeSavedMs = 0L
            dao.savePositionAndTimeSaved(id, position, System.currentTimeMillis(), ms)
        } else {
            dao.savePosition(id, position, System.currentTimeMillis())
        }
        // Only writes when the duration differs (an update that changes no row doesn't wake the lists).
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
            // A speed picked from the speed list: set it and carry on with what's loaded.
            if (requested.mediaId.startsWith(EpisodeMedia.SPEED_PREFIX)) {
                return speedPicked(requested.mediaId.removePrefix(EpisodeMedia.SPEED_PREFIX).toFloatOrNull())
            }
            // A chapter picked from the chapters list: jump there in what's loaded.
            if (requested.mediaId.startsWith(EpisodeMedia.CHAPTER_PREFIX)) {
                return chapterPicked(requested.mediaId.removePrefix(EpisodeMedia.CHAPTER_PREFIX).toLongOrNull())
            }
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
                val start = if (startPositionMs != C.TIME_UNSET && startPositionMs > 0) startPositionMs else resumeStart(ep)
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
            // otherwise) and add the Mark played and Bookmark buttons. Android Auto shows the
            // buttons the media notification's controller has, and taps are checked against its
            // own controller, so every controller gets them. They only mark played or bookmark.
            val defaults = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(
                    defaults.availableSessionCommands.buildUpon()
                        .add(PlayerButtons.markPlayedCommand)
                        .add(PlayerButtons.bookmarkCommand)
                        .add(PlayerButtons.skipAdCommand)
                        .add(PlayerButtons.aiSweepCommand)
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
                PlayerButtons.MARK_PLAYED -> player.currentMediaItem?.mediaId?.let {
                    finishCurrent(it, playNext = player.playWhenReady, atEnd = false)
                }
                PlayerButtons.BOOKMARK -> bookmarkNow()
                PlayerButtons.SKIP_AD -> skipAdNow()
                PlayerButtons.AI_SWEEP -> player.currentMediaItem?.mediaId?.let {
                    Log.i(TAG, "AI sweep requested for $it")
                    com.earshot.app.work.AiSweepWorker.enqueue(app, it)
                }
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
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
            MediaItemsWithStartPosition(playlist(head), 0, resumeStart(head))
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
                mediaId == EpisodeMedia.SPEED -> EpisodeMedia.speedFolder(prefs.speed, iconUri(PlayerButtons.speedIcon(prefs.speed)))
                mediaId == EpisodeMedia.CONTINUE -> continueRow() ?: EpisodeMedia.continueFolder(0, null, iconUri(R.drawable.ic_tab_latest))
                mediaId.startsWith(EpisodeMedia.SPEED_PREFIX) ->
                    mediaId.removePrefix(EpisodeMedia.SPEED_PREFIX).toFloatOrNull()?.let { speedChoiceItem(it) }
                mediaId.startsWith(EpisodeMedia.PODCAST_PREFIX) ->
                    mediaId.removePrefix(EpisodeMedia.PODCAST_PREFIX).toLongOrNull()
                        ?.let { repo.podcasts.get(it) }?.let(EpisodeMedia::podcastFolder)
                mediaId.startsWith(EpisodeMedia.MORE_FROM_PREFIX) ->
                    mediaId.removePrefix(EpisodeMedia.MORE_FROM_PREFIX).toLongOrNull()
                        ?.let { repo.podcasts.get(it) }?.let(EpisodeMedia::moreFromFolder)
                mediaId.startsWith(EpisodeMedia.PLAYLIST_PREFIX) ->
                    repo.playlists.get(mediaId.removePrefix(EpisodeMedia.PLAYLIST_PREFIX))
                        ?.takeIf { !it.deleted }?.let { EpisodeMedia.playlistFolder(it, iconUri(R.drawable.ic_tab_playlists)) }
                else -> tabs().firstOrNull { it.mediaId == mediaId }
            }
            val item = folder ?: repo.episodes.getWithPodcast(mediaId)?.let(EpisodeMedia::item)
            if (item != null) LibraryResult.ofItem(item, null)
            else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
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
                parentId == EpisodeMedia.QUEUE ->
                    listOf(EpisodeMedia.speedFolder(prefs.speed, iconUri(PlayerButtons.speedIcon(prefs.speed)))) +
                        listOfNotNull(chaptersRow()) +
                        listOfNotNull(moreFromLoaded()) +
                        listOfNotNull(continueRow()) +
                        dao.getQueue().map { EpisodeMedia.item(it) }
                parentId == EpisodeMedia.CONTINUE -> dao.getContinue(CONTINUE_LIMIT).map { EpisodeMedia.item(it) }
                parentId == EpisodeMedia.SPEED -> Settings.SPEED_CHOICES.map { speedChoiceItem(it) }
                parentId == EpisodeMedia.CHAPTERS -> {
                    val current = currentChapterIndex()
                    val icon = iconUri(R.drawable.ic_car_chapters)
                    chapters.mapIndexed { i, c ->
                        EpisodeMedia.chapterChoice(c.startMs, c.title.ifBlank { "Chapter ${i + 1}" }, i == current, icon)
                    }
                }
                parentId == EpisodeMedia.INBOX -> EpisodeMedia.itemsByDate(dao.getInbox())
                parentId == EpisodeMedia.PODCASTS -> repo.podcasts.getAll().map(EpisodeMedia::podcastFolder)
                parentId.startsWith(EpisodeMedia.PODCAST_PREFIX) -> {
                    val id = parentId.removePrefix(EpisodeMedia.PODCAST_PREFIX).toLongOrNull()
                    if (id == null) emptyList() else dao.getForPodcast(id, 100).map { EpisodeMedia.item(it) }
                }
                parentId.startsWith(EpisodeMedia.MORE_FROM_PREFIX) -> {
                    val id = parentId.removePrefix(EpisodeMedia.MORE_FROM_PREFIX).toLongOrNull()
                    if (id == null) emptyList() else dao.getForPodcast(id, 100).map { EpisodeMedia.item(it) }
                }
                parentId == EpisodeMedia.PLAYLISTS ->
                    repo.playlists.getAll().map { EpisodeMedia.playlistFolder(it, iconUri(R.drawable.ic_tab_playlists)) }
                parentId.startsWith(EpisodeMedia.PLAYLIST_PREFIX) -> {
                    val p = repo.playlists.get(parentId.removePrefix(EpisodeMedia.PLAYLIST_PREFIX))
                    if (p == null || p.deleted) emptyList()
                    else repo.playlistEpisodes(PlaylistJson.parse(p.filtersJson)).map { EpisodeMedia.item(it) }
                }
                else -> return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
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

    /** "More from <podcast>": the loaded episode's podcast, at the top of the car's Queue tab. */
    private suspend fun moreFromLoaded(): MediaItem? {
        val id = player.currentMediaItem?.mediaId ?: return null
        val podcastId = repo.episodes.get(id)?.podcastId ?: return null
        return repo.podcasts.get(podcastId)?.let(EpisodeMedia::moreFromFolder)
    }

    /** "Continue listening (n)" in the Queue tab (phase 13), when there are started episodes not queued. */
    private suspend fun continueRow(): MediaItem? {
        val list = repo.episodes.getContinue(CONTINUE_LIMIT)
        if (list.isEmpty()) return null
        return EpisodeMedia.continueFolder(list.size, list.first().episode.title, iconUri(R.drawable.ic_tab_latest))
    }

    /** "Chapters (n)" at the top of the Queue tab, when the loaded episode has chapters. */
    private fun chaptersRow(): MediaItem? {
        if (chapters.isEmpty() || player.currentMediaItem == null) return null
        val current = chapters.getOrNull(currentChapterIndex())?.title?.takeIf { it.isNotBlank() }
        return EpisodeMedia.chaptersFolder(chapters.size, current, iconUri(R.drawable.ic_car_chapters))
    }

    /**
     * Like a speed: Android Auto plays whatever is tapped, so a chapter arrives as a request to play
     * it. Hand back what's loaded, at the chapter's start, so it carries on from there.
     */
    private fun chapterPicked(startMs: Long?): ListenableFuture<MediaItemsWithStartPosition> {
        val count = player.mediaItemCount
        if (startMs == null || count == 0 || player.playbackState == Player.STATE_ENDED) {
            return Futures.immediateFailedFuture(IllegalStateException("No episode loaded for that chapter"))
        }
        val items = (0 until count).map { player.getMediaItemAt(it) }
        skipPlayer.keepLoadedOnce = true // phase 14b: seek instead of reloading the episode
        return Futures.immediateFuture(MediaItemsWithStartPosition(items, player.currentMediaItemIndex, startMs))
    }

    private fun speedChoiceItem(speed: Float): MediaItem =
        EpisodeMedia.speedChoice(speed, abs(speed - prefs.speed) < 0.01f, iconUri(PlayerButtons.speedIcon(speed)))

    /**
     * Android Auto plays whatever is tapped in a list, so a speed from the speed list arrives as a
     * request to play it. Set the speed, then hand back what was already loaded, at the same spot,
     * so it carries on (Android Auto then shows the playing screen with the new speed).
     */
    private fun speedPicked(speed: Float?): ListenableFuture<MediaItemsWithStartPosition> {
        if (speed != null) setSpeed(speed)
        val count = player.mediaItemCount
        if (count > 0 && player.playbackState != Player.STATE_ENDED) {
            val items = (0 until count).map { player.getMediaItemAt(it) }
            skipPlayer.keepLoadedOnce = true // phase 14b: carry on without reloading (no pause)
            return Futures.immediateFuture(MediaItemsWithStartPosition(items, player.currentMediaItemIndex, player.currentPosition))
        }
        return future {
            val head = repo.queueHead() ?: throw IllegalStateException("Speed set; nothing to play")
            player.setPauseAtEndOfMediaItems(true)
            MediaItemsWithStartPosition(playlist(head), 0, EpisodeMedia.startPosition(head))
        }
    }

    private fun pageOf(items: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        val from = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
        val to = (from + pageSize).coerceAtMost(items.size)
        return ImmutableList.copyOf(items.subList(from, to))
    }

    /**
     * The four tabs at the top of Android Auto (the most it shows), with their icons. Phase 7:
     * Playlists took the Latest tab's place in the car (Latest stays on the phone and web).
     */
    private fun tabs(): List<MediaItem> {
        val list = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        val grid = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        return listOf(
            EpisodeMedia.folder(EpisodeMedia.QUEUE, "Queue", icon = iconUri(R.drawable.ic_tab_queue), childStyle = list),
            EpisodeMedia.folder(EpisodeMedia.INBOX, "New Episodes", icon = iconUri(R.drawable.ic_tab_new), childStyle = list),
            EpisodeMedia.folder(EpisodeMedia.PLAYLISTS, "Playlists", icon = iconUri(R.drawable.ic_tab_playlists), childStyle = list),
            EpisodeMedia.folder(
                EpisodeMedia.PODCASTS,
                "Podcasts",
                MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS,
                icon = iconUri(R.drawable.ic_tab_podcasts),
                childStyle = grid,
            ),
        )
    }

    private fun iconUri(res: Int): Uri = "android.resource://$packageName/$res".toUri()

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
     * What "play [query]" should start (phase 10, see data/VoiceMatch.kt):
     *  - nothing, "podcasts", "Earshot": carry on with the loaded episode, else the top of the queue;
     *  - "the latest <show>": that show's newest unplayed episode;
     *  - a show's name (spelled loosely): its newest unplayed episode;
     *  - otherwise the newest episode whose title matches (exactly, then loosely).
     */
    private suspend fun voiceSearch(query: String): EpisodeWithPodcast? {
        val intent = VoiceMatch.parse(query)
        Log.i(TAG, "Voice: \"$query\" -> $intent")
        suspend fun newestOf(show: String): EpisodeWithPodcast? {
            val podcast = VoiceMatch.best(show, repo.podcasts.getAll()) { it.title } ?: return null
            val episodes = repo.episodes.getForPodcast(podcast.id, 50)
            return episodes.firstOrNull { !it.episode.played } ?: episodes.firstOrNull()
        }
        return when (intent) {
            VoiceMatch.Intent.Resume -> {
                val loaded = player.currentMediaItem?.mediaId?.takeIf { player.playbackState != Player.STATE_ENDED }
                loaded?.let { repo.episodes.getWithPodcast(it) } ?: repo.queueHead()
            }
            is VoiceMatch.Intent.Latest -> newestOf(intent.show) ?: anyMatch(query)
            is VoiceMatch.Intent.Any -> newestOf(intent.text) ?: anyMatch(intent.text)
        }
    }

    /** An episode by title: a plain "contains" match first, then a loose one over recent episodes. */
    private suspend fun anyMatch(text: String): EpisodeWithPodcast? {
        repo.episodes.search(likePattern(text), 1).firstOrNull()?.let { return it }
        val recent = repo.episodes.getQueue() + repo.episodes.getLatest(300)
        return VoiceMatch.best(text, recent, min = 0.7) { it.episode.title }
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
        writeTimeSaved()
        listening.finish()
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
        platformToken = null
        super.onDestroy()
    }

    companion object {
        private const val CONTINUE_LIMIT = 20
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

        /**
         * Id of the episode loaded in the player, unless it has played to the end. Download cleanup
         * never deletes this one. Null when nothing is loaded or the service isn't running.
         */
        @Volatile
        var protectedEpisodeId: String? = null
            private set

        /** The session's platform token while the service runs (phase 12 car app scaffold). */
        @Volatile
        var platformToken: android.media.session.MediaSession.Token? = null
            private set
    }
}
