package com.earshot.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.ui.AppViewModel
import com.earshot.app.ui.formatClock

/**
 * "Back to 12:34" (phase 10): shown for a few seconds after a big jump (a chapter, transcript line,
 * bookmark or the scrubber), returning to where the jump started.
 */
@Composable
fun JumpBackChip(vm: AppViewModel, modifier: Modifier = Modifier) {
    val jump by vm.player.jumpBack.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val j = jump ?: return
    if (j.episodeId != now.episodeId) return
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AssistChip(
            onClick = vm.player::undoJump,
            label = { Text("Back to ${formatClock(j.fromMs)}") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp)) },
            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}
