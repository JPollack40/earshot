@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.earshot.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.earshot.app.data.PlaybackDefaults
import com.earshot.app.data.PlaybackPrefs
import com.earshot.app.data.Settings
import com.earshot.app.data.db.Podcast
import com.earshot.app.playback.skipLengthName
import com.earshot.app.playback.skipLengthShort
import com.earshot.app.ui.formatSpeed

/** Player skip button: tap to skip, long-press to choose the length. */
@Composable
fun SkipButton(lengthMs: Long, forward: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onLongClickLabel = "Choose skip length",
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Replay,
            contentDescription = (if (forward) "Forward " else "Back ") + skipLengthName(lengthMs),
            modifier = Modifier
                .size(40.dp)
                .scale(scaleX = if (forward) -1f else 1f, scaleY = 1f),
        )
        Text(
            skipLengthShort(lengthMs),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** "Just for <podcast>" checkbox row used in the player's dialogs. */
@Composable
private fun PodcastOnlyRow(podcast: Podcast?, checked: Boolean, onChange: (Boolean) -> Unit) {
    if (podcast == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text("Just for ${podcast.title}", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
    }
}

/** Long-press picker for one skip button's length. */
@Composable
fun SkipLengthDialog(
    forward: Boolean,
    currentMs: Long,
    podcast: Podcast?,
    podcastOnly: Boolean,
    onPick: (Long) -> Unit,
    onPodcastOnly: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (forward) "Skip forward" else "Skip back") },
        text = {
            Column {
                Settings.SKIP_CHOICES_MS.forEach { ms ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(ms); onDismiss() }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = ms == currentMs, onClick = { onPick(ms); onDismiss() })
                        Text(skipLengthName(ms), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (podcast != null) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    PodcastOnlyRow(podcast, podcastOnly, onPodcastOnly)
                    Text(
                        "Applies to both skip buttons. Without it, the change applies to every podcast without its own lengths.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Speed, Smart Speed and Voice Boost, from the player's speed chip. */
@Composable
fun AudioDialog(
    prefs: PlaybackPrefs,
    podcast: Podcast?,
    podcastOnly: Boolean,
    onSpeed: (Float) -> Unit,
    onSmartSpeed: (Boolean) -> Unit,
    onVoiceBoost: (Boolean) -> Unit,
    onPodcastOnly: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Speed", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Settings.SPEED_CHOICES.forEach { s ->
                        FilterChip(
                            selected = s == prefs.speed,
                            onClick = { onSpeed(s) },
                            label = { Text(formatSpeed(s)) },
                        )
                    }
                }
                SwitchLine("Smart Speed", "Shortens silences in speech.", prefs.smartSpeed, onSmartSpeed)
                SwitchLine("Voice Boost", "Evens out loudness and lifts quiet voices.", prefs.voiceBoost, onVoiceBoost)
                if (podcast != null) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    PodcastOnlyRow(podcast, podcastOnly, onPodcastOnly)
                    Text(
                        "Without it, changes apply to every podcast without its own settings.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SwitchLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A settings row whose value is picked from a dropdown. */
@Composable
fun <T> ChoiceRow(
    title: String,
    subtitle: String?,
    value: T,
    choices: List<T>,
    label: (T) -> String,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                choices.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(label(c), fontWeight = if (c == value) FontWeight.Bold else null) },
                        onClick = {
                            onPick(c)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

private fun onOff(b: Boolean) = if (b) "On" else "Off"

/** The podcast page's "Playback settings": each one is either the default or the podcast's own. */
@Composable
fun PodcastPlaybackDialog(
    podcast: Podcast,
    defaults: PlaybackDefaults,
    onChange: ((Podcast) -> Podcast) -> Unit,
    onDismiss: () -> Unit,
) {
    val divider = @Composable { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "For ${podcast.title}. \"Default\" follows the app's Settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                ChoiceRow(
                    "Speed", null, podcast.speed, listOf(null) + Settings.SPEED_CHOICES,
                    label = { it?.let(::formatSpeed) ?: "Default (${formatSpeed(defaults.speed)})" },
                    onPick = { v -> onChange { it.copy(speed = v) } },
                )
                divider()
                ChoiceRow(
                    "Smart Speed", "Shortens silences", podcast.smartSpeed, listOf(null, true, false),
                    label = { it?.let(::onOff) ?: "Default (${onOff(defaults.smartSpeed)})" },
                    onPick = { v -> onChange { it.copy(smartSpeed = v) } },
                )
                divider()
                ChoiceRow(
                    "Voice Boost", "Evens out loudness", podcast.voiceBoost, listOf(null, true, false),
                    label = { it?.let(::onOff) ?: "Default (${onOff(defaults.voiceBoost)})" },
                    onPick = { v -> onChange { it.copy(voiceBoost = v) } },
                )
                divider()
                ChoiceRow(
                    "Skip back", null, podcast.skipBackMs, listOf(null) + Settings.SKIP_CHOICES_MS,
                    label = { it?.let(::skipLengthName) ?: "Default (${skipLengthName(defaults.skipBackMs)})" },
                    onPick = { v -> onChange { it.copy(skipBackMs = v) } },
                )
                divider()
                ChoiceRow(
                    "Skip forward", null, podcast.skipForwardMs, listOf(null) + Settings.SKIP_CHOICES_MS,
                    label = { it?.let(::skipLengthName) ?: "Default (${skipLengthName(defaults.skipForwardMs)})" },
                    onPick = { v -> onChange { it.copy(skipForwardMs = v) } },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
