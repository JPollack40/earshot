package com.earshot.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.earshot.app.EarshotApp
import com.earshot.app.data.Repository
import com.earshot.app.data.db.Listen
import com.earshot.app.data.stats.ListenEntry
import com.earshot.app.data.stats.StatsRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Data for the Stats page and the week / month / year reviews: the listening history (this phone's
 * plus the web player's, pulled by sync), podcast artwork by feed, and known episode lengths.
 * Shared by the Stats screen and the review screens (both are in the same activity).
 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EarshotApp

    /** Null until loaded. */
    val entries: StateFlow<List<ListenEntry>?> = app.db.listens().observeAll()
        .map { list -> list.map { it.toEntry() } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Artwork by feed key, for the top podcasts list. */
    val artwork: StateFlow<Map<String, String>> = app.repository.observeLibrary()
        .map { lib -> lib.mapNotNull { r -> r.podcast.imageUrl?.let { Repository.feedKey(r.podcast.feedUrl) to it } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _lengths = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lengths: StateFlow<Map<String, Long>> = _lengths.asStateFlow()

    /** The range picked on the Stats page; 4 weeks to start with (like Stats.fm). */
    var range by mutableStateOf(StatsRange.FOUR_WEEKS)

    init {
        refreshLengths()
    }

    fun refreshLengths() {
        viewModelScope.launch {
            _lengths.value = runCatching { app.db.listens().episodeLengths().associate { it.id to it.durationMs } }.getOrDefault(emptyMap())
        }
    }

    private fun Listen.toEntry() = ListenEntry(
        episodeId = episodeId,
        feedUrl = feedUrl,
        podcastTitle = podcastTitle,
        episodeTitle = episodeTitle,
        startedAt = startedAt,
        endedAt = endedAt,
        listenedMs = listenedMs,
        toMs = toMs,
        speed = speed,
        device = device,
    )
}
