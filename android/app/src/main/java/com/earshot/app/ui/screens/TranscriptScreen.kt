@file:OptIn(ExperimentalMaterial3Api::class)

package com.earshot.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.data.db.Bookmark
import com.earshot.app.data.transcript.TranscribeState
import com.earshot.app.data.transcript.TranscriptFormat
import com.earshot.app.data.transcript.Transcripts
import com.earshot.app.ui.AppViewModel
import com.earshot.app.ui.formatClock
import kotlinx.coroutines.flow.map

/**
 * An episode's transcript. While the episode plays, the line being spoken is highlighted and kept in
 * view (until you scroll; "Follow" brings it back). Tap a line to play from there. Bookmarks show as
 * a bookmark icon on their line; tap it to edit the bookmark.
 */
@Composable
fun TranscriptScreen(vm: AppViewModel, episodeId: String, focusMs: Long?) {
    val item by remember(episodeId) { vm.episode(episodeId) }.collectAsStateWithLifecycle(null)
    // (read, transcript): read is false until the database has answered, so nothing flashes.
    val stored by remember(episodeId) { vm.transcript(episodeId).map { true to it } }.collectAsStateWithLifecycle(false to null)
    val transcript = stored.second
    val bookmarks by remember(episodeId) { vm.bookmarksFor(episodeId) }.collectAsStateWithLifecycle(emptyList())
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val transcribing by vm.transcribing.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var loaded by remember { mutableStateOf(false) }

    val isLoaded = now.episodeId == episodeId
    val title = item?.episode?.title ?: bookmarks.firstOrNull()?.episodeTitle ?: "Transcript"
    val podcastTitle = item?.podcastTitle ?: bookmarks.firstOrNull()?.podcastTitle.orEmpty()
    val state = transcribing[episodeId]

    // A feed transcript that isn't saved yet: fetch it now.
    LaunchedEffect(episodeId, item?.episode?.transcriptUrl) {
        if (item?.episode?.transcriptUrl != null) vm.fetchFeedTranscript(episodeId)
        loaded = true
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            podcastTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isLoaded) {
                        IconButton(onClick = vm::bookmarkNow) {
                            Icon(Icons.Outlined.BookmarkAdd, contentDescription = "Bookmark this moment")
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (transcript != null) {
                                DropdownMenuItem(text = { Text("Export transcript (Markdown)") }, onClick = { menu = false; vm.exportTranscript(episodeId) })
                            }
                            if (bookmarks.isNotEmpty()) {
                                DropdownMenuItem(text = { Text("Export bookmarks (Markdown)") }, onClick = { menu = false; vm.exportEpisodeBookmarks(episodeId) })
                            }
                            if (transcript == null && item != null && state == null) {
                                DropdownMenuItem(text = { Text("Transcribe") }, onClick = { menu = false; vm.transcribeWithGemini(episodeId) })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val t = transcript
            if (!stored.first) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
                return@Box
            }
            if (t == null) {
                NoTranscript(
                    hasFeedTranscript = item?.episode?.transcriptUrl != null,
                    fetching = vm.fetchingTranscript || !loaded,
                    feedError = vm.feedTranscriptError,
                    inLibrary = item != null,
                    state = state,
                    onRetryFeed = { vm.fetchFeedTranscript(episodeId) },
                    onTranscribe = { vm.transcribeWithGemini(episodeId) },
                    onCancel = { vm.cancelTranscription(episodeId) },
                )
                return@Box
            }
            val segments = t.segments
            val current = if (isLoaded && t.timed) TranscriptFormat.indexAt(segments, now.positionMs) else -1
            val focusIndex = remember(t, focusMs) { if (focusMs != null && t.timed) TranscriptFormat.indexAt(segments, focusMs).coerceAtLeast(0) else -1 }
            val bookmarkAt = remember(bookmarks, segments) {
                if (!t.timed) emptyMap() else bookmarks.groupBy { TranscriptFormat.indexAt(segments, it.positionMs).coerceAtLeast(0) }
            }
            val list = rememberLazyListState(initialFirstVisibleItemIndex = (if (focusIndex >= 0) focusIndex else current).coerceAtLeast(0).let { (it - 2).coerceAtLeast(0) })
            var follow by remember { mutableStateOf(focusMs == null) }

            // Scrolling by hand stops following along.
            LaunchedEffect(list) {
                list.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
            }
            LaunchedEffect(current, follow) {
                if (follow && current >= 0) list.animateScrollToItem((current + 1 - 2).coerceAtLeast(0))
            }

            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                item(key = "source") {
                    Text(
                        Transcripts.sourceLabel(t.source) +
                            (if (!t.timed) " · no timings, so lines can't be played or followed" else " · tap a line to play from there"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                itemsIndexed(segments, key = { i, _ -> i }) { i, seg ->
                    val active = i == current
                    val focused = i == focusIndex
                    val prevSpeaker = segments.getOrNull(i - 1)?.speaker
                    val marks = bookmarkAt[i].orEmpty()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    focused -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
                                    else -> MaterialTheme.colorScheme.surface
                                },
                            )
                            .then(if (t.timed) Modifier.clickable { follow = true; vm.playAt(episodeId, seg.startMs) } else Modifier)
                            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (t.timed) {
                            Text(
                                formatClock(seg.startMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(52.dp).padding(top = 2.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            if (seg.speaker != null && seg.speaker != prevSpeaker) {
                                Text(
                                    TranscriptFormat.speakerName(seg.speaker),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                seg.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.87f),
                            )
                        }
                        if (marks.isNotEmpty()) {
                            IconButton(onClick = { editing = marks.first() }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Filled.Bookmark,
                                    contentDescription = "Bookmark at ${formatClock(marks.first().positionMs)}",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(80.dp)) }
            }

            if (isLoaded && t.timed && !follow) {
                ExtendedFloatingActionButton(
                    onClick = { follow = true },
                    icon = { Icon(Icons.Outlined.MyLocation, null) },
                    text = { Text("Follow") },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            }
        }
    }

    editing?.let { b ->
        BookmarkDialog(vm, b, transcript, onDismiss = { editing = null })
    }
}

@Composable
private fun NoTranscript(
    hasFeedTranscript: Boolean,
    fetching: Boolean,
    feedError: String?,
    inLibrary: Boolean,
    state: TranscribeState?,
    onRetryFeed: () -> Unit,
    onTranscribe: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            state is TranscribeState.Waiting || state is TranscribeState.Running -> {
                val running = state as? TranscribeState.Running
                Text("Transcribing with Deepgram", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (running != null && running.parts > 0) {
                    LinearProgressIndicator(progress = { running.part.toFloat() / running.parts }, modifier = Modifier.fillMaxWidth(0.7f))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Part ${minOf(running.part + 1, running.parts)} of ${running.parts}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.7f))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "You can leave this page or the app; it carries on and the transcript appears here when it's done.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
            hasFeedTranscript && fetching -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Getting the podcast's transcript…", style = MaterialTheme.typography.bodyMedium)
            }
            hasFeedTranscript && feedError != null -> {
                Text("Couldn't get the podcast's transcript", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(feedError, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRetryFeed) { Text("Try again") }
                    if (inLibrary) Button(onClick = onTranscribe) { Text("Transcribe") }
                }
            }
            else -> {
                if (state is TranscribeState.Failed) {
                    Text("Transcribing didn't work", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(state.message, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
                } else {
                    Text("No transcript for this episode", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (inLibrary) "The podcast doesn't publish one. Deepgram can make one (uses your free credit; about a minute)."
                        else "This episode isn't in your library any more, so it can't be transcribed.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (inLibrary) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onTranscribe) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (state is TranscribeState.Failed) "Try again" else "Transcribe")
                    }
                }
            }
        }
    }
}
