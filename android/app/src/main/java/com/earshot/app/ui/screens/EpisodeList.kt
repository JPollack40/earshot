package com.earshot.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        }
    }

    EpisodeSheetFor(vm, openId?.let { id -> episodes.firstOrNull { it.episode.id == id } }) { openId = null }
}

/** The episode detail sheet with every action wired to the view model. Shows nothing when [item] is null. */
@Composable
fun EpisodeSheetFor(vm: AppViewModel, item: EpisodeWithPodcast?, onDismiss: () -> Unit) {
    if (item == null) return
    val transcriptIds by vm.transcriptIds.collectAsStateWithLifecycle()
    val transcribing by vm.transcribing.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
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
        onArchive = { vm.archive(item) },
        hasTranscript = item.episode.id in transcriptIds,
        transcribing = transcribing[item.episode.id].let { it is TranscribeState.Waiting || it is TranscribeState.Running },
        bookmarkCount = bookmarks.count { it.episodeId == item.episode.id },
        onTranscript = { vm.navigate(Screen.TranscriptView(item.episode.id)) },
        onTranscribe = { vm.transcribeWithGemini(item.episode.id) },
    )
}
