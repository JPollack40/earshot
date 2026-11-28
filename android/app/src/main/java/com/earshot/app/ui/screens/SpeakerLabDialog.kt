package com.earshot.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.playback.SpeakerLab

/**
 * Phase 24 (lab): level and speed for each speaker of one episode. [speakers] in the order they first
 * speak, [label] gives each one's name.
 */
@Composable
fun SpeakerLabDialog(episodeId: String, speakers: List<String>, label: (String) -> String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version by SpeakerLab.version.collectAsStateWithLifecycle()
    val adjust = remember(episodeId, version) { SpeakerLab.adjustments(context, episodeId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speaker levels (lab)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "While this episode plays, each speaker gets their own level and speed. Raising one turns the others down, so you may want the phone a little louder.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (sp in speakers) {
                    val a = adjust[sp] ?: SpeakerLab.Adjust()
                    Spacer(Modifier.height(12.dp))
                    Text(label(sp), style = MaterialTheme.typography.titleSmall)
                    Text("Level", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (db in SpeakerLab.LEVEL_CHOICES_DB) {
                            FilterChip(
                                selected = a.levelDb == db,
                                onClick = { SpeakerLab.set(context, episodeId, sp, a.copy(levelDb = db)) },
                                label = { Text(if (db > 0) "+$db dB" else if (db == 0) "Same" else "$db dB") },
                            )
                        }
                    }
                    Text("Speed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (sx in SpeakerLab.SPEED_CHOICES) {
                            FilterChip(
                                selected = a.speed == sx,
                                onClick = { SpeakerLab.set(context, episodeId, sp, a.copy(speed = sx)) },
                                label = { Text(if (sx == 1f) "Same" else "×" + ("%.2f".format(sx)).trimEnd('0').trimEnd('.')) },
                            )
                        }
                    }
                }
                if (speakers.isEmpty()) {
                    Text("This transcript has no speaker labels. A Deepgram transcript (Transcribe) has them.", modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            if (adjust.isNotEmpty()) TextButton(onClick = { SpeakerLab.clear(context, episodeId) }) { Text("Reset") }
        },
    )
}
