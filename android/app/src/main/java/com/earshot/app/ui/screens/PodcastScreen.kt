@file:OptIn(ExperimentalMaterial3Api::class)

package com.earshot.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.data.EpisodeSearch
import com.earshot.app.data.db.EpisodeWithPodcast
import com.earshot.app.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Episodes that match a search within this podcast, with the bit of show notes that matched. */
private data class EpisodeMatches(val query: String, val episodes: List<EpisodeWithPodcast>, val snippets: Map<String, String>)

@Composable
fun PodcastScreen(vm: AppViewModel, podcastId: Long) {
    val podcast by remember(podcastId) { vm.podcast(podcastId) }.collectAsStateWithLifecycle(null)
    val episodes by remember(podcastId) { vm.episodesFor(podcastId) }.collectAsStateWithLifecycle(emptyList())
    var menuOpen by remember { mutableStateOf(false) }
    var confirmUnsubscribe by remember { mutableStateOf(false) }
    var showPlayback by remember { mutableStateOf(false) }
    val defaults by vm.playbackDefaults.collectAsStateWithLifecycle()
    var descExpanded by remember { mutableStateOf(false) }

    // ---- Search within this podcast (titles and show notes, on the phone) ----
    var searchOpen by rememberSaveable(podcastId) { mutableStateOf(false) }
    var query by rememberSaveable(podcastId) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Plain-text index of every episode, rebuilt only when the episode list changes.
    val index by produceState(emptyMap<String, String>(), episodes) {
        value = withContext(Dispatchers.Default) {
            episodes.associate { it.episode.id to EpisodeSearch.index(it.episode.title, it.episode.description) }
        }
    }
    val matches by produceState<EpisodeMatches?>(null, query, index, episodes) {
        val terms = EpisodeSearch.terms(query)
        if (terms.isEmpty()) {
            value = null
            return@produceState
        }
        delay(150) // let typing settle
        value = withContext(Dispatchers.Default) {
            val found = episodes.filter { e -> index[e.episode.id]?.let { EpisodeSearch.matches(it, terms) } == true }
            EpisodeMatches(
                query = query,
                episodes = found,
                snippets = found.mapNotNull { e ->
                    EpisodeSearch.snippet(e.episode.description, terms)?.let { e.episode.id to it }
                }.toMap(),
            )
        }
    }
    val searching = searchOpen && EpisodeSearch.terms(query).isNotEmpty()
    val shown = if (searching) matches?.episodes.orEmpty() else episodes

    fun closeSearch() {
        searchOpen = false
        query = ""
        focusManager.clearFocus()
    }
    BackHandler(enabled = searchOpen) { closeSearch() }
    LaunchedEffect(searchOpen) { if (searchOpen) focus.requestFocus() }

    val p = podcast
    val previewing = p != null && !p.subscribed

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    if (searchOpen) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            placeholder = { Text("Search episodes and show notes") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                        )
                    } else {
                        Text(p?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (searchOpen) closeSearch() else vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (searchOpen) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    } else {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search this podcast")
                        }
                        IconButton(onClick = { showPlayback = true }) {
                            Icon(Icons.Filled.Tune, contentDescription = "Playback settings")
                        }
                        RefreshAction(vm.refreshing) { vm.refresh(podcastId) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Playback settings") },
                                onClick = {
                                    menuOpen = false
                                    showPlayback = true
                                },
                            )
                            if (!previewing) {
                                DropdownMenuItem(
                                    text = { Text("Unsubscribe") },
                                    onClick = {
                                        menuOpen = false
                                        confirmUnsubscribe = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        EpisodeList(
            vm = vm,
            episodes = shown,
            showPodcast = false,
            modifier = Modifier.padding(padding).fillMaxSize(),
            snippets = if (searching) matches?.snippets.orEmpty() else emptyMap(),
            header = {
                if (p != null && searching) {
                    item(key = "matches") {
                        val m = matches
                        Text(
                            when {
                                m == null || m.query != query -> "Searching…"
                                m.episodes.isEmpty() -> "No episodes match \"${query.trim()}\""
                                else -> "${m.episodes.size} of ${episodes.size} episodes match"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                } else if (p != null) {
                    item(key = "header") {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Artwork(p.imageUrl, 72, corner = 8)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.title, style = MaterialTheme.typography.titleMedium)
                                    if (p.author.isNotBlank()) {
                                        Text(p.author, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(
                                        "${episodes.size} episodes",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (previewing) {
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (vm.subscribingUrl == p.feedUrl) {
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        FilledTonalButton(onClick = { vm.subscribe(p.feedUrl, openAfter = false) }) { Text("Subscribe") }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "Not subscribed. Episodes you play or queue stay in your queue until played.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            if (p.description.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    AnnotatedString.fromHtml(p.description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (descExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { descExpanded = !descExpanded },
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            },
        )
    }

    val pod = podcast
    if (showPlayback && pod != null) {
        PodcastPlaybackDialog(
            podcast = pod,
            defaults = defaults,
            onChange = { change -> vm.updatePodcastPlayback(podcastId, change) },
            onDismiss = { showPlayback = false },
        )
    }

    if (confirmUnsubscribe) {
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            title = { Text("Unsubscribe?") },
            text = { Text("Remove ${podcast?.title.orEmpty()} and its downloads from this phone? Episodes in your queue stay until you play or remove them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsubscribe = false
                    vm.unsubscribe(podcastId)
                }) { Text("Unsubscribe") }
            },
            dismissButton = { TextButton(onClick = { confirmUnsubscribe = false }) { Text("Cancel") } },
        )
    }
}
