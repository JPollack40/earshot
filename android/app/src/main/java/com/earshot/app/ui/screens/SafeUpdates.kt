package com.earshot.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.BuildConfig
import com.earshot.app.data.RemoteConfig
import com.earshot.app.data.Updates
import com.earshot.app.ui.AppViewModel

/**
 * Phase 16: shown at the top of every screen when Serena has set a minimum version above this release.
 * It can't be dismissed, but the app keeps working (Serena chose "warn only").
 */
@Composable
fun UpdateNeededBanner(vm: AppViewModel, minVersion: String) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp)) {
            Text("Please update Earshot", style = MaterialTheme.typography.titleSmall)
            Text(
                "This version (${BuildConfig.VERSION_NAME}) is out of date and may stop working properly. Install " +
                    "$minVersion or newer: your library, queue and downloads stay.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = {
                    val url = vm.update?.apkUrl?.ifBlank { null } ?: Updates.RELEASES_PAGE
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                }) { Text("Get the update") }
            }
        }
    }
}

/** Phase 16: Serena's note from app_config.banner, until dismissed (a new note shows again). */
@Composable
fun ConfigBanner() {
    val context = LocalContext.current
    val config by RemoteConfig.config.collectAsStateWithLifecycle()
    val text = config.banner ?: return
    var hidden by remember(text) { mutableStateOf(RemoteConfig.bannerDismissed(context, text)) }
    if (hidden) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                RemoteConfig.dismissBanner(context, text)
                hidden = true
            }) { Text("OK") }
        }
    }
}

/** Phase 16: Settings > About > Report a problem. Shows exactly what will be sent before sending. */
@Composable
fun ReportProblemDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf("") }
    var includeLog by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report a problem") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Goes to Serena only. It includes your note, the app and Android version, your phone's model and, " +
                        "if ticked, Earshot's recent log (no passwords; it can include episode and podcast names).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(4_000) },
                    label = { Text("What happened?") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeLog, onCheckedChange = { includeLog = it; preview = null })
                    Text("Include the recent log", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { vm.previewProblemReport(includeLog) { preview = it } }) {
                    Text(if (preview == null) "Preview what's sent" else "Refresh preview")
                }
                preview?.let {
                    SelectionContainer {
                        Text(
                            it,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = note.isNotBlank() && !vm.reportSending,
                onClick = { vm.sendProblemReport(note, includeLog, onDone = onDismiss) },
            ) { Text(if (vm.reportSending) "Sending…" else "Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
