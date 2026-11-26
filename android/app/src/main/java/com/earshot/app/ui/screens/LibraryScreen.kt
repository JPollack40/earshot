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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val library by vm.library.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

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
                    IconButton(onClick = { vm.navigate(Screen.Settings) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Latest") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Podcasts (${library.size})") })
            }
            when {
                library.isEmpty() -> EmptyLibrary(onAdd = { vm.navigate(Screen.Add) })
                tab == 0 -> EpisodeList(vm, latest, showPodcast = true, modifier = Modifier.fillMaxSize())
                else -> PodcastList(vm, library)
            }
        }
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
                TextButton(onClick = { confirmUnsubscribe = row }) { Text("Unsubscribe", style = MaterialTheme.typography.labelSmall) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        }
    }

    confirmUnsubscribe?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = null },
            title = { Text("Unsubscribe?") },
            text = { Text("Remove ${row.podcast.title} and its downloaded episodes from this phone?") },
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
