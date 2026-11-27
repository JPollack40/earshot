package com.earshot.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.data.ai.AdSkip
import com.earshot.app.data.ai.AiKinds
import com.earshot.app.data.db.EpisodeWithPodcast
import com.earshot.app.ui.AppViewModel
import com.earshot.app.ui.formatClock

/** "Skip ad" (phase 11): shown while a found ad plays, when ads are set to show a button. */
@Composable
fun SkipAdChip(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ad by vm.currentAd.collectAsStateWithLifecycle()
    val a = ad ?: return
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AssistChip(
            onClick = vm::skipAd,
            label = { Text("Skip ad: ${a.label} (to ${formatClock(a.endMs)})") },
            leadingIcon = { Icon(Icons.Filled.FastForward, null, Modifier.size(18.dp)) },
            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}

/**
 * The "AI" dialog in the player (phase 11): a summary with key moments, chapters and ads, each
 * made from the playing episode's transcript on request. Times are tappable.
 */
@Composable
fun AiDialog(vm: AppViewModel, item: EpisodeWithPodcast, onDismiss: () -> Unit) {
    val ai by vm.ai.collectAsStateWithLifecycle()
    val running by vm.aiRunning.collectAsStateWithLifecycle()
    val adSkip by vm.adSkip.collectAsStateWithLifecycle()
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val id = item.episode.id
    fun busy(kind: String) = "$id|$kind" in running
    val jump: (Long) -> Unit = { ms -> vm.player.seekTo(ms) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("From the transcript") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Made by Gemini from this episode's transcript (needs a timed transcript). It can make mistakes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---- Ask about this episode (phase 13) ----
                Section("Ask about this episode")
                androidx.compose.material3.OutlinedTextField(
                    value = vm.askQuestion,
                    onValueChange = { vm.askQuestion = it },
                    placeholder = { Text("e.g. What did they say about sleep?") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { vm.ask(item) }),
                )
                MakeButton(label = "Ask", busy = vm.asking) { vm.ask(item) }
                vm.askError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                vm.answerFor(id)?.let { a ->
                    Spacer(Modifier.height(6.dp))
                    Text(a.question, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    a.answer.split("\n").filter { it.isNotBlank() }.forEach {
                        Text(it.trim(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    a.quotes.forEach { q -> TimeRow(q.atMs, "\u201C${q.text}\u201D", jump) }
                }

                // ---- Summary ----
                Section("Summary")
                val summary = ai.summary
                if (summary != null) {
                    summary.text.split("\n").filter { it.isNotBlank() }.forEach {
                        Text(it.trim(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    summary.points.forEach { p -> TimeRow(p.atMs, p.text, jump) }
                }
                MakeButton(
                    label = if (summary == null) "Summarise this episode" else "Summarise again",
                    busy = busy(AiKinds.SUMMARY),
                ) { vm.generateAi(item, AiKinds.SUMMARY) }

                // ---- Chapters ----
                Section("Chapters")
                val generated = ai.chapters
                val hasOwn = chapters.isNotEmpty() && chapters.none { it.generated }
                Text(
                    when {
                        hasOwn -> "This episode has its own chapters (${chapters.size}), so generated ones aren't used."
                        generated != null -> "${generated.size} chapters made from the transcript. They're in the chapter list below the player."
                        else -> "This episode has no chapters. Make some from the transcript to navigate it and skip parts."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!hasOwn) {
                    MakeButton(
                        label = if (generated == null) "Make chapters" else "Make chapters again",
                        busy = busy(AiKinds.CHAPTERS),
                    ) { vm.generateAi(item, AiKinds.CHAPTERS) }
                }

                // ---- Ads ----
                Section("Ads")
                val ads = ai.ads
                when {
                    ads == null -> Text("Find ad reads and breaks so they can be skipped.", style = MaterialTheme.typography.bodySmall)
                    ads.isEmpty() -> Text("No ads were found in this episode.", style = MaterialTheme.typography.bodySmall)
                    else -> ads.forEach { a -> TimeRow(a.startMs, "${a.label} (${formatClock(a.endMs - a.startMs)})", jump) }
                }
                MakeButton(
                    label = if (ads == null) "Find ads" else "Find ads again",
                    busy = busy(AiKinds.ADS),
                ) { vm.generateAi(item, AiKinds.ADS) }
                Spacer(Modifier.height(8.dp))
                Text("Found ads (all episodes)", style = MaterialTheme.typography.labelMedium)
                listOf(AdSkip.AUTO, AdSkip.BUTTON, AdSkip.OFF).forEach { mode ->
                    ChoiceLine(AdSkip.label(mode), selected = adSkip == mode) { vm.updateAdSkip(mode) }
                }
            }
        },
    )
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun TimeRow(atMs: Long, text: String, onJump: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onJump(atMs) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(formatClock(atMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(56.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MakeButton(label: String, busy: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !busy, modifier = Modifier.padding(top = 6.dp)) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Working…")
        } else {
            Text(label)
        }
    }
}

@Composable
private fun ChoiceLine(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
