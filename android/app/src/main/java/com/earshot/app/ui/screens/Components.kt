@file:OptIn(ExperimentalMaterial3Api::class)

package com.earshot.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.earshot.app.data.db.DownloadState
import com.earshot.app.data.db.EpisodeWithPodcast
import com.earshot.app.playback.NowPlaying
import com.earshot.app.ui.formatDate
import com.earshot.app.ui.formatDuration

@Composable
fun Artwork(url: String?, size: Int, modifier: Modifier = Modifier, corner: Int = 6) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(corner.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/**
 * The podcast's name as a link (phase 7): tapping it opens the podcast. Same colour as the rest
 * of the line, underlined so it reads as tappable.
 */
@Composable
fun PodcastLink(
    title: String,
    onOpenPodcast: (() -> Unit)?,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelSmall,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        title,
        style = style,
        color = color,
        textDecoration = if (onOpenPodcast != null) androidx.compose.ui.text.style.TextDecoration.Underline else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.then(
            if (onOpenPodcast != null) Modifier.clickable(onClickLabel = "Open podcast", onClick = onOpenPodcast) else Modifier,
        ),
    )
}

/** Second line under an episode title: "Podcast • Nov 24 • 1h 02m" or "… • 32m left". */
private fun episodeMeta(item: EpisodeWithPodcast, showPodcast: Boolean): String {
    val e = item.episode
    val timeText = when {
        e.played -> "Played"
        e.positionMs > 0 && e.durationMs > 0 -> formatDuration(e.durationMs - e.positionMs) + " left"
        else -> formatDuration(e.durationMs)
    }
    return listOfNotNull(
        item.podcastTitle.takeIf { showPodcast },
        com.earshot.app.data.feed.episodeBadge(e.season, e.episodeNo),
        formatDate(e.pubDate).takeIf { it.isNotEmpty() },
        timeText.takeIf { it.isNotEmpty() },
    ).joinToString(" • ")
}

