package com.earshot.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.data.db.EpisodeWithPodcast
import com.earshot.app.ui.AppViewModel
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Inbox: new episodes waiting to be triaged. Swipe right to add to the end of the queue,
 * swipe left to archive. The episode sheet has "Play next" for putting one at the front.
 */
@Composable
fun InboxList(vm: AppViewModel, modifier: Modifier = Modifier) {
    val inbox by vm.inbox.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    var openId by remember { mutableStateOf<String?>(null) }

    if (inbox.isEmpty()) {
        EmptyMessage("All caught up", "New episodes from your podcasts show up here. Swipe right to queue, left to archive.", modifier)
        return
    }
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${inbox.size} new · swipe right to queue, left to archive",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::archiveInbox) { Text("Archive all") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        }
        items(inbox, key = { it.episode.id }) { item ->
            val isCurrent = now.episodeId == item.episode.id
            SwipeRow(
                startToEnd = SwipeAction(Icons.AutoMirrored.Filled.PlaylistAdd, "Queue", MaterialTheme.colorScheme.primaryContainer) {
                    vm.queueLast(item)
                },
                endToStart = SwipeAction(Icons.Filled.Archive, "Archive", MaterialTheme.colorScheme.surfaceContainerHighest) {
                    vm.archive(item)
                },
            ) {
                EpisodeRow(
                    item = item,
                    showPodcast = true,
                    isCurrent = isCurrent,
                    isPlaying = now.isPlaying,
                    onOpen = { openId = item.episode.id },
                    onPlay = { if (isCurrent) vm.player.togglePlay() else vm.play(item) },
                    onDownload = { vm.download(item) },
                    onOpenPodcast = { vm.openPodcastPage(item.episode.podcastId) },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        }
    }
    EpisodeSheetFor(vm, openId?.let { id -> inbox.firstOrNull { it.episode.id == id } }) { openId = null }
}

/**
 * The queue, top plays first. Drag the handle to reorder, swipe left to remove.
 * Whatever you play moves to the top; when it finishes the next one starts.
 */
@Composable
fun QueueList(vm: AppViewModel, modifier: Modifier = Modifier) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    val continuing by vm.continueListening.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    var openId by remember { mutableStateOf<String?>(null) }
    var showAllContinue by remember { mutableStateOf(false) }

    // Local copy so a drag moves rows instantly; saved to the database when the drag ends.
    var local by remember { mutableStateOf(queue) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(queue) { if (!dragging) local = queue }

    if (local.isEmpty() && continuing.isEmpty()) {
        EmptyMessage(
            "Queue is empty",
            "Swipe right on a new episode, or use Play next / Play last in any episode's details.",
            modifier,
        )
        return
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val list = local.toMutableList()
        val fromIndex = list.indexOfFirst { it.episode.id == from.key }
        val toIndex = list.indexOfFirst { it.episode.id == to.key }
        if (fromIndex >= 0 && toIndex >= 0) {
            list.add(toIndex, list.removeAt(fromIndex))
            local = list
        }
    }

    // Phase 16: time left in the queue at each show's speed, and when it would be done.
    val library by vm.library.collectAsStateWithLifecycle()
    val defaults by vm.playbackDefaults.collectAsStateWithLifecycle()
    val speeds = remember(library) { library.associate { it.podcast.id to it.podcast.speed } }
    val timeLabel = com.earshot.app.ui.queueTimeLabel(
        com.earshot.app.ui.queueTimeLeftMs(local, { id -> speeds[id] ?: defaults.speed }, now.episodeId, now.positionMs),
    )

    LazyColumn(modifier = modifier, state = listState, contentPadding = PaddingValues(bottom = 8.dp)) {
        if (continuing.isEmpty() && local.isNotEmpty() && timeLabel.isNotEmpty()) {
            item(key = "queue-time") {
                Text(
                    "Queue · ${local.size} · $timeLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
                )
            }
        }
        // Phase 13: Continue listening (started, unfinished, not queued).
        if (continuing.isNotEmpty()) {
            item(key = "continue-header") {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Continue listening · ${continuing.size}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (continuing.size > 3) {
                        TextButton(onClick = { showAllContinue = !showAllContinue }) { Text(if (showAllContinue) "Show less" else "Show all") }
                    }
                }
            }
            items(if (showAllContinue) continuing else continuing.take(3), key = { "c:" + it.episode.id }) { item ->
                val isCurrent = now.episodeId == item.episode.id
                EpisodeRow(
                    item = item,
                    showPodcast = true,
                    isCurrent = isCurrent,
                    isPlaying = now.isPlaying,
                    onOpen = { openId = item.episode.id },
                    onPlay = { if (isCurrent) vm.player.togglePlay() else vm.play(item) },
                    onDownload = { vm.download(item) },
                    onOpenPodcast = { vm.openPodcastPage(item.episode.podcastId) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            }
            item(key = "queue-header") {
                Text(
                    if (local.isEmpty()) "Queue is empty" else "Queue · ${local.size}" + if (timeLabel.isNotEmpty()) " · $timeLabel" else "",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
                )
            }
        }
        items(local, key = { it.episode.id }) { item ->
            ReorderableItem(reorderState, key = item.episode.id) { isDragging ->
                val isCurrent = now.episodeId == item.episode.id
                Column(Modifier.background(if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)) {
                    SwipeRow(
                        startToEnd = null,
                        endToStart = SwipeAction(Icons.Filled.PlaylistRemove, "Remove", MaterialTheme.colorScheme.errorContainer) {
                            vm.removeFromQueue(item)
                        },
                    ) {
                        EpisodeRow(
                            item = item,
                            showPodcast = true,
                            isCurrent = isCurrent,
                            isPlaying = now.isPlaying,
                            onOpen = { openId = item.episode.id },
                            onPlay = { if (isCurrent) vm.player.togglePlay() else vm.play(item) },
                            onDownload = { vm.download(item) },
                            onOpenPodcast = { vm.openPodcastPage(item.episode.podcastId) },
                            leading = {
                                Box(
                                    Modifier
                                        .size(40.dp, 48.dp)
                                        .draggableHandle(
                                            onDragStarted = { dragging = true },
                                            onDragStopped = {
                                                dragging = false
                                                vm.reorderQueue(local.map { it.episode.id })
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.DragHandle,
                                        contentDescription = "Drag to reorder",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }
    EpisodeSheetFor(vm, openId?.let { id -> local.firstOrNull { it.episode.id == id } ?: continuing.firstOrNull { it.episode.id == id } }) { openId = null }
}

class SwipeAction(val icon: ImageVector, val label: String, val color: Color, val onSwipe: () -> Unit)

/** A row that can be swiped away left and/or right; the action runs once the swipe completes. */
@Composable
private fun SwipeRow(
    startToEnd: SwipeAction?,
    endToStart: SwipeAction?,
    content: @Composable () -> Unit,
) {
    // Not saved with the list (PR D, test B3): the list keeps each row's saved state by its key, so after
    // a swipe and Undo the episode came back already "swiped" and was removed again, over and over.
    var holder: androidx.compose.material3.SwipeToDismissBoxState? = null
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.runtime.saveable.LocalSaveableStateRegistry provides null) {
        holder = rememberSwipeToDismissBoxState()
    }
    val state = holder!!
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = startToEnd != null,
        enableDismissFromEndToStart = endToStart != null,
        backgroundContent = {
            val action = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> startToEnd
                SwipeToDismissBoxValue.EndToStart -> endToStart
                SwipeToDismissBoxValue.Settled -> null
            }
            if (action != null) {
                Row(
                    Modifier.fillMaxSize().background(action.color).padding(horizontal = 20.dp),
                    horizontalArrangement = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(action.icon, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(action.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        onDismiss = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> startToEnd?.onSwipe?.invoke()
                SwipeToDismissBoxValue.EndToStart -> endToStart?.onSwipe?.invoke()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // The row normally disappears from the list; if it doesn't, slide it back.
            scope.launch { state.reset() }
        },
    ) {
        content()
    }
}

@Composable
private fun EmptyMessage(title: String, body: String, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
