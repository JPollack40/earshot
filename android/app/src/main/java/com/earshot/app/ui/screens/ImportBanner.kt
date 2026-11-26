package com.earshot.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.data.ImportStatus
import com.earshot.app.ui.AppViewModel

/**
 * Slim strip above the mini player showing OPML import progress on every screen, then a summary
 * (naming any feeds that failed) that stays until dismissed.
 */
@Composable
fun ImportBanner(vm: AppViewModel) {
    val status by vm.importStatus.collectAsStateWithLifecycle()
    if (status == ImportStatus.Idle) return

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            when (val s = status) {
                ImportStatus.Reading -> Progress("Reading subscriptions file…", null)
                is ImportStatus.Running -> Progress(
                    "Importing ${s.done} of ${s.total}",
                    if (s.total > 0) s.done.toFloat() / s.total else null,
                )
                is ImportStatus.Finished -> Summary(
                    title = if (s.failed.isEmpty()) {
                        "Imported ${s.added} podcasts"
                    } else {
                        "Imported ${s.added} of ${s.total} podcasts"
                    },
                    detail = if (s.failed.isEmpty()) null else "Couldn't add: " + s.failed.joinToString(", "),
                    onDismiss = vm::dismissImport,
                )
                is ImportStatus.Error -> Summary(
                    title = "Import failed",
                    detail = s.message,
                    onDismiss = vm::dismissImport,
                )
                ImportStatus.Idle -> Unit
            }
        }
    }
}

@Composable
private fun Progress(text: String, fraction: Float?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        if (fraction == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Summary(title: String, detail: String?, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}