/** Compact episode row: about 64dp tall so plenty fit on screen. */
@Composable
fun EpisodeRow(
    item: EpisodeWithPodcast,
    showPodcast: Boolean,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    /** Extra line under the title, e.g. the part of the show notes a search matched. */
    snippet: String? = null,
    /** Opens the episode's podcast (tapping its artwork or name); null where the row is already on that page. */
    onOpenPodcast: (() -> Unit)? = null,
) {
    val e = item.episode
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Opaque, so a swipe's coloured background only shows where the row has moved away.
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)
            .padding(start = if (leading != null) 0.dp else 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Artwork(
            item.artworkUrl,
            44,
            modifier = if (onOpenPodcast != null) Modifier.clickable(onClickLabel = "Open podcast", onClick = onOpenPodcast) else Modifier,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).alpha(if (e.played) 0.5f else 1f)) {
            Text(
                e.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showPodcast && onOpenPodcast != null) {
                // The podcast's name is its own tap target; the rest of the line opens the episode.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PodcastLink(item.podcastTitle, onOpenPodcast, Modifier.weight(1f, fill = false))
                    Text(
                        episodeMeta(item, showPodcast = false).let { if (it.isEmpty()) "" else " • $it" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            } else {
                Text(
                    episodeMeta(item, showPodcast),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (snippet != null) {
                Text(
                    snippet,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!e.played && e.positionMs > 0 && e.durationMs > 0) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (e.positionMs.toFloat() / e.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.width(80.dp).height(2.dp),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
        DownloadButton(item, onDownload)
        IconButton(onClick = onPlay) {
            Icon(
                if (isCurrent && isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isCurrent && isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun DownloadButton(item: EpisodeWithPodcast, onDownload: () -> Unit) {
    val e = item.episode
    when (e.downloadState) {
        DownloadState.QUEUED, DownloadState.DOWNLOADING -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (e.downloadState == DownloadState.DOWNLOADING && e.downloadProgress > 0) {
                CircularProgressIndicator(progress = { e.downloadProgress / 100f }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        DownloadState.DONE -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.DownloadDone, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        DownloadState.FAILED -> IconButton(onClick = onDownload) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = "Download failed, tap to retry", tint = MaterialTheme.colorScheme.error)
        }
        else -> IconButton(onClick = onDownload) {
            Icon(Icons.Outlined.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Bottom sheet with full show notes and actions for one episode. */
@Composable
fun EpisodeSheet(
    item: EpisodeWithPodcast,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onTogglePlayed: () -> Unit,
    onQueueNext: () -> Unit,
    onQueueLast: () -> Unit,
    onRemoveFromQueue: () -> Unit,
    onArchive: () -> Unit,
    hasTranscript: Boolean = false,
    transcribing: Boolean = false,
    bookmarkCount: Int = 0,
    onTranscript: () -> Unit = {},
    onTranscribe: () -> Unit = {},
    onOpenPodcast: (() -> Unit)? = null,
    /** Phase 7b: shown only on podcast pages, and only when there's something to mark. */
    onMarkOlder: (() -> Unit)? = null,
    onMarkNewer: (() -> Unit)? = null,
) {
    val e = item.episode
    val notes = remember(e.description) { AnnotatedString.fromHtml(e.description) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val open = onOpenPodcast?.let { { onDismiss(); it() } }
                Artwork(
                    item.artworkUrl,
                    56,
                    modifier = if (open != null) Modifier.clickable(onClickLabel = "Open podcast", onClick = open) else Modifier,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.titleMedium)
                    if (open != null) {
                        PodcastLink(item.podcastTitle, open, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        episodeMeta(item, showPodcast = open == null),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // podcast:person on the episode (guests and hosts), phase 10.
                    val people = remember(e.personsJson) { com.earshot.app.data.feed.Persons.parse(e.personsJson) }
                    if (people.isNotEmpty()) {
                        Text(
                            com.earshot.app.data.feed.Persons.summary(people),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onPlay(); onDismiss() }) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play")
                }
                if (e.downloadState == DownloadState.DONE) {
                    OutlinedButton(onClick = onDeleteDownload) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Remove download")
                    }
                } else if (e.downloadState == DownloadState.NONE || e.downloadState == DownloadState.FAILED) {
                    OutlinedButton(onClick = onDownload) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Download")
                    }
                }
                OutlinedButton(onClick = onTogglePlayed) {
                    Icon(if (e.played) Icons.Outlined.RadioButtonUnchecked else Icons.Filled.CheckCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (e.played) "Unplayed" else "Played")
                }
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onQueueNext(); onDismiss() }) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play next")
                }
                if (e.queuePosition != null) {
                    OutlinedButton(onClick = { onRemoveFromQueue(); onDismiss() }) {
                        Icon(Icons.Filled.PlaylistRemove, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Remove from queue")
                    }
                } else {
                    OutlinedButton(onClick = { onQueueLast(); onDismiss() }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Play last")
                    }
                }
                if (e.inInbox) {
                    OutlinedButton(onClick = { onArchive(); onDismiss() }) {
                        Icon(Icons.Filled.Archive, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Archive")
                    }
                }
            }
            if (onMarkOlder != null || onMarkNewer != null) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The list is newest first, so newer episodes are above this one and older below.
                    if (onMarkNewer != null) {
                        OutlinedButton(onClick = onMarkNewer) {
                            Icon(Icons.Filled.DoneAll, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Mark newer played")
                        }
                    }
                    if (onMarkOlder != null) {
                        OutlinedButton(onClick = onMarkOlder) {
                            Icon(Icons.Filled.DoneAll, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Mark older played")
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onTranscript(); onDismiss() }) {
                    Icon(Icons.AutoMirrored.Outlined.Subject, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when {
                            transcribing -> "Transcribing…"
                            bookmarkCount > 0 -> "Transcript · $bookmarkCount bookmark${if (bookmarkCount == 1) "" else "s"}"
                            hasTranscript || e.transcriptUrl != null -> "Transcript"
                            else -> "No transcript"
                        },
                    )
                }
                // Cloud transcription only when asked, and only when the feed has no transcript of its own.
                if (!hasTranscript && e.transcriptUrl == null && !transcribing) {
                    OutlinedButton(onClick = { onTranscribe(); onDismiss() }) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Transcribe")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(notes, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Slim now-playing bar above the system nav bar. */
@Composable
fun MiniPlayer(now: NowPlaying, onOpen: () -> Unit, onTogglePlay: () -> Unit, onOpenPodcast: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = { if (now.durationMs > 0) (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(
                    now.artworkUrl,
                    40,
                    modifier = if (onOpenPodcast != null) Modifier.clickable(onClickLabel = "Open podcast", onClick = onOpenPodcast) else Modifier,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(now.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    PodcastLink(now.podcastTitle, onOpenPodcast)
                }
                if (now.isBuffering) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = onTogglePlay) {
                        Icon(
                            if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (now.isPlaying) "Pause" else "Play",
                        )
                    }
                }
            }
        }
    }
}
