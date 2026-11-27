package com.earshot.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import com.earshot.app.EarshotApp
import com.earshot.app.data.ImportStatus
import com.earshot.app.data.PlaybackDefaults
import com.earshot.app.data.PlaybackPrefs
import com.earshot.app.data.Settings
import com.earshot.app.data.TimeSaved
import com.earshot.app.data.TidyDefaults
import com.earshot.app.data.PlaylistFilters
import com.earshot.app.data.db.Playlist
import com.earshot.app.data.db.Podcast
import com.earshot.app.data.db.EpisodeWithPodcast
import com.earshot.app.data.db.Bookmark
import com.earshot.app.data.transcript.LoadedTranscript
import com.earshot.app.data.transcript.TranscribeState
import com.earshot.app.data.transcript.TranscriptHit
import com.earshot.app.data.feed.Chapter
import com.earshot.app.data.ai.AdSkip
import com.earshot.app.data.ai.AdSpan
import com.earshot.app.data.ai.AiKinds
import com.earshot.app.data.ai.EpisodeAiState
import com.earshot.app.data.search.Directory
import com.earshot.app.data.search.SearchSource
import com.earshot.app.data.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Library : Screen
    data class PodcastDetail(val podcastId: Long) : Screen
    data object Add : Screen
    data object Settings : Screen
    /** An episode's transcript; [focusMs] scrolls to and highlights a moment (from search). */
    data class TranscriptView(val episodeId: String, val focusMs: Long? = null) : Screen
    data object Bookmarks : Screen
    data object TranscriptSearch : Screen
    /** A smart playlist's episodes (phase 7). */
    data class PlaylistDetail(val playlistId: String) : Screen
    /** "Set up for your car" (phase 9): getting Earshot into Android Auto. */
    data object CarSetup : Screen
    /** Listening stats (Stats phase). */
    data object Stats : Screen
    /** A week, month or year in review, for the calendar period starting at [from]. */
    data class StatsReview(val kind: com.earshot.app.data.stats.ReviewKind, val from: Long) : Screen
}

/** Library tabs. Phase 7: Playlists added; the app opens on Podcasts. */
object Tabs {
    const val QUEUE = 0
    const val NEW = 1
    const val LATEST = 2
    const val PLAYLISTS = 3
    const val PODCASTS = 4
    val NAMES = listOf("Queue", "New Episodes", "Latest", "Playlists", "Podcasts")
}

