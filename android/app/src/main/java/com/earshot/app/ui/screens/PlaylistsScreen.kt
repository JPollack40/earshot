@file:OptIn(ExperimentalMaterial3Api::class)

package com.earshot.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.earshot.app.data.Automation
import com.earshot.app.data.PlaylistFilters
import com.earshot.app.data.PlaylistJson
import com.earshot.app.data.Repository
import com.earshot.app.data.db.PodcastWithCount
import com.earshot.app.ui.AppViewModel
import com.earshot.app.ui.Screen

/**
 * The Playlists tab (phase 7): saved smart playlists with how many episodes each has now, and a
 * button to make a new one.
 */
@Composable
fun PlaylistsList(vm: AppViewModel, modifier: Modifier = Modifier) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val counts by vm.playlistCounts.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val titles = remember(library) { titleLookup(library) }

    if (playlists.isEmpty()) {
        Column(
            modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No playlists yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "A smart playlist keeps itself up to date from filters you choose: which podcasts, unplayed or " +
                    "in progress, length, how recent, words in the title. For example \"Short ones\": unplayed, under 30 minutes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { creating = true }) { Text("New playlist") }
        }
    } else {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
            item(key = "new") {
                Row(
                    Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("New playlist", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            }
            items(playlists, key = { it.id }) { p ->
                val filters = remember(p.filtersJson) { PlaylistJson.parse(p.filtersJson) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.navigate(Screen.PlaylistDetail(p.id)) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            filters.summary(titles),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    counts[p.id]?.let { n ->
                        Text(
                            n.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            }
        }
    }

    if (creating) {
        PlaylistEditor(
            title = "New playlist",
            initialName = "",
            initial = PlaylistFilters(),
            library = library,
            onSave = { name, f ->
                creating = false
                vm.createPlaylist(name, f)
            },
            onDismiss = { creating = false },
        )
    }
}

/** One playlist's episodes, with Edit, Add all to queue and Delete. */
@Composable
fun PlaylistScreen(vm: AppViewModel, playlistId: String) {
    val playlist by remember(playlistId) { vm.playlist(playlistId) }.collectAsStateWithLifecycle(null)
    val episodes by remember(playlistId) { vm.playlistEpisodes(playlistId) }.collectAsStateWithLifecycle(emptyList())
    val library by vm.library.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val p = playlist
    val filters = remember(p?.filtersJson) { PlaylistJson.parse(p?.filtersJson) }
    val titles = remember(library) { titleLookup(library) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(p?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { editing = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Edit playlist")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Edit playlist") }, onClick = { menu = false; editing = true })
                            DropdownMenuItem(text = { Text("Add all to queue") }, onClick = { menu = false; vm.queueAll(episodes) })
                            DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        EpisodeList(
            vm = vm,
            episodes = episodes,
            showPodcast = true,
            modifier = Modifier.padding(padding).fillMaxSize(),
            header = {
                item(key = "summary") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${episodes.size} episode${if (episodes.size == 1) "" else "s"} · " + filters.summary(titles),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (episodes.isNotEmpty()) {
                            FilledTonalButton(onClick = { vm.queueAll(episodes) }) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Queue all")
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (episodes.isEmpty() && p != null) {
                        Text(
                            "Nothing matches these filters right now. Tap the sliders icon to change them.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            },
        )
    }

    if (editing && p != null) {
        PlaylistEditor(
            title = "Edit playlist",
            initialName = p.name,
            initial = filters,
            library = library,
            onSave = { name, f ->
                editing = false
                vm.updatePlaylist(p.id, name, f)
            },
            onDismiss = { editing = false },
        )
    }
    if (confirmDelete && p != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("Only the playlist goes; its episodes stay in your library and queue.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deletePlaylist(p.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Feed address → podcast title, for playlist summaries. */
private fun titleLookup(library: List<PodcastWithCount>): (String) -> String? {
    val byKey = library.associate { Repository.feedKey(it.podcast.feedUrl) to it.podcast.title }
    return { url -> byKey[Repository.feedKey(url)] }
}

/** Making or changing a playlist: its name and filters. */
@Composable
private fun PlaylistEditor(
    title: String,
    initialName: String,
    initial: PlaylistFilters,
    library: List<PodcastWithCount>,
    onSave: (String, PlaylistFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var f by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf(false) }
    val divider = @Composable { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp) }
    val titles = remember(library) { titleLookup(library) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Short ones") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { picking = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Podcasts", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when (f.podcasts.size) {
                                0 -> "All podcasts"
                                1 -> titles(f.podcasts[0]) ?: "1 podcast"
                                else -> "${f.podcasts.size} podcasts"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("Choose", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                divider()
                ChoiceRow("Episodes", null, f.status, PlaylistFilters.Status.entries, { it.label }) { f = f.copy(status = it) }
                divider()
                ChoiceRow("At least", null, f.minMinutes, listOf<Int?>(null) + Automation.MINUTE_CHOICES, { it?.let { m -> "$m min" } ?: "Any length" }) {
                    f = f.copy(minMinutes = it)
                }
                divider()
                ChoiceRow("At most", null, f.maxMinutes, listOf<Int?>(null) + Automation.MINUTE_CHOICES, { it?.let { m -> "$m min" } ?: "Any length" }) {
                    f = f.copy(maxMinutes = it)
                }
                divider()
                ChoiceRow("Released in the last", null, f.withinDays, listOf<Int?>(null) + PlaylistFilters.DAY_CHOICES, { it?.let { d -> if (d == 1) "Day" else "$d days" } ?: "Any time" }) {
                    f = f.copy(withinDays = it)
                }
                divider()
                OutlinedTextField(
                    value = f.keywords,
                    onValueChange = { f = f.copy(keywords = it) },
                    label = { Text("Title contains (optional)") },
                    placeholder = { Text("words or phrases, comma-separated") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
                divider()
                Row(
                    Modifier.fillMaxWidth().clickable { f = f.copy(downloaded = !f.downloaded) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Downloaded only", style = MaterialTheme.typography.bodyMedium)
                        Text("This phone only; the web player ignores it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = f.downloaded, onCheckedChange = { f = f.copy(downloaded = it) })
                }
                divider()
                ChoiceRow("Order", null, f.sort, PlaylistFilters.Sort.entries, { it.label }) { f = f.copy(sort = it) }
                divider()
                ChoiceRow("Show at most", null, f.limit, PlaylistFilters.LIMIT_CHOICES, { "$it episodes" }) { f = f.copy(limit = it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim().ifBlank { "Playlist" }, f.copy(keywords = f.keywords.trim())) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (picking) {
        PodcastPicker(
            library = library,
            selected = f.podcasts,
            onDone = {
                f = f.copy(podcasts = it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/** Ticks podcasts for a playlist. None ticked = every podcast. */
@Composable
private fun PodcastPicker(
    library: List<PodcastWithCount>,
    selected: List<String>,
    onDone: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var keys by remember { mutableStateOf(selected.map(Repository::feedKey).toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Podcasts") },
        text = {
            Column {
                Text(
                    "None ticked means every podcast, including ones you subscribe to later.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(library, key = { it.podcast.id }) { row ->
                        val key = Repository.feedKey(row.podcast.feedUrl)
                        val on = key in keys
                        Row(
                            Modifier.fillMaxWidth().clickable { keys = if (on) keys - key else keys + key }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = { keys = if (on) keys - key else keys + key })
                            Artwork(row.podcast.imageUrl, 32)
                            Spacer(Modifier.width(8.dp))
                            Text(row.podcast.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDone(library.map { it.podcast.feedUrl }.filter { Repository.feedKey(it) in keys })
            }) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = { keys = emptySet() }) { Text("Clear") }
        },
    )
}
