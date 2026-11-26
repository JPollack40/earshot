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

/** Episode list shared by the Latest tab and podcast pages, including the episode detail sheet. */
@Composable
fun EpisodeList(
    vm: AppViewModel,
    episodes: List<EpisodeWithPodcast>,
    showPodcast: Boolean,
    modifier: Modifier = Modifier,
    header: LazyListScope.() -> Unit = {},
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
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        }
    }

    val open = openId?.let { id -> episodes.firstOrNull { it.episode.id == id } }
    if (open != null) {
        EpisodeSheet(
            item = open,
            onDismiss = { openId = null },
            onPlay = { vm.play(open) },
            onDownload = { vm.download(open) },
            onDeleteDownload = { vm.deleteDownload(open) },
            onTogglePlayed = { vm.setPlayed(open, !open.episode.played) },
        )
    }
}
