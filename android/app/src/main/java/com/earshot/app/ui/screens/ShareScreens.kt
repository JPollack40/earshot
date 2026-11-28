@file:OptIn(ExperimentalMaterial3Api::class)

package com.earshot.app.ui.screens

import androidx.core.net.toUri
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.earshot.app.BuildConfig
import com.earshot.app.data.Backup
import com.earshot.app.data.Updates
import com.earshot.app.ui.AppViewModel
import java.text.DateFormat
import java.util.Date

/** "Update available" banner (phase 9), shown above the bottom bar. */
@Composable
fun UpdateBanner(vm: AppViewModel) {
    val u = vm.update ?: return
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Earshot ${u.version} is available", style = MaterialTheme.typography.titleSmall)
            Text(
                "You have ${BuildConfig.VERSION_NAME}. Download it, open the file, and Android installs it over this one (your library stays).",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val url = u.apkUrl.ifBlank { u.pageUrl }
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                }) { Text("Download") }
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, u.pageUrl.toUri())) } }) {
                    Text("What's new")
                }
                TextButton(onClick = vm::dismissUpdate) { Text("Not now") }
            }
        }
    }
}

/** Settings rows for updates, backup and restore, and the car guide (phase 9). */
@Composable
fun ShareSettings(vm: AppViewModel, divider: @Composable () -> Unit) {
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.inspectBackup(uri)
    }

    SectionHeader("Android Auto")
    SettingRow(
        title = "Set up for your car",
        subtitle = "Steps to make Earshot show up in Android Auto.",
        onClick = { vm.navigate(com.earshot.app.ui.Screen.CarSetup) },
    ) {}
    divider()

    SectionHeader("Backup")
    SettingRow(
        title = "Back up everything",
        subtitle = "Podcasts, pins, queue, listening progress, transcripts, bookmarks, playlists and settings, in one file. Keep it somewhere safe (Drive, Files).",
        onClick = { if (!vm.backupBusy) save.launch(Backup.fileName()) },
    ) {}
    divider()
    SettingRow(
        title = "Restore from a backup",
        subtitle = "Replaces everything on this phone with the backup, then restarts Earshot.",
        onClick = { if (!vm.backupBusy) open.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
    ) {}
    divider()

    SectionHeader("Updates")
    SettingRow(
        title = if (vm.updateChecking) "Checking…" else "Check for updates",
        subtitle = if (Updates.automatic) "Release builds also check once a day." else "This is a test build; it doesn't check by itself.",
        onClick = { vm.checkForUpdate(manual = true) },
    ) {}
    divider()

    vm.restoreCandidate?.let { info ->
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(info.createdAt))
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Made $date with Earshot ${info.appVersion}: ${info.podcasts} podcasts, ${info.episodes} episodes.\n\n" +
                        "Everything on this phone is replaced by the backup and Earshot restarts. Downloads aren't in the backup; " +
                        "they download again. A phone that isn't signed in yet can sign in afterwards (Settings, Sync).",
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmRestore) { Text("Restore and restart") } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } },
        )
    }
}

/** "Set up for your car" (phase 9): Android Auto hides apps that weren't installed from the Play Store until allowed. */
@Composable
fun CarSetupScreen(vm: AppViewModel) {
    val context = LocalContext.current
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Set up for your car") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Android Auto only lists apps from the Play Store until you allow others. Earshot comes from GitHub, " +
                    "so do this once on your phone (parked, not driving). It takes about two minutes.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Step(1, "Open Android Auto's settings", "Tap the button below, then \"Additional settings in the app\". (Or: phone Settings, search for Android Auto.)")
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:com.google.android.projection.gearhead".toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Text("Open Android Auto app info") }
            Step(2, "Turn on developer settings", "Scroll to the bottom and tap \"Version\" (or \"Version and permission info\") about 10 times, until it asks \"Allow development settings?\". Tap OK.")
            Step(3, "Allow unknown sources", "Tap ⋮ (top right), then \"Developer settings\". Scroll down and tick \"Unknown sources\".")
            Step(4, "Show Earshot in the car", "Go back to Android Auto's settings, open \"Customize launcher\" (\"Customise launcher\") and make sure Earshot is ticked. You can drag it near the top.")
            Step(5, "Connect to the car", "Plug in or connect wirelessly. Earshot appears in the app list. If it doesn't, unplug, force stop Android Auto (its app info, Force stop) and connect again.")
            Step(6, "Try it", "Open Earshot in the car: Queue, New Episodes, Playlists and Podcasts are the tabs. Voice works too: \"Hey Google, play the latest <podcast> on Earshot\".")
            Text(
                "Sync: if you have an Earshot account, sign in on the phone (Settings, Sync) before driving, so your queue is there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Step(n: Int, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("$n.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
