@file:OptIn(ExperimentalMaterial3Api::class)

package com.earshot.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.data.db.Bookmark
import com.earshot.app.data.transcript.LoadedTranscript
import com.earshot.app.data.transcript.TranscriptFormat
import com.earshot.app.ui.AppViewModel
import com.earshot.app.ui.Screen
import com.earshot.app.ui.formatClock

/** Every bookmark, grouped by episode (most recent first), with quotes from the transcripts. */
@Composable
fun BookmarksScreen(vm: AppViewModel) {
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val groups = remember(bookmarks) { bookmarks.groupBy { it.episodeId }.toList() }
    var editing by remember { mutableStateOf<Pair<Bookmark, LoadedTranscript?>?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Bookmarks") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (bookmarks.isNotEmpty()) {
                        IconButton(onClick = vm::exportAllBookmarks) {
                            Icon(Icons.Outlined.Share, contentDescription = "Export all bookmarks (Markdown)")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (bookmarks.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.BookmarkBorder, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("No bookmarks yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tap Bookmark in the player, in Android Auto or in the notification to save the last minute of what you heard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            groups.forEach { (episodeId, list) ->
                item(key = "h-$episodeId") {
                    EpisodeGroupHeader(vm, episodeId, list.first())
                }
                items(list.sortedBy { it.positionMs }, key = { it.id }) { b ->
                    BookmarkRow(vm, b) { t -> editing = b to t }
                }
                item(key = "d-$episodeId") { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp) }
            }
        }
    }

    editing?.let { (b, t) -> BookmarkDialog(vm, b, t) { editing = null } }
}

@Composable
private fun EpisodeGroupHeader(vm: AppViewModel, episodeId: String, sample: Bookmark) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { vm.navigate(Screen.TranscriptView(episodeId)) }
            .padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(sample.imageUrl, 40)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(sample.episodeTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sample.podcastTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open transcript") }, onClick = { menu = false; vm.navigate(Screen.TranscriptView(episodeId)) })
                DropdownMenuItem(text = { Text("Export these bookmarks (Markdown)") }, onClick = { menu = false; vm.exportEpisodeBookmarks(episodeId) })
                DropdownMenuItem(text = { Text("Export transcript with bookmarks") }, onClick = { menu = false; vm.exportTranscript(episodeId) })
            }
        }
    }
}

@Composable
private fun BookmarkRow(vm: AppViewModel, b: Bookmark, onOpen: (LoadedTranscript?) -> Unit) {
    val transcript by remember(b.episodeId) { vm.transcript(b.episodeId) }.collectAsStateWithLifecycle(null)
    val quote = transcript?.let { TranscriptFormat.quote(it.segments, it.timed, b.startMs, b.endMs) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(transcript) }
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.Bookmark, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(formatClock(b.positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (quote != null) {
                Text(quote, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            } else {
                Text(
                    "No transcript for this moment yet",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (b.note.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(b.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        IconButton(onClick = { vm.playAt(b.episodeId, b.startMs) }) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play from ${formatClock(b.startMs)}", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Edit a bookmark: widen or trim the quoted stretch, add a note, play from it, or delete it.
 * The quote updates as the window changes.
 */
@Composable
fun BookmarkDialog(vm: AppViewModel, bookmark: Bookmark, transcript: LoadedTranscript?, onDismiss: () -> Unit) {
    var start by remember(bookmark.id) { mutableLongStateOf(bookmark.startMs) }
    var end by remember(bookmark.id) { mutableLongStateOf(bookmark.endMs) }
    var note by remember(bookmark.id) { mutableStateOf(bookmark.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    val quote = transcript?.let { TranscriptFormat.quote(it.segments, it.timed, start, end) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmark at ${formatClock(bookmark.positionMs)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(bookmark.episodeTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                Text(
                    quote ?: "No transcript for this moment yet. Open the transcript page to get one.",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = if (quote == null) FontStyle.Italic else FontStyle.Normal,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(12.dp))
                Text("Quote from ${formatClock(start)} to ${formatClock(end)}", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Start", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                    TextButton(onClick = { start = (start - 15_000).coerceAtLeast(0) }) { Text("−15 s") }
                    TextButton(onClick = { start = (start + 15_000).coerceAtMost(maxOf(0, end - 5_000)) }) { Text("+15 s") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("End", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                    TextButton(onClick = { end = (end - 5_000).coerceAtLeast(start + 5_000) }) { Text("−5 s") }
                    TextButton(onClick = { end += 15_000 }) { Text("+15 s") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vm.playAt(bookmark.episodeId, start); onDismiss() }) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Play from ${formatClock(start)}")
                    }
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (start != bookmark.startMs || end != bookmark.endMs || note != bookmark.note) {
                    vm.updateBookmark(bookmark.copy(startMs = start, endMs = end, note = note.trim()))
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this bookmark?") },
            text = { Text("It's removed on every device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteBookmark(bookmark)
                    onDismiss()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}
