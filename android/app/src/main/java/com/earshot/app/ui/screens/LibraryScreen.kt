@file:OptIn(ExperimentalMaterial3Api::class)

package com.earshot.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.data.db.PodcastWithCount
import com.earshot.app.ui.AppViewModel
import com.earshot.app.ui.Screen
import com.earshot.app.ui.Tabs
import com.earshot.app.ui.formatDuration
import com.earshot.app.ui.theme.LocalGlow
import com.earshot.app.ui.theme.rememberArtColor
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val library by vm.library.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()
    val inbox by vm.inbox.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val tab = vm.libraryTab
    val glow = LocalGlow.current
    val now by vm.nowPlaying.collectAsStateWithLifecycle()

    val content: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.fillMaxSize()) {
            if (!glow) {
                // Five tabs (phase 7 added Playlists): scrollable, so they fit on the Fold's cover screen.
                PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    Tab(selected = tab == Tabs.QUEUE, onClick = { vm.libraryTab = Tabs.QUEUE }, text = { TabLabel("Queue", queue.size) })
                    Tab(selected = tab == Tabs.NEW, onClick = { vm.libraryTab = Tabs.NEW }, text = { TabLabel("New Episodes", inbox.size) })
                    Tab(selected = tab == Tabs.LATEST, onClick = { vm.libraryTab = Tabs.LATEST }, text = { TabLabel("Latest", 0) })
                    Tab(selected = tab == Tabs.PLAYLISTS, onClick = { vm.libraryTab = Tabs.PLAYLISTS }, text = { TabLabel("Playlists", 0) })
                    Tab(selected = tab == Tabs.PODCASTS, onClick = { vm.libraryTab = Tabs.PODCASTS }, text = { TabLabel("Podcasts", library.size) })
                }
            }
            when {
                tab == Tabs.QUEUE -> QueueList(vm, Modifier.fillMaxSize())
                tab == Tabs.NEW -> InboxList(vm, Modifier.fillMaxSize())
                library.isEmpty() -> EmptyLibrary(onAdd = { vm.navigate(Screen.Add) })
                tab == Tabs.LATEST -> EpisodeList(vm, latest, showPodcast = true, modifier = Modifier.fillMaxSize())
                tab == Tabs.PLAYLISTS -> PlaylistsList(vm, Modifier.fillMaxSize())
                else -> PodcastList(vm, library)
            }
        }
    }

    if (glow) {
        // Glow: a header tinted from what's playing, the section title in large type, tabs in the bottom bar.
        val tint = rememberArtColor(now.artworkUrl, now.podcastTitle.ifEmpty { "earshot" })
        val queueLeftMs = queue.sumOf { (it.episode.durationMs - it.episode.positionMs).coerceAtLeast(0) }
        val pinned = library.count { it.podcast.pinned }
        Column(Modifier.fillMaxSize()) {
            GlowHero(
                tint = tint,
                eyebrow = null,
                title = Tabs.NAMES[tab.coerceIn(0, Tabs.NAMES.lastIndex)],
                summary = when (tab) {
                    Tabs.QUEUE -> if (queue.isEmpty()) "Nothing queued" else "${queue.size} episode${if (queue.size == 1) "" else "s"}" +
                        (if (queueLeftMs > 0) " · about ${formatDuration(queueLeftMs)} left" else "")
                    Tabs.NEW -> if (inbox.isEmpty()) "All caught up" else "${inbox.size} new"
                    Tabs.LATEST -> "Newest episodes across your podcasts"
                    Tabs.PLAYLISTS -> "Smart playlists that keep themselves up to date"
                    else -> "${library.size} podcast${if (library.size == 1) "" else "s"}" + (if (pinned > 0) " · $pinned pinned" else "")
                },
                top = {
                    Text("Earshot", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))
                },
                actions = {
                    CompositionLocalProvider(LocalContentColor provides Color.White) {
                        RefreshAction(vm.refreshing) { vm.refreshAll() }
                        IconButton(onClick = { vm.navigate(Screen.Add) }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add podcast")
                        }
                        IconButton(onClick = { vm.navigate(Screen.TranscriptSearch) }) {
                            Icon(Icons.AutoMirrored.Outlined.ManageSearch, contentDescription = "Search transcripts")
                        }
                        IconButton(onClick = { vm.navigate(Screen.Bookmarks) }) {
                            Icon(Icons.Outlined.BookmarkBorder, contentDescription = "Bookmarks")
                        }
                        IconButton(onClick = { vm.navigate(Screen.Stats) }) {
                            Icon(Icons.Outlined.Insights, contentDescription = "Stats")
                        }
                        IconButton(onClick = { vm.navigate(Screen.Settings) }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
            content(Modifier.weight(1f))
        }
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Earshot") },
                actions = {
                    RefreshAction(vm.refreshing) { vm.refreshAll() }
                    IconButton(onClick = { vm.navigate(Screen.Add) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add podcast")
                    }
                    IconButton(onClick = { vm.navigate(Screen.TranscriptSearch) }) {
                        Icon(Icons.AutoMirrored.Outlined.ManageSearch, contentDescription = "Search transcripts")
                    }
                    IconButton(onClick = { vm.navigate(Screen.Bookmarks) }) {
                        Icon(Icons.Outlined.BookmarkBorder, contentDescription = "Bookmarks")
                    }
                    IconButton(onClick = { vm.navigate(Screen.Stats) }) {
                        Icon(Icons.Outlined.Insights, contentDescription = "Stats")
                    }
                    IconButton(onClick = { vm.navigate(Screen.Settings) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

@Composable
fun RefreshAction(refreshing: Boolean, onRefresh: () -> Unit) {
    if (refreshing) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    } else {
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Check for new episodes")
        }
    }
}

@Composable
private fun PodcastList(vm: AppViewModel, library: List<PodcastWithCount>) {
    var confirmUnsubscribe by remember { mutableStateOf<PodcastWithCount?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        items(library, key = { it.podcast.id }) { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.navigate(Screen.PodcastDetail(row.podcast.id)) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(row.podcast.imageUrl, 48)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.podcast.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (row.podcast.author.isNotBlank()) {
                        Text(
                            row.podcast.author,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (row.unplayedCount > 0) {
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text(row.unplayedCount.toString())
                    }
                }
                IconButton(onClick = { vm.setPinned(row.podcast.id, !row.podcast.pinned) }) {
                    Icon(
                        if (row.podcast.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (row.podcast.pinned) "Unpin" else "Pin to top",
                        tint = if (row.podcast.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { confirmUnsubscribe = row }) { Text("Unsubscribe", style = MaterialTheme.typography.labelSmall) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        }
    }

    confirmUnsubscribe?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = null },
            title = { Text("Unsubscribe?") },
            text = { Text("Remove ${row.podcast.title} and its downloads from this phone? Episodes in your queue stay until you play or remove them.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.unsubscribe(row.podcast.id)
                    confirmUnsubscribe = null
                }) { Text("Unsubscribe") }
            },
            dismissButton = { TextButton(onClick = { confirmUnsubscribe = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No podcasts yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Search for a show, paste an RSS feed link, or import your subscriptions (OPML) from your current app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAdd) { Text("Add podcasts") }
    }
}

/** Compact tab label with an optional count, so four tabs fit on the Fold's cover screen. */
@Composable
private fun TabLabel(name: String, count: Int) {
    Text(
        if (count > 0) "$name $count" else name,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        softWrap = false,
    )
}
