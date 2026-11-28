package com.earshot.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.earshot.app.playback.NowPlaying
import com.earshot.app.playback.skipLengthName
import com.earshot.app.ui.theme.GlowAmber
import com.earshot.app.ui.theme.GlowOnAmber
import com.earshot.app.ui.theme.rememberArtColor

/** Glow's tinted page header: a band of [tint] fading into the background, with white text. */
@Composable
fun GlowHero(
    tint: Color,
    eyebrow: String?,
    title: String,
    summary: String?,
    actions: @Composable RowScope.() -> Unit = {},
    top: (@Composable RowScope.() -> Unit)? = null,
) {
    val bg = MaterialTheme.colorScheme.background
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to tint, 0.65f to tint.copy(alpha = 0.45f).compositeOver(bg), 1f to bg))
            .statusBarsPadding()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (top != null) top() else Spacer(Modifier.weight(1f))
            actions()
        }
        if (eyebrow != null) {
            Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (summary != null) {
            Text(summary, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.9f))
        }
    }
}

/** Glow's mini player: a rounded card tinted from the artwork, with skip buttons. */
@Composable
fun GlowMiniPlayer(
    now: NowPlaying,
    skipBackMs: Long,
    skipForwardMs: Long,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onOpenPodcast: (() -> Unit)? = null,
) {
    val tint = rememberArtColor(now.artworkUrl, now.podcastTitle)
    val card = MaterialTheme.colorScheme.surfaceContainerHigh
    val progress = if (now.durationMs > 0) (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.7f).compositeOver(card), card)))
            .drawBehind {
                drawRect(GlowAmber, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 2.dp.toPx()), size = Size(size.width * progress, 2.dp.toPx()))
            }
            .clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(
                now.artworkUrl,
                40,
                modifier = if (onOpenPodcast != null) Modifier.clickable(onClickLabel = "Open podcast", onClick = onOpenPodcast) else Modifier,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(now.title, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PodcastLink(now.podcastTitle, onOpenPodcast, modifier = Modifier.weight(1f, fill = false), color = Color.White.copy(alpha = 0.8f))
                    // Phase 16: time left at this speed, or "Buffering…".
                    val status = com.earshot.app.ui.miniPlayerStatus(now.isBuffering, now.positionMs, now.durationMs, now.speed)
                    if (status.isNotEmpty()) {
                        Text(" · $status", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
                    }
                }
            }
            IconButton(onClick = onSkipBack) {
                Icon(Icons.Filled.Replay, contentDescription = "Back " + skipLengthName(skipBackMs), tint = Color.White)
            }
            if (now.isBuffering) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = GlowAmber)
                }
            } else {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(GlowAmber).clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (now.isPlaying) "Pause" else "Play",
                        tint = GlowOnAmber,
                    )
                }
            }
            IconButton(onClick = onSkipForward) {
                Icon(
                    Icons.Filled.Replay,
                    contentDescription = "Forward " + skipLengthName(skipForwardMs),
                    tint = Color.White,
                    modifier = Modifier.scale(scaleX = -1f, scaleY = 1f),
                )
            }
        }
    }
}

/** Glow's bottom bar: the five library sections (see Tabs), on every screen. [selected] is -1 off the library. */
@Composable
fun GlowNavBar(selected: Int, queueCount: Int, newCount: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        val colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val items = listOf(
            Triple("Queue", Icons.AutoMirrored.Filled.PlaylistPlay, queueCount),
            Triple("New", Icons.Filled.Inbox, newCount),
            Triple("Browse", Icons.Filled.Explore, 0),
            Triple("Playlists", Icons.AutoMirrored.Filled.QueueMusic, 0),
            Triple("Podcasts", Icons.Filled.GridView, 0),
        )
        items.forEachIndexed { i, (label, icon, count) ->
            NavigationBarItem(
                selected = selected == i,
                onClick = { onSelect(i) },
                colors = colors,
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                icon = {
                    BadgedBox(badge = { if (count > 0) Badge(containerColor = GlowAmber, contentColor = GlowOnAmber) { Text(count.toString()) } }) {
                        Icon(icon, contentDescription = null)
                    }
                },
            )
        }
    }
}
