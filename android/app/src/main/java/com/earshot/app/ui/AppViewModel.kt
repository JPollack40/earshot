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
import com.earshot.app.data.db.Podcast
import com.earshot.app.data.db.EpisodeWithPodcast
import com.earshot.app.data.db.Bookmark
import com.earshot.app.data.transcript.LoadedTranscript
import com.earshot.app.data.transcript.TranscribeState
import com.earshot.app.data.transcript.TranscriptHit
import com.earshot.app.data.feed.Chapter
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

    /** Library tab: 0 Queue, 1 New Episodes, 2 Latest, 3 Podcasts. Kept here so Glow's bottom bar can switch it. */
    var libraryTab by mutableIntStateOf(0)

    /** Glow's bottom bar: go back to the library on [tab], closing any pages on top (previews close as usual). */
    fun showTab(tab: Int) {
        libraryTab = tab
        while (backStack.size > 1) back()
    }

    fun navigate(screen: Screen) {
        backStack.add(screen)
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

    val chapters: StateFlow<List<Chapter>> = currentEpisode
        .map { it?.episode?.chaptersUrl }
        .distinctUntilChanged()
        .mapLatest { url ->
            if (url == null) {
                emptyList()
            } else {
                try {
                    repo.fetchChapters(url).also { Log.i("Earshot", "Loaded ${it.size} chapters from $url") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Earshot", "Couldn't load chapters from $url", e)
                    emptyList()
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun podcast(id: Long) = repo.observePodcast(id)
    fun episodesFor(podcastId: Long) = repo.observeEpisodes(podcastId)

    // A channel (not a SharedFlow) so messages sent while the UI is away, e.g. behind the file
    // picker, are queued and shown on return instead of being dropped.
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()
    private fun say(msg: String) {
        _messages.trySend(msg)
    }

    init {
        player.connect()
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
        say("Transcribing with Gemini. This takes a few minutes; you can leave the app")
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
