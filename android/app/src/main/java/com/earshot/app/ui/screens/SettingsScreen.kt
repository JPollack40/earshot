@file:OptIn(ExperimentalMaterial3Api::class)

package com.earshot.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.earshot.app.data.Settings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.playback.skipLengthName
import com.earshot.app.ui.AppViewModel
import com.earshot.app.ui.formatSaved
import com.earshot.app.ui.formatSpeed

@Composable
fun SettingsScreen(vm: AppViewModel) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            val divider = @Composable { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp) }
            AccountSection(vm)
            divider()

            val theme by vm.appTheme.collectAsStateWithLifecycle()
            SectionHeader("Appearance")
            ChoiceRow(
                "Theme",
                "Glow: artwork colours and larger titles. Classic: the original look. This phone only.",
                theme,
                listOf(Settings.THEME_GLOW, Settings.THEME_CLASSIC),
                { if (it == Settings.THEME_GLOW) "Glow" else "Classic" },
            ) { vm.setAppTheme(it) }
            divider()

            val d by vm.playbackDefaults.collectAsStateWithLifecycle()
            SectionHeader("Playback")
            Text(
                "Defaults for every podcast. A podcast can have its own (podcast page, sliders icon).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            ChoiceRow("Speed", null, d.speed, Settings.SPEED_CHOICES, ::formatSpeed) { s ->
                vm.updatePlaybackDefaults { it.copy(speed = s) }
            }
            divider()
            SettingRow(
                title = "Smart Speed",
                subtitle = "Shortens silences in speech.",
                onClick = { vm.updatePlaybackDefaults { it.copy(smartSpeed = !it.smartSpeed) } },
            ) {
                Switch(checked = d.smartSpeed, onCheckedChange = { b -> vm.updatePlaybackDefaults { it.copy(smartSpeed = b) } })
            }
            divider()
            SettingRow(
                title = "Voice Boost",
                subtitle = "Evens out loudness and lifts quiet voices.",
                onClick = { vm.updatePlaybackDefaults { it.copy(voiceBoost = !it.voiceBoost) } },
            ) {
                Switch(checked = d.voiceBoost, onCheckedChange = { b -> vm.updatePlaybackDefaults { it.copy(voiceBoost = b) } })
            }
            divider()
            ChoiceRow("Skip back", "Also: long-press the button in the player.", d.skipBackMs, Settings.SKIP_CHOICES_MS, ::skipLengthName) { ms ->
                vm.updatePlaybackDefaults { it.copy(skipBackMs = ms) }
            }
            divider()
            ChoiceRow("Skip forward", null, d.skipForwardMs, Settings.SKIP_CHOICES_MS, ::skipLengthName) { ms ->
                vm.updatePlaybackDefaults { it.copy(skipForwardMs = ms) }
            }
            divider()

            val saved by vm.timeSaved.collectAsStateWithLifecycle()
            SectionHeader("Time saved")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(formatSaved(saved.totalMs), style = MaterialTheme.typography.titleLarge)
                Text(
                    "${formatSaved(saved.bySpeedMs)} from speed · ${formatSaved(saved.bySmartSpeedMs)} from Smart Speed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            divider()

            val tidy by vm.tidyDefaults.collectAsStateWithLifecycle()
            TidySettings(tidy, vm::updateTidyDefaults)
            divider()

            SectionHeader("Downloads")
            SettingRow(
                title = "Download queued episodes",
                subtitle = "Starts a download whenever an episode is added to the queue.",
                onClick = { vm.updateAutoDownloadQueued(!vm.autoDownloadQueued) },
            ) {
                Switch(checked = vm.autoDownloadQueued, onCheckedChange = vm::updateAutoDownloadQueued)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            SettingRow(
                title = "Delete downloads when finished",
                subtitle = "Frees space as soon as an episode is played to the end or marked played.",
                onClick = { vm.updateDeleteWhenFinished(!vm.deleteWhenFinished) },
            ) {
                Switch(checked = vm.deleteWhenFinished, onCheckedChange = vm::updateDeleteWhenFinished)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            ExpirySetting(vm.expireDays, vm::updateExpireDays)
            divider()

            SectionHeader("About")
            Text(
                "Earshot ${com.earshot.app.BuildConfig.VERSION_NAME} (${com.earshot.app.BuildConfig.VERSION_CODE}, " +
                    "${com.earshot.app.BuildConfig.BUILD_TYPE})",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ExpirySetting(days: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingRow(
        title = "Delete unfinished downloads after",
        subtitle = "Counted from download, or from when you last played it.",
        onClick = { open = true },
    ) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(expiryLabel(days), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Settings.EXPIRE_DAY_CHOICES.forEach { d ->
                    DropdownMenuItem(
                        text = { Text(expiryLabel(d), fontWeight = if (d == days) FontWeight.Bold else null) },
                        onClick = {
                            onChange(d)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

private fun expiryLabel(days: Int) = if (days == 0) "Never" else "$days days"

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
internal fun SettingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}
