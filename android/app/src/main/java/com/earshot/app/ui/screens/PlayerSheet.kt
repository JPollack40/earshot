@file:OptIn(ExperimentalMaterial3Api::class)

package com.earshot.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Canvas
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.earshot.app.playback.SleepTimer
import com.earshot.app.data.feed.SkippedChapters
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import com.earshot.app.ui.theme.LocalGlow
import com.earshot.app.ui.theme.rememberArtColor
import com.earshot.app.ui.AppViewModel
import com.earshot.app.ui.Screen
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.automirrored.outlined.Subject
import com.earshot.app.ui.formatClock
import com.earshot.app.ui.formatSaved
import com.earshot.app.ui.formatSpeed
import kotlinx.coroutines.launch

private val SLEEP_MINUTES = listOf(5, 15, 30, 45, 60, 90)

@Composable
fun PlayerSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val episode by vm.currentEpisode.collectAsStateWithLifecycle()
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val allBookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val episodeBookmarks = allBookmarks.filter { it.episodeId == now.episodeId }
    val sleep by vm.sleepTimer.collectAsStateWithLifecycle()
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val prefs by vm.currentPrefs.collectAsStateWithLifecycle()
    val podcast by vm.currentPodcast.collectAsStateWithLifecycle()
    var audioDialog by remember { mutableStateOf(false) }
    /** Which skip button's length picker is open: false = back, true = forward, null = none. */
    var skipDialog by remember { mutableStateOf<Boolean?>(null) }
    var sleepMenu by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var aiDialog by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var chaptersTop by remember { mutableIntStateOf(0) }

    val glow = LocalGlow.current
    val tint = rememberArtColor(now.artworkUrl, now.podcastTitle.ifEmpty { "earshot" })
    val sheetBg = MaterialTheme.colorScheme.surfaceContainerLow

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (glow) tint else BottomSheetDefaults.ContainerColor,
    ) {
        Column(
            Modifier
                // Glow: the sheet fades from the artwork colour into the background.
                .then(if (glow) Modifier.background(Brush.verticalGradient(0f to tint, 0.55f to tint.copy(alpha = 0.35f).compositeOver(sheetBg), 1f to sheetBg)) else Modifier)
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Tapping the artwork or the podcast's name opens the podcast (phase 7).
            val openPodcast: () -> Unit = { episode?.episode?.podcastId?.let(vm::openPodcastPage) }
            val playingChapter = chapters.lastOrNull { it.startMs <= (dragPosition?.toLong() ?: now.positionMs) }
            Artwork(
                playingChapter?.imageUrl ?: now.artworkUrl,
                if (glow) 280 else 220,
                corner = if (glow) 14 else 12,
                modifier = Modifier.clickable(onClickLabel = "Open podcast", onClick = openPodcast),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                now.title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            PodcastLink(now.podcastTitle, openPodcast, style = MaterialTheme.typography.labelLarge)

            val position = dragPosition?.toLong() ?: now.positionMs
            val currentChapter = chapters.lastOrNull { it.startMs <= position }
            if (currentChapter != null) {
                Spacer(Modifier.height(4.dp))
                val uri = LocalUriHandler.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(currentChapter.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    currentChapter.url?.let { link ->
                        IconButton(onClick = { runCatching { uri.openUri(link) } }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open chapter link", Modifier.size(16.dp))
                        }
                    }
                }
            }

            // ---- Scrubber ----
            Spacer(Modifier.height(8.dp))
            val duration = now.durationMs.coerceAtLeast(1L).toFloat()
            Box(Modifier.fillMaxWidth()) {
                Slider(
                    value = (dragPosition ?: now.positionMs.toFloat()).coerceIn(0f, duration),
                    onValueChange = { dragPosition = it },
                    onValueChangeFinished = {
                        dragPosition?.let { vm.player.seekTo(it.toLong()) }
                        dragPosition = null
                    },
                    valueRange = 0f..duration,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Glow: a tick on the track at each chapter start.
                if (glow && chapters.size > 1 && now.durationMs > 0) {
                    val marks = chapters.map { it.startMs.toFloat() / duration }.filter { it > 0f && it < 1f }
                    Canvas(Modifier.matchParentSize()) {
                        val inset = 10.dp.toPx()
                        val tick = Size(2.dp.toPx(), 10.dp.toPx())
                        marks.forEach { f ->
                            val x = inset + f * (size.width - 2 * inset)
                            drawRect(Color.White.copy(alpha = 0.55f), topLeft = Offset(x - tick.width / 2, size.height / 2 - tick.height / 2), size = tick)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(position), style = MaterialTheme.typography.labelSmall)
                Text(
                    if (now.durationMs > 0) "-" + formatClock(now.durationMs - position) else "",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            JumpBackChip(vm)
            SkipAdChip(vm)
            val adNow by vm.currentAd.collectAsStateWithLifecycle()

            // ---- Transport ----
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SkipButton(prefs.skipBackMs, forward = false, onClick = vm::skipBack, onLongClick = { skipDialog = false })
                FilledIconButton(
                    onClick = { vm.player.togglePlay() },
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    if (now.isBuffering) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (now.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                SkipButton(prefs.skipForwardMs, forward = true, onClick = vm::skipForward, onLongClick = { skipDialog = true }, skipAd = adNow != null)
            }
            val saved = episode?.episode?.timeSavedMs ?: 0
            if (saved >= 1_000) {
                Text(
                    "Saved ${formatSaved(saved)} on this episode",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Speed / sleep / notes / chapters ----
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                AssistChip(
                    onClick = { audioDialog = true },
                    label = { Text(formatSpeed(prefs.speed)) },
                    leadingIcon = { Icon(Icons.Filled.Speed, null, Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = prefs.smartSpeed,
                    onClick = { vm.changeAudio { it.copy(smartSpeed = !it.smartSpeed) } },
                    label = { Text("Smart Speed") },
                    leadingIcon = { Icon(Icons.Filled.Bolt, null, Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = prefs.voiceBoost,
                    onClick = { vm.changeAudio { it.copy(voiceBoost = !it.voiceBoost) } },
                    label = { Text("Voice Boost") },
                    leadingIcon = { Icon(Icons.Filled.RecordVoiceOver, null, Modifier.size(18.dp)) },
                )
                Box {
                    AssistChip(
                        onClick = { sleepMenu = true },
                        label = { Text(sleepLabel(sleep)) },
                        leadingIcon = { Icon(Icons.Filled.Bedtime, null, Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                        DropdownMenuItem(text = { Text("Off") }, onClick = { vm.player.setSleepTimer(null); sleepMenu = false })
                        SLEEP_MINUTES.forEach { m ->
                            DropdownMenuItem(text = { Text("$m minutes") }, onClick = { vm.player.setSleepTimer(m); sleepMenu = false })
                        }
                        DropdownMenuItem(text = { Text("End of episode") }, onClick = { vm.player.sleepAtEndOfEpisode(); sleepMenu = false })
                    }
                }
                AssistChip(
                    onClick = vm::bookmarkNow,
                    label = { Text(if (episodeBookmarks.isEmpty()) "Bookmark" else "Bookmark (${episodeBookmarks.size})") },
                    leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp)) },
                )
                now.episodeId?.let { id ->
                    AssistChip(
                        onClick = {
                            onDismiss()
                            vm.navigate(Screen.TranscriptView(id))
                        },
                        label = { Text("Transcript") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Subject, null, Modifier.size(18.dp)) },
                    )
                }
                if (episode?.episode?.description?.isNotBlank() == true) {
                    AssistChip(
                        onClick = { showNotes = true },
                        label = { Text("Notes") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Notes, null, Modifier.size(18.dp)) },
                    )
                }
                // Phase 11: summary, chapters and ads made from the transcript.
                if (episode != null) {
                    AssistChip(
                        onClick = { aiDialog = true },
                        label = { Text("AI") },
                        leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)) },
                    )
                }
                // Only once chapters have actually loaded; jumps down to the list.
                if (chapters.isNotEmpty()) {
                    AssistChip(
                        onClick = { scope.launch { scroll.animateScrollTo(chaptersTop) } },
                        label = { Text("Chapters (${chapters.size})") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(18.dp)) },
                    )
                }
            }

            // ---- Chapters ----
            if (chapters.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.onGloballyPositioned { chaptersTop = it.positionInParent().y.toInt() },
                )
                val skipped = episode?.episode?.skippedChapters.let { SkippedChapters.parse(it) }
                val skippedCount = chapters.count { c -> skipped.any { kotlin.math.abs(it - c.startMs) <= 1_000 } }
                Text(
                    buildString {
                        append("Chapters")
                        if (chapters.any { it.generated }) append(" · generated from the transcript")
                        if (skippedCount > 0) append(" · $skippedCount skipped")
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "Untick a chapter to skip it, here, in the notification and in the car.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
                val uri = LocalUriHandler.current
                chapters.forEach { ch ->
                    val active = ch == currentChapter
                    val isSkipped = skipped.any { kotlin.math.abs(it - ch.startMs) <= 1_000 }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.player.seekTo(ch.startMs) }
                            .background(if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                            .padding(vertical = 2.dp, horizontal = 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = !isSkipped,
                            onCheckedChange = { vm.setChapterSkipped(ch.startMs, !it) },
                            modifier = Modifier.size(36.dp),
                        )
                        if (ch.imageUrl != null) {
                            Artwork(ch.imageUrl, 32, corner = 4)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            formatClock(ch.startMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(52.dp),
                        )
                        Text(
                            ch.title,
                            style = MaterialTheme.typography.bodySmall.copy(
                                textDecoration = if (isSkipped) TextDecoration.LineThrough else null,
                            ),
                            color = when {
                                isSkipped -> MaterialTheme.colorScheme.onSurfaceVariant
                                active -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        ch.url?.let { link ->
                            IconButton(onClick = { runCatching { uri.openUri(link) } }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open chapter link", Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    val ep = episode
    if (showNotes) {
        EpisodeSheetFor(vm, ep) { showNotes = false }
    }
    if (aiDialog && ep != null) {
        AiDialog(vm, ep) { aiDialog = false }
    }

    val pod = podcast
    if (audioDialog) {
        with(vm) {
            AudioDialog(
                prefs = prefs,
                podcast = pod,
                podcastOnly = pod?.hasOwnAudio() == true,
                onSpeed = { s -> changeAudio { it.copy(speed = s) } },
                onSmartSpeed = { b -> changeAudio { it.copy(smartSpeed = b) } },
                onVoiceBoost = { b -> changeAudio { it.copy(voiceBoost = b) } },
                onPodcastOnly = ::setAudioJustForPodcast,
                onDismiss = { audioDialog = false },
            )
        }
    }
    skipDialog?.let { forward ->
        with(vm) {
            SkipLengthDialog(
                forward = forward,
                currentMs = if (forward) prefs.skipForwardMs else prefs.skipBackMs,
                podcast = pod,
                podcastOnly = pod?.hasOwnSkip() == true,
                onPick = { changeSkip(forward, it) },
                onPodcastOnly = ::setSkipJustForPodcast,
                onDismiss = { skipDialog = null },
            )
        }
    }
}


private fun sleepLabel(sleep: SleepTimer): String = when (sleep) {
    SleepTimer.Off -> "Sleep"
    SleepTimer.EndOfEpisode -> "End of episode"
    is SleepTimer.At -> {
        val minsLeft = ((sleep.endsAtMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1
        "${minsLeft}m"
    }
}
