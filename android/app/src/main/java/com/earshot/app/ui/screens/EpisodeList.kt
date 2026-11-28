package com.earshot.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.data.MarkRange
import com.earshot.app.data.db.EpisodeWithPodcast
import com.earshot.app.ui.AppViewModel
import com.earshot.app.ui.Screen
import com.earshot.app.data.transcript.TranscribeState

/** Episode list shared by the Latest tab and podcast pages, including the episode detail sheet. */
@Composable
fun EpisodeList(
    vm: AppViewModel,
    episodes: List<EpisodeWithPodcast>,
    showPodcast: Boolean,
    modifier: Modifier = Modifier,
    header: LazyListScope.() -> Unit = {},
    snippets: Map<String, String> = emptyMap(),
    /** The podcast's whole episode list, for "Mark older/newer played" (podcast pages only). */
    markRange: List<EpisodeWithPodcast>? = null,
) {
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    var openId by remember { mutableStateOf<String?>(null) }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 8.dp)) {
        header()
        items(episodes, key = { it.episode.id }) { item ->
            val isCurrent = now.episodeId == item.episode.id
            EpisodeRow(
                item = item,
                showPodcast = showPodcast,
                isCurrent = isCurrent,
                isPlaying = now.isPlaying,
                onOpen = { openId = item.episode.id },
                onPlay = { if (isCurrent) vm.player.togglePlay() else vm.play(item) },
                onDownload = { vm.download(item) },
                snippet = snippets[item.episode.id],
                onOpenPodcast = if (showPodcast) ({ vm.openPodcastPage(item.episode.podcastId) }) else null,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        }
    }

    EpisodeSheetFor(vm, openId?.let { id -> episodes.firstOrNull { it.episode.id == id } }, markRange) { openId = null }
}

/** The episode detail sheet with every action wired to the view model. Shows nothing when [item] is null. */
@Composable
fun EpisodeSheetFor(
    vm: AppViewModel,
    item: EpisodeWithPodcast?,
    markRange: List<EpisodeWithPodcast>? = null,
    onDismiss: () -> Unit,
) {
    if (item == null) return
    val transcriptIds by vm.transcriptIds.collectAsStateWithLifecycle()
    val transcribing by vm.transcribing.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    // Phase 7b: what "Mark older/newer played" would change (queued and loaded episodes are left alone).
    val older = remember(markRange, item, now.episodeId) {
        markRange?.let { all -> MarkRange.pick(all.map { it.episode }, item.episode, MarkRange.Direction.OLDER, now.episodeId) }
    }
    val newer = remember(markRange, item, now.episodeId) {
        markRange?.let { all -> MarkRange.pick(all.map { it.episode }, item.episode, MarkRange.Direction.NEWER, now.episodeId) }
    }
    var confirm by remember { mutableStateOf<MarkRange.Direction?>(null) }
    EpisodeSheet(
        item = item,
        onDismiss = onDismiss,
        onPlay = { vm.play(item) },
        onDownload = { vm.download(item) },
        onDeleteDownload = { vm.deleteDownload(item) },
        onTogglePlayed = { vm.setPlayed(item, !item.episode.played) },
        onQueueNext = { vm.queueNext(item) },
        onQueueLast = { vm.queueLast(item) },
        onRemoveFromQueue = { vm.removeFromQueue(item) },
        onMoveToTop = { vm.moveToTop(item) },
        onMoveToBottom = { vm.moveToBottom(item) },
        onTimestamp = { ms -> vm.playAt(item.episode.id, ms) },
        onArchive = { vm.archive(item) },
        hasTranscript = item.episode.id in transcriptIds,
        transcribing = transcribing[item.episode.id].let { it is TranscribeState.Waiting || it is TranscribeState.Running },
        bookmarkCount = bookmarks.count { it.episodeId == item.episode.id },
        onTranscript = { vm.navigate(Screen.TranscriptView(item.episode.id)) },
        onTranscribe = { vm.transcribeWithGemini(item.episode.id) },
        onOpenPodcast = { vm.openPodcastPage(item.episode.podcastId) },
        onMarkOlder = older?.takeIf { it.ids.isNotEmpty() }?.let { { confirm = MarkRange.Direction.OLDER } },
        onMarkNewer = newer?.takeIf { it.ids.isNotEmpty() }?.let { { confirm = MarkRange.Direction.NEWER } },
    )

    val direction = confirm
    val pick = when (direction) {
        MarkRange.Direction.OLDER -> older
        MarkRange.Direction.NEWER -> newer
        null -> null
    }
    if (direction != null && pick != null) {
        val n = pick.ids.size
        val word = if (direction == MarkRange.Direction.OLDER) "older" else "newer"
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Mark $n $word episode${if (n == 1) "" else "s"} played?") },
            text = {
                Text(
                    buildString {
                        append("Every unplayed episode of ${item.podcastTitle} released ")
                        append(if (direction == MarkRange.Direction.OLDER) "before" else "after")
                        append(" \"${item.episode.title}\" will be marked played.")
                        if (pick.skipped > 0) {
                            append(" ${pick.skipped} queued or playing episode${if (pick.skipped == 1) " is" else "s are"} left alone.")
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    vm.markPlayed(pick.ids)
                    onDismiss()
                }) { Text("Mark played") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