/** A Markdown file to hand to the share sheet. */
data class ShareRequest(val fileName: String, val markdown: String)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EarshotApp
    val repo = app.repository
    val player = app.player

    // ---- Navigation (simple back stack; no nav library needed yet) ----
    val backStack = mutableStateListOf<Screen>(Screen.Library)
    var showPlayer by mutableStateOf(false)

    /** Library tab (see [Tabs]); opens on Podcasts. Kept here so Glow's bottom bar can switch it. */
    var libraryTab by mutableIntStateOf(Tabs.PODCASTS)

    /** Glow's bottom bar: go back to the library on [tab], closing any pages on top (previews close as usual). */
    fun showTab(tab: Int) {
        libraryTab = tab
        while (backStack.size > 1) back()
    }

    fun navigate(screen: Screen) {
        backStack.add(screen)
    }

    /**
     * Opens a podcast's page from anywhere its name or artwork shows (phase 7). Closes the player
     * sheet. A podcast kept only for queued episodes opens as a preview, so all its episodes show.
     */
    fun openPodcastPage(podcastId: Long) {
        showPlayer = false
        if (backStack.lastOrNull() == Screen.PodcastDetail(podcastId)) return
        viewModelScope.launch {
            val p = repo.podcasts.get(podcastId) ?: return@launch
            if (p.subscribed) navigate(Screen.PodcastDetail(podcastId)) else openPodcast(p.feedUrl)
        }
    }

    /** The same, by feed address (bookmarks and transcripts keep only the feed). */
    fun openPodcastByFeed(feedUrl: String) {
        if (feedUrl.isBlank()) return
        showPlayer = false
        viewModelScope.launch {
            val p = repo.findPodcast(feedUrl)
            if (p != null && p.subscribed) openPodcastPage(p.id) else openPodcast(feedUrl)
        }
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        val left = backStack.removeAt(backStack.lastIndex)
        // Leaving a previewed podcast without subscribing: keep only what was queued or is playing.
        if (left is Screen.PodcastDetail && left !in backStack) {
            viewModelScope.launch { repo.closePreview(left.podcastId, keepId = nowPlaying.value.episodeId) }
        }
        return true
    }

    // ---- Data ----
    val library = repo.observeLibrary().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latest = repo.observeLatest().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val inbox = repo.observeInbox().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val queue = repo.observeQueue().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val nowPlaying = player.state
    val sleepTimer = player.sleepTimer

    val currentEpisode: StateFlow<EpisodeWithPodcast?> = player.state
        .map { it.episodeId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeEpisode(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The playing episode's chapters: from the feed, the audio file or the transcript (phase 10). */
    val chapters: StateFlow<List<Chapter>> = currentEpisode
        .map { it?.episode }
        .distinctUntilChanged { a, b -> a?.id == b?.id && a?.chaptersUrl == b?.chaptersUrl }
        // Phase 11: loaded again when chapters are generated for it.
        .combine(app.chapters.changes) { ep, _ -> ep }
        .mapLatest { ep -> if (ep == null) emptyList() else app.chapters.load(ep) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Phase 11: AI from transcripts ----

    /** Chapters, summary and ads made for the playing episode so far. */
    val ai: StateFlow<EpisodeAiState> = currentEpisode
        .map { it?.episode?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(EpisodeAiState()) else app.ai.observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodeAiState())

    /** "episodeId|kind" for everything being made right now. */
    val aiRunning: StateFlow<Set<String>> = app.ai.running

    /** What found ads do: "auto", "button" or "off" (synced). */
    val adSkip: StateFlow<String> = Settings.adSkip(app)

    fun updateAdSkip(mode: String) {
        Settings.setAdSkip(app, mode)
        app.sync.request("settings")
    }

    /** The found ad playing now, when ads are set to show a Skip ad button. */
    val currentAd: StateFlow<AdSpan?> = combine(ai, nowPlaying, adSkip) { a, np, mode ->
        if (mode != AdSkip.BUTTON) null else AdSkip.current(a.ads.orEmpty(), np.positionMs)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun skipAd() {
        val ad = currentAd.value ?: return
        player.skipBy(ad.endMs - nowPlaying.value.positionMs)
    }

    /** Makes chapters, a summary or the ad list for an episode from its transcript (Gemini, on the server). */
    fun generateAi(item: EpisodeWithPodcast, kind: String) {
        viewModelScope.launch {
            try {
                app.ai.generate(item.episode, item.podcastTitle, kind)
                when (kind) {
                    AiKinds.ADS -> {
                        val n = app.ai.ads(item.episode.id).size
                        say(if (n == 0) "No ads found in this episode" else "Found $n ad${if (n == 1) "" else "s"}")
                    }
                    AiKinds.CHAPTERS -> say("Chapters made from the transcript")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(e.message ?: "That didn't work")
            }
        }
    }

    /** Phase 10: trim ads stitched onto this podcast's episodes ("start", "end" or null = off). Synced. */
    fun setAdTrim(podcastId: Long, mode: String?) {
        viewModelScope.launch { repo.podcasts.setAdTrim(podcastId, mode) }
    }

    /** Ticks or unticks a chapter of the playing episode; unticked chapters are skipped (synced). */
    fun setChapterSkipped(startMs: Long, skip: Boolean) {
        val ep = currentEpisode.value?.episode ?: return
        viewModelScope.launch {
            val now = repo.episodes.get(ep.id) ?: return@launch
            val set = com.earshot.app.data.feed.SkippedChapters.parse(now.skippedChapters)
                .filterNot { kotlin.math.abs(it - startMs) <= 1_000 }.toMutableSet()
            if (skip) set += startMs
            repo.episodes.setSkippedChapters(ep.id, com.earshot.app.data.feed.SkippedChapters.toJson(set))
        }
    }

    fun podcast(id: Long) = repo.observePodcast(id)
    fun episodesFor(podcastId: Long) = repo.observeEpisodes(podcastId)

    // A channel (not a SharedFlow) so messages sent while the UI is away, e.g. behind the file
    // picker, are queued and shown on return instead of being dropped.
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()
    private fun say(msg: String) {
        _messages.trySend(msg)
    }

    // ---- Phase 9: updates and backup ----

    /** A newer release on GitHub, shown as a banner until installed or dismissed. */
    var update by mutableStateOf<com.earshot.app.data.UpdateInfo?>(null)
        private set
    var updateChecking by mutableStateOf(false)
        private set

    /** Checks GitHub for a newer release. [manual] = the Settings button (always says what it found). */
    fun checkForUpdate(manual: Boolean) {
        if (updateChecking) return
        updateChecking = true
        viewModelScope.launch {
            try {
                val u = com.earshot.app.data.Updates.check(app)
                if (u == null) {
                    if (manual) say("Earshot is up to date (${com.earshot.app.BuildConfig.VERSION_NAME})")
                } else if (manual || com.earshot.app.data.Updates.dismissed(app) != u.version) {
                    update = u
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Earshot", "Update check failed", e)
                if (manual) {
                    say(if (e.message?.contains("HTTP 404") == true) "No release has been published yet" else "Couldn't check for updates: ${e.message}")
                }
            } finally {
                updateChecking = false
            }
        }
    }

    fun dismissUpdate() {
        update?.let { com.earshot.app.data.Updates.dismiss(app, it.version) }
        update = null
    }

    /** A backup chosen for restoring, checked and waiting for confirmation. */
    var restoreCandidate by mutableStateOf<com.earshot.app.data.Backup.Info?>(null)
        private set
    var backupBusy by mutableStateOf(false)
        private set

    fun exportBackup(uri: Uri) {
        backupBusy = true
        viewModelScope.launch {
            try {
                val info = com.earshot.app.data.Backup.export(app, uri)
                say("Backed up ${info.podcasts} podcasts and ${info.episodes} episodes")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Backup failed: ${e.message}")
            } finally {
                backupBusy = false
            }
        }
    }

    fun inspectBackup(uri: Uri) {
        backupBusy = true
        viewModelScope.launch {
            try {
                restoreCandidate = com.earshot.app.data.Backup.inspect(app, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(e.message ?: "Couldn't read that backup")
            } finally {
                backupBusy = false
            }
        }
    }

    fun cancelRestore() {
        restoreCandidate = null
    }

    /** Replaces everything on this phone with the chosen backup and restarts the app. */
    fun confirmRestore() {
        restoreCandidate = null
        try {
            com.earshot.app.data.Backup.restoreAndRestart(app)
        } catch (e: Exception) {
            say("Restore failed: ${e.message}")
        }
    }

    init {
        player.connect()
        if (com.earshot.app.data.Updates.due(app)) checkForUpdate(manual = false)
        // Previews left open when the app was last closed. Waits a moment so the player has loaded
        // its episode (which is kept), and skips any podcast page already open again.
        viewModelScope.launch {
            delay(5_000)
            val open = backStack.filterIsInstance<Screen.PodcastDetail>().map { it.podcastId }.toSet()
            repo.closeAllPreviews(keepId = nowPlaying.value.episodeId, except = open)
        }
    }

    // ---- Refresh ----
    var refreshing by mutableStateOf(false)
        private set

    fun refreshAll() {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            val n = repo.refreshAll()
            refreshing = false
            say(if (n > 0) "$n new episode${if (n == 1) "" else "s"}" else "No new episodes")
        }
    }

    fun refresh(podcastId: Long) {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            try {
                val n = repo.refresh(podcastId)
                say(if (n > 0) "$n new episode${if (n == 1) "" else "s"}" else "Up to date")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Refresh failed: ${e.message}")
            } finally {
                refreshing = false
            }
        }
    }

    // ---- Add podcasts ----
    var searchResults by mutableStateOf<List<SearchResult>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var subscribingUrl by mutableStateOf<String?>(null)
        private set
    /** Which directory gave the current results (shown under the search box). */
    var searchSource by mutableStateOf<SearchSource?>(null)
        private set
    /** Feed being loaded to open its page from search results. */
    var openingUrl by mutableStateOf<String?>(null)
        private set
    private var searchJob: Job? = null

    fun search(term: String) {
        if (term.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searching = true
            try {
                val outcome = Directory.search(term)
                searchResults = outcome.results
                searchSource = outcome.source
                if (outcome.fellBack) say("Podcast Index didn't answer, so these results are from Apple")
                if (searchResults.isEmpty()) say("No podcasts found")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Search failed: ${e.message}")
            } finally {
                searching = false
            }
        }
    }

    fun subscribe(feedUrl: String, openAfter: Boolean) {
        if (subscribingUrl != null) return
        viewModelScope.launch {
            subscribingUrl = feedUrl
            try {
                val podcast = repo.subscribe(feedUrl)
                say("Subscribed to ${podcast.title}")
                if (openAfter) navigate(Screen.PodcastDetail(podcast.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Couldn't subscribe: ${e.message}")
            } finally {
                subscribingUrl = null
            }
        }
    }

    /**
     * Opens a search result's page without subscribing (a preview), or its normal page if it's
     * already in the library.
     */
    fun openPodcast(feedUrl: String) {
        if (openingUrl != null) return
        viewModelScope.launch {
            openingUrl = feedUrl
            try {
                val podcast = repo.preview(feedUrl)
                navigate(Screen.PodcastDetail(podcast.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Couldn't open that podcast: ${e.message}")
            } finally {
                openingUrl = null
            }
        }
    }

    // ---- OPML import (runs app-wide, so it survives leaving this screen) ----
    val importStatus: StateFlow<ImportStatus> = app.importer.status

    fun importOpml(uri: Uri) {
        if (!app.importer.start(uri)) say("An import is already running")
    }

    fun dismissImport() = app.importer.dismiss()

    fun unsubscribe(podcastId: Long) {
        viewModelScope.launch {
            repo.unsubscribe(podcastId, keepId = nowPlaying.value.episodeId)
            if (backStack.lastOrNull() == Screen.PodcastDetail(podcastId)) back()
            say("Unsubscribed")
        }
    }

    // ---- Episodes ----
    fun play(item: EpisodeWithPodcast) = player.play(item)

    /** The Play button on a "New episode" notification (phase 10): sync if needed, then play and show the player. */
    fun playFromNotification(episodeId: String) {
        viewModelScope.launch {
            var item = repo.episodes.getWithPodcast(episodeId)
            if (item == null) {
                app.sync.sync("play from notification")
                item = repo.episodes.getWithPodcast(episodeId)
            }
            if (item == null) {
                say("That episode isn't on this phone yet. Try again in a moment.")
                return@launch
            }
            player.play(item)
            showPlayer = true
        }
    }

    // ---- New-episode notifications (phase 10) ----
    var newEpisodeNotify by mutableStateOf(Settings.newEpisodeNotify(app))
        private set
    val pushConfigured get() = com.earshot.app.push.Push.configured

    fun updateNewEpisodeNotify(mode: String) {
        newEpisodeNotify = mode
        Settings.setNewEpisodeNotify(app, mode)
        com.earshot.app.push.Push.register(app)
    }

    fun download(item: EpisodeWithPodcast) {
        viewModelScope.launch { repo.download(item.episode.id) }
    }

    fun deleteDownload(item: EpisodeWithPodcast) {
        viewModelScope.launch { repo.deleteDownload(item.episode.id) }
    }

    fun setPlayed(item: EpisodeWithPodcast, played: Boolean) {
        viewModelScope.launch {
            repo.setPlayed(item.episode.id, played)
            if (played) app.cleanupDownloads("marked played")
        }
    }

    /** "Mark older/newer played" (phase 7b): one batch, then one sync and one download cleanup. */
    fun markPlayed(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val n = repo.markPlayed(ids)
            say("Marked $n episode${if (n == 1) "" else "s"} played")
            if (n > 0) app.cleanupDownloads("marked played")
        }
    }

    // ---- Inbox and queue ----
    fun queueNext(item: EpisodeWithPodcast) {
        viewModelScope.launch { repo.queueNext(item.episode.id) }
    }

    fun queueLast(item: EpisodeWithPodcast) {
        viewModelScope.launch { repo.queueLast(item.episode.id) }
    }

    fun removeFromQueue(item: EpisodeWithPodcast) {
        viewModelScope.launch { repo.removeFromQueue(item.episode.id) }
    }

    fun reorderQueue(ids: List<String>) {
        viewModelScope.launch { repo.reorderQueue(ids) }
    }

    fun archive(item: EpisodeWithPodcast) {
        viewModelScope.launch { repo.archive(item.episode.id) }
    }

    fun archiveInbox() {
        viewModelScope.launch {
            val n = repo.archiveInbox()
            say("Archived $n episode${if (n == 1) "" else "s"}")
        }
    }

    // ---- Settings ----
    /** "glow" or "classic" (Settings > Appearance). This phone only. */
    val appTheme: StateFlow<String> = Settings.appTheme(app)

    fun setAppTheme(value: String) = Settings.setAppTheme(app, value)

    var autoDownloadQueued by mutableStateOf(Settings.autoDownloadQueued(app))
        private set

    fun updateAutoDownloadQueued(enabled: Boolean) {
        Settings.setAutoDownloadQueued(app, enabled)
        autoDownloadQueued = enabled
    }

    var deleteWhenFinished by mutableStateOf(Settings.deleteWhenFinished(app))
        private set
    var expireDays by mutableIntStateOf(Settings.expireDays(app))
        private set

    fun updateDeleteWhenFinished(enabled: Boolean) {
        Settings.setDeleteWhenFinished(app, enabled)
        deleteWhenFinished = enabled
        if (enabled) app.cleanupDownloads("setting changed")
    }

    fun updateExpireDays(days: Int) {
        Settings.setExpireDays(app, days)
        expireDays = days
        app.cleanupDownloads("setting changed")
    }

    // ---- Playback settings (speed, Smart Speed, Voice Boost, skip lengths) ----
    val playbackDefaults: StateFlow<PlaybackDefaults> = Settings.playbackDefaults(app)
    val timeSaved: StateFlow<TimeSaved> = Settings.timeSaved(app)

    /** Podcast of the episode loaded in the player. */
    val currentPodcast: StateFlow<Podcast?> = currentEpisode
        .map { it?.episode?.podcastId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.observePodcast(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Settings that apply to the loaded episode right now. */
    val currentPrefs: StateFlow<PlaybackPrefs> = combine(currentPodcast, playbackDefaults) { p, d -> PlaybackPrefs.resolve(d, p) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackPrefs.resolve(playbackDefaults.value, null))

    fun updatePlaybackDefaults(change: (PlaybackDefaults) -> PlaybackDefaults) = Settings.updatePlaybackDefaults(app, change)

    fun updatePodcastPlayback(podcastId: Long, change: (Podcast) -> Podcast) {
        viewModelScope.launch { repo.updatePodcastPlayback(podcastId, change) }
    }

    fun Podcast.hasOwnAudio() = speed != null || smartSpeed != null || voiceBoost != null
    fun Podcast.hasOwnSkip() = skipBackMs != null || skipForwardMs != null

    /**
     * A speed / Smart Speed / Voice Boost change from the player. Goes to the loaded podcast if it
     * has its own audio settings, otherwise to the defaults.
     */
    fun changeAudio(change: (PlaybackPrefs) -> PlaybackPrefs) {
        val n = change(currentPrefs.value)
        val p = currentPodcast.value
        if (p != null && p.hasOwnAudio()) {
            updatePodcastPlayback(p.id) { it.copy(speed = n.speed, smartSpeed = n.smartSpeed, voiceBoost = n.voiceBoost) }
        } else {
            updatePlaybackDefaults { it.copy(speed = n.speed, smartSpeed = n.smartSpeed, voiceBoost = n.voiceBoost) }
        }
    }

    /** "Just for this podcast" for audio: on keeps the current values as the podcast's own. */
    fun setAudioJustForPodcast(on: Boolean) {
        val p = currentPodcast.value ?: return
        val cur = currentPrefs.value
        updatePodcastPlayback(p.id) {
            if (on) it.copy(speed = cur.speed, smartSpeed = cur.smartSpeed, voiceBoost = cur.voiceBoost)
            else it.copy(speed = null, smartSpeed = null, voiceBoost = null)
        }
    }

    /** A skip length picked by long-pressing a skip button; same rule as [changeAudio]. */
    fun changeSkip(forward: Boolean, ms: Long) {
        val cur = currentPrefs.value
        val back = if (forward) cur.skipBackMs else ms
        val fwd = if (forward) ms else cur.skipForwardMs
        val p = currentPodcast.value
        if (p != null && p.hasOwnSkip()) {
            updatePodcastPlayback(p.id) { it.copy(skipBackMs = back, skipForwardMs = fwd) }
        } else {
            updatePlaybackDefaults { it.copy(skipBackMs = back, skipForwardMs = fwd) }
        }
    }

    fun setSkipJustForPodcast(on: Boolean) {
        val p = currentPodcast.value ?: return
        val cur = currentPrefs.value
        updatePodcastPlayback(p.id) {
            if (on) it.copy(skipBackMs = cur.skipBackMs, skipForwardMs = cur.skipForwardMs)
            else it.copy(skipBackMs = null, skipForwardMs = null)
        }
    }

    // ---- Automation (phase 7) ----
    val tidyDefaults: StateFlow<TidyDefaults> = Settings.tidyDefaults(app)

    fun updateTidyDefaults(change: (TidyDefaults) -> TidyDefaults) {
        Settings.updateTidyDefaults(app, change)
        viewModelScope.launch {
            val n = repo.tidyInbox()
            if (n > 0) say("Archived $n episode${if (n == 1) "" else "s"} from New Episodes")
        }
    }

    fun updateRules(podcastId: Long, change: (Podcast) -> Podcast) {
        viewModelScope.launch { repo.updateRules(podcastId, change) }
    }

    // ---- Smart playlists (phase 7) ----
    val playlists: StateFlow<List<Playlist>> = repo.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlistCounts: StateFlow<Map<String, Int>> = repo.observePlaylistCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun playlist(id: String): Flow<Playlist?> = repo.playlists.observe(id)

    fun playlistEpisodes(id: String): Flow<List<EpisodeWithPodcast>> = repo.observePlaylistEpisodes(id)

    fun createPlaylist(name: String, filters: PlaylistFilters) {
        viewModelScope.launch {
            val id = repo.createPlaylist(name, filters)
            navigate(Screen.PlaylistDetail(id))
        }
    }

    fun updatePlaylist(id: String, name: String, filters: PlaylistFilters) {
        viewModelScope.launch { repo.updatePlaylist(id, name, filters) }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            repo.deletePlaylist(id)
            if (backStack.lastOrNull() == Screen.PlaylistDetail(id)) back()
            say("Playlist deleted")
        }
    }

    fun queueAll(items: List<EpisodeWithPodcast>) {
        viewModelScope.launch {
            val n = repo.queueAllLast(items.map { it.episode.id })
            say(if (n == 0) "All of these are already queued" else "Added $n episode${if (n == 1) "" else "s"} to the queue")
        }
    }

    // ---- Pins ----
    fun setPinned(podcastId: Long, pinned: Boolean) {
        viewModelScope.launch { repo.setPinned(podcastId, pinned) }
    }

    // ---- Sync account ----
    val syncConfigured get() = app.sync.supabase.configured
    val session = app.sync.supabase.session
    val syncStatus = app.sync.status
    var signingIn by mutableStateOf(false)
        private set
    var signInError by mutableStateOf<String?>(null)
        private set

    fun signIn(email: String, password: String) {
        if (signingIn) return
        viewModelScope.launch {
            signingIn = true
            signInError = null
            try {
                app.sync.supabase.signIn(email, password)
                app.sync.resetForNewAccount()
                say(if (app.sync.sync("sign-in")) "Signed in and synced" else "Signed in. Sync will retry shortly")
                com.earshot.app.push.Push.register(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                signInError = e.message ?: "Couldn't sign in"
            } finally {
                signingIn = false
            }
        }
    }

    fun signOut() = app.sync.supabase.signOut()

    fun syncNow() {
        viewModelScope.launch {
            say(if (app.sync.sync("Sync now")) "Synced" else "Sync failed: ${app.sync.status.value.error ?: "not signed in"}")
        }
    }

    // ---- Transcripts and bookmarks (phase 6) ----
    val transcripts = app.transcripts

    fun transcript(episodeId: String): Flow<LoadedTranscript?> = transcripts.observe(episodeId)

    /** Ids of episodes with a saved transcript. */
    val transcriptIds: StateFlow<Set<String>> = app.db.transcripts().observeIds().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val transcribing: StateFlow<Map<String, TranscribeState>> = transcripts.transcribing

    val bookmarks: StateFlow<List<Bookmark>> = transcripts.observeBookmarks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun bookmarksFor(episodeId: String): Flow<List<Bookmark>> = transcripts.observeBookmarks(episodeId)

    fun episode(id: String): Flow<EpisodeWithPodcast?> = repo.observeEpisode(id)

    val signedIn: Boolean get() = app.sync.canSync

    /** Bookmarks the playing moment. */
    fun bookmarkNow() {
        val id = nowPlaying.value.episodeId ?: return
        val pos = nowPlaying.value.positionMs
        viewModelScope.launch {
            val b = transcripts.addBookmark(id, pos)
            say(if (b != null) "Bookmarked at ${formatClock(b.positionMs)}" else "Couldn't bookmark this episode")
        }
    }

    fun updateBookmark(b: Bookmark) {
        viewModelScope.launch { transcripts.updateBookmark(b) }
    }

    fun deleteBookmark(b: Bookmark) {
        viewModelScope.launch {
            transcripts.deleteBookmark(b)
            say("Bookmark deleted")
        }
    }

    /** Plays an episode from a moment, if it's still in the library. */
    fun playAt(episodeId: String, positionMs: Long) {
        viewModelScope.launch {
            val item = repo.episodes.getWithPodcast(episodeId)
            if (item == null) {
                say("That episode isn't in your library any more")
                return@launch
            }
            player.playAt(item, positionMs)
        }
    }

    /** Fetches the feed's transcript now (when opening the transcript page). Errors are shown on the page. */
    var feedTranscriptError by mutableStateOf<String?>(null)
        private set
    var fetchingTranscript by mutableStateOf(false)
        private set

    fun fetchFeedTranscript(episodeId: String) {
        if (fetchingTranscript) return
        viewModelScope.launch {
            val e = repo.episodes.get(episodeId) ?: return@launch
            if (e.transcriptUrl == null) return@launch
            fetchingTranscript = true
            feedTranscriptError = null
            try {
                transcripts.ensureFeedTranscript(e)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                feedTranscriptError = ex.message ?: ex.toString()
            } finally {
                fetchingTranscript = false
            }
        }
    }

    fun transcribeWithGemini(episodeId: String) {
        if (!app.sync.canSync) {
            say("Sign in first (Settings > Sync): transcribing goes through your Supabase project")
            return
        }
        transcripts.requestTranscription(episodeId)
        say("Transcribing with Deepgram. This takes about a minute; you can leave the app")
    }

    fun cancelTranscription(episodeId: String) = transcripts.cancelTranscription(episodeId)

    // Search across transcripts
    var transcriptQuery by mutableStateOf("")
    var transcriptResults by mutableStateOf<List<TranscriptHit>>(emptyList())
        private set
    var searchingTranscripts by mutableStateOf(false)
        private set
    private var transcriptSearchJob: Job? = null

    fun searchTranscripts(query: String) {
        transcriptQuery = query
        transcriptSearchJob?.cancel()
        if (query.isBlank()) {
            transcriptResults = emptyList()
            return
        }
        transcriptSearchJob = viewModelScope.launch {
            delay(250) // typing
            searchingTranscripts = true
            try {
                transcriptResults = transcripts.search(query)
            } finally {
                searchingTranscripts = false
            }
        }
    }

    // Markdown export: MainActivity hands these to the share sheet.
    private val _shares = Channel<ShareRequest>(Channel.BUFFERED)
    val shares: Flow<ShareRequest> = _shares.receiveAsFlow()

    private fun share(result: Pair<String, String>?, missing: String) {
        if (result == null) say(missing) else _shares.trySend(ShareRequest(result.first, result.second))
    }

    fun exportEpisodeBookmarks(episodeId: String) {
        viewModelScope.launch { share(transcripts.exportEpisodeBookmarks(episodeId), "Nothing to export") }
    }

    fun exportTranscript(episodeId: String) {
        viewModelScope.launch { share(transcripts.exportTranscript(episodeId), "This episode has no transcript yet") }
    }

    fun exportAllBookmarks() {
        viewModelScope.launch {
            if (transcripts.allBookmarks().isEmpty()) say("No bookmarks yet") else share(transcripts.exportAllBookmarks(), "")
        }
    }

    fun skipBack() = player.skipBy(-currentPrefs.value.skipBackMs)
    fun skipForward() = player.skipBy(currentPrefs.value.skipForwardMs)
}
