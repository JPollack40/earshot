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
import com.earshot.app.data.Settings
import com.earshot.app.data.db.EpisodeWithPodcast
import com.earshot.app.data.feed.Chapter
import com.earshot.app.data.search.ItunesSearch
import com.earshot.app.data.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Library : Screen
    data class PodcastDetail(val podcastId: Long) : Screen
    data object Add : Screen
    data object Settings : Screen
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EarshotApp
    val repo = app.repository
    val player = app.player

    // ---- Navigation (simple back stack; no nav library needed yet) ----
    val backStack = mutableStateListOf<Screen>(Screen.Library)
    var showPlayer by mutableStateOf(false)

    fun navigate(screen: Screen) {
        backStack.add(screen)
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    // ---- Data ----
    val library = repo.observeLibrary().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latest = repo.observeLatest().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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
    private var searchJob: Job? = null

    fun search(term: String) {
        if (term.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searching = true
            try {
                searchResults = ItunesSearch.search(term)
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

    // ---- OPML import (runs app-wide, so it survives leaving this screen) ----
    val importStatus: StateFlow<ImportStatus> = app.importer.status

    fun importOpml(uri: Uri) {
        if (!app.importer.start(uri)) say("An import is already running")
    }

    fun dismissImport() = app.importer.dismiss()

    fun unsubscribe(podcastId: Long) {
        viewModelScope.launch {
            repo.unsubscribe(podcastId)
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

    // ---- Settings ----
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
}
