@file:OptIn(ExperimentalMaterial3Api::class)

package com.earshot.app.ui.screens

import androidx.core.net.toUri
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.earshot.app.data.Settings
import com.earshot.app.data.StorageCap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.playback.skipLengthName
import com.earshot.app.ui.AppViewModel
import com.earshot.app.ui.formatSaved
import com.earshot.app.ui.formatSpeed

@Composable
fun SettingsScreen(vm: AppViewModel) {
    // Phase 22: Settings search (shows only the groups that match).
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shown = remember(query) { com.earshot.app.ui.SettingsSearch.groups(query) }
    val show = { group: String -> group in shown }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        androidx.compose.material3.TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search settings") },
                            singleLine = true,
                            colors = androidx.compose.material3.TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Settings")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(
                            if (searching) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searching) "Close search" else "Search settings",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            val divider = @Composable { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp) }
            if (shown.isEmpty()) {
                Text(
                    "No settings match \"$query\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (show("Sync")) {
                AccountSection(vm)
                divider()
            }

            if (show("Appearance")) {
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
                // Phase 22
                val black by vm.trueBlack.collectAsStateWithLifecycle()
                SettingRow(
                    title = "True black",
                    subtitle = "Pure black behind everything. Saves a little battery on this phone's screen.",
                    onClick = { vm.setTrueBlack(!black) },
                ) {
                    Switch(checked = black, onCheckedChange = vm::setTrueBlack)
                }
                divider()
                val textSize by vm.textSize.collectAsStateWithLifecycle()
                ChoiceRow(
                    "Text size",
                    "Comfortable makes all text a little bigger, on top of the phone's own font size.",
                    textSize,
                    listOf(Settings.TEXT_COMPACT, Settings.TEXT_COMFORTABLE),
                    { if (it == Settings.TEXT_COMFORTABLE) "Comfortable" else "Compact" },
                ) { vm.setTextSize(it) }
                divider()
                val swipes by vm.inboxSwipes.collectAsStateWithLifecycle()
                ChoiceRow(
                    "New Episodes: swipe right",
                    null,
                    swipes.first,
                    listOf(Settings.SWIPE_QUEUE_LAST, Settings.SWIPE_QUEUE_NEXT),
                    { if (it == Settings.SWIPE_QUEUE_NEXT) "Play next" else "End of queue" },
                ) { vm.setInboxSwipes(it, swipes.second) }
                divider()
                ChoiceRow(
                    "New Episodes: swipe left",
                    null,
                    swipes.second,
                    listOf(Settings.SWIPE_ARCHIVE, Settings.SWIPE_MARK_PLAYED),
                    { if (it == Settings.SWIPE_MARK_PLAYED) "Mark played" else "Archive" },
                ) { vm.setInboxSwipes(swipes.first, it) }
                divider()
            }

            if (show("Playback")) {
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
                OutputProfilesSetting() // phase 20 (C1)
                divider()
                // Phase 20 (C5)
                val ctx = androidx.compose.ui.platform.LocalContext.current
                var handoff by remember { mutableStateOf(com.earshot.app.data.Handoff.enabled(ctx)) }
                SettingRow(
                    title = "Continue between phone and web",
                    subtitle = "Starting an episode here pauses it in the web player (and the other way round), and opening the app offers Continue here. Needs sign-in.",
                    onClick = { handoff = !handoff; com.earshot.app.data.Handoff.setEnabled(ctx, handoff) },
                ) {
                    Switch(checked = handoff, onCheckedChange = { handoff = it; com.earshot.app.data.Handoff.setEnabled(ctx, it) })
                }
                divider()
                val rewind by vm.resumeRewind.collectAsStateWithLifecycle()
                SettingRow(
                    title = "Rewind a little after a pause",
                    subtitle = "On resuming, back up 2 s (10 s after an hour or more), and at least 3 s after a call or navigation prompt. Phone and car.",
                    onClick = { vm.updateResumeRewind(!rewind) },
                ) {
                    Switch(checked = rewind, onCheckedChange = vm::updateResumeRewind)
                }
                divider()
                // Phase 24 (lab)
                val labCtx = androidx.compose.ui.platform.LocalContext.current
                val labVersion by com.earshot.app.playback.SpeakerLab.version.collectAsStateWithLifecycle()
                val labOn = remember(labVersion) { com.earshot.app.playback.SpeakerLab.enabled(labCtx) }
                SettingRow(
                    title = "Per-speaker controls (lab)",
                    subtitle = "Adds Speaker levels to a transcript's menu: make a quiet guest louder or a slow speaker faster in that episode. Needs a transcript with speakers (Transcribe).",
                    onClick = { com.earshot.app.playback.SpeakerLab.setEnabled(labCtx, !labOn) },
                ) {
                    Switch(checked = labOn, onCheckedChange = { com.earshot.app.playback.SpeakerLab.setEnabled(labCtx, it) })
                }
                divider()
                SleepScheduleSetting(vm.sleepSchedule, vm::updateSleepSchedule)
                divider()
                // Phase 19 (B5): carry on from a playlist when the queue runs out.
                val playlists by vm.playlists.collectAsStateWithLifecycle()
                val live = playlists.filter { !it.deleted }
                ChoiceRow(
                    "When the queue ends",
                    "Carry on with the next unplayed episode of a playlist (it joins the queue). Phone and car.",
                    vm.queueEndPlaylistId?.takeIf { id -> live.any { it.id == id } },
                    listOf<String?>(null) + live.map { it.id },
                    label = { id -> if (id == null) "Stop" else live.firstOrNull { it.id == id }?.name ?: "Stop" },
                    onPick = vm::updateQueueEndPlaylist,
                )
                divider()
            }

            if (show("Time saved")) {
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
            }

            if (show("Automation")) {
                val tidy by vm.tidyDefaults.collectAsStateWithLifecycle()
                TidySettings(tidy, vm::updateTidyDefaults)
                divider()
            }

            if (show("Downloads")) {
                SectionHeader("Downloads")
                ChoiceRow(
                    "Download over",
                    if (vm.downloadWifiOnly) {
                        "Downloads wait for Wi-Fi. Streaming still works on any network."
                    } else {
                        "Downloads use Wi-Fi or mobile data."
                    },
                    vm.downloadWifiOnly,
                    listOf(false, true),
                    { if (it) "Wi-Fi only" else "Any network" },
                ) { vm.updateDownloadWifiOnly(it) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                StorageCapSetting(vm.storageCapGb, vm::updateStorageCapGb)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                DeleteAllDownloads(vm)
                divider()
            }

            if (show("Background")) {
                SectionHeader("Background")
                KeepRunningRow()
                divider()
            }

            if (show("Notifications")) {
                SectionHeader("Notifications")
                ChoiceRow(
                    "New episodes",
                    if (vm.pushConfigured) {
                        "The server checks for new episodes every 15 minutes and notifies this phone, with a Play button."
                    } else {
                        "Not set up yet: this build has no Firebase configuration (see the phase 10 notes)."
                    },
                    vm.newEpisodeNotify,
                    listOf(Settings.NOTIFY_ALL, Settings.NOTIFY_PINNED, Settings.NOTIFY_OFF),
                    { when (it) { Settings.NOTIFY_ALL -> "All podcasts"; Settings.NOTIFY_PINNED -> "Pinned only"; else -> "Off" } },
                ) { vm.updateNewEpisodeNotify(it) }
                divider()
            }

            if (show("AI from transcripts")) {
                SectionHeader("AI from transcripts")
                val adSkip by vm.adSkip.collectAsStateWithLifecycle()
                ChoiceRow(
                    "Found ads",
                    "In the player, AI > Find ads lists an episode's ads. Then they're skipped, or a Skip ad button shows.",
                    adSkip,
                    listOf(com.earshot.app.data.ai.AdSkip.AUTO, com.earshot.app.data.ai.AdSkip.BUTTON, com.earshot.app.data.ai.AdSkip.OFF),
                    { com.earshot.app.data.ai.AdSkip.label(it) },
                ) { vm.updateAdSkip(it) }
                divider()
            }

            if (show("Car, backup and updates")) {
                ShareSettings(vm, divider)
            }

            if (show("About")) {
                SectionHeader("About")
                Text(
                    "Earshot ${com.earshot.app.BuildConfig.VERSION_NAME} (${com.earshot.app.BuildConfig.VERSION_CODE}, " +
                        "${com.earshot.app.BuildConfig.BUILD_TYPE})",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                androidx.compose.material3.TextButton(
                    onClick = { vm.showWhatsNew(manual = true) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("What's new in this version") }
                // Phase 16: a note to Serena with a recent log, previewed before sending.
                var reporting by remember { mutableStateOf(false) }
                androidx.compose.material3.TextButton(
                    onClick = { reporting = true },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("Report a problem") }
                if (reporting) ReportProblemDialog(vm, onDismiss = { reporting = false })
            }
        }
    }
}

/**
 * Phase 14b: "Keep Earshot running". Shows whether Android's battery optimisation can put Earshot to
 * sleep (which can stop downloads, the AI sweep and transcription in the background) and opens the
 * system prompt to allow it. Nothing asks at app start (Serena). Rechecked each time Settings shows.
 */
@Composable
private fun KeepRunningRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        exempt = isIgnoringBatteryOptimizations(context)
        onPauseOrDispose {}
    }
    SettingRow(
        title = "Keep Earshot running",
        subtitle = if (exempt) {
            "On. Android's battery saving won't put Earshot to sleep. Tap to see the setting."
        } else {
            "Android's battery saving can put Earshot to sleep, which stops downloads, the AI sweep and " +
                "transcripts in the background. Tap to allow Earshot to run."
        },
        onClick = { openBatterySettings(context, exempt) },
    ) {
        Text(
            if (exempt) "On" else "Allow",
            style = MaterialTheme.typography.labelLarge,
            color = if (exempt) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Not exempt: Android's own "Let app always run in background?" prompt. Already exempt (or the
 * prompt isn't available): the app's info page, where Battery shows Unrestricted and can be changed.
 */
private fun openBatterySettings(context: android.content.Context, exempt: Boolean) {
    val pkg = "package:${context.packageName}".toUri()
    val tries = buildList {
        if (!exempt) add(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
        add(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        add(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
    for (intent in tries) {
        val started = runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (started) return
    }
}

/** Phase 14: deletes every downloaded episode at once, after a confirmation. */
@Composable
private fun DeleteAllDownloads(vm: AppViewModel) {
    var refresh by remember { mutableIntStateOf(0) }
    var usage by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(refresh) {
        usage = vm.downloadsUsage()
        cacheBytes = vm.streamCacheBytes()
    }
    var confirm by remember { mutableStateOf(false) }
    val (count, bytes) = usage ?: (0 to 0L)
    SettingRow(
        title = "Delete all downloads",
        subtitle = when {
            usage == null -> "Frees the space downloaded episodes take."
            count == 0 && bytes == 0L -> "No downloads right now."
            else -> "$count ${if (count == 1) "episode" else "episodes"}, ${formatBytes(bytes)}. Your queue and progress stay."
        } + if (cacheBytes >= 1_000_000L) " Also clears ${formatBytes(cacheBytes)} of streamed audio kept for rewinds." else "",
        onClick = { confirm = true },
    ) {}
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete all downloads?") },
            text = {
                Text(
                    "Deletes every downloaded episode file" + (if (count > 0) " ($count, ${formatBytes(bytes)})" else "") +
                        ", and the streamed audio kept for rewinds and resume" +
                        ". Episodes stay in your library and queue, and stream or download again when played.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirm = false
                    vm.deleteAllDownloads { refresh++ }
                }) { Text("Delete all") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Cancel") }
            },
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> "${bytes / 1_000_000} MB"
    else -> "${(bytes / 1_000).coerceAtLeast(if (bytes > 0) 1 else 0)} KB"
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

/** Phase 17: the most space downloads may take. */
@Composable
private fun StorageCapSetting(gb: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingRow(
        title = "Storage limit for downloads",
        subtitle = "Over it, downloads not in your queue go first (oldest first). Queued episodes are kept, " +
            "but new ones aren't downloaded automatically until there's room.",
        onClick = { open = true },
    ) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(StorageCap.label(gb), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                StorageCap.CHOICES_GB.forEach { g ->
                    DropdownMenuItem(
                        text = { Text(StorageCap.label(g), fontWeight = if (g == gb) FontWeight.Bold else null) },
                        onClick = {
                            onChange(g)
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
