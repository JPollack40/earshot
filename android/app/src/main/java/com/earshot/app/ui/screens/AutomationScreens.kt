package com.earshot.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.earshot.app.data.Automation
import com.earshot.app.data.Automation.NewEpisodeAction
import com.earshot.app.data.TidyDefaults
import com.earshot.app.data.db.Podcast

/** "Never" / "N days" for the archive-after rule. */
fun inboxDaysLabel(days: Int) = if (days <= 0) "Never" else "$days days"

/** "No limit" / "Latest N" for the keep-latest rule. */
fun inboxKeepLabel(n: Int) = if (n <= 0) "No limit" else "Latest $n"

/** A short description of a podcast's rules for its page, or null when it has none. */
fun rulesSummary(p: Podcast, d: TidyDefaults): String? {
    if (!Automation.hasRules(p)) return null
    val parts = mutableListOf<String>()
    val action = NewEpisodeAction.of(p.newEpisodeAction)
    if (action != NewEpisodeAction.INBOX) parts += "new episodes → ${action.label.lowercase()}"
    Automation.words(p.includeWords).takeIf { it.isNotEmpty() }?.let { parts += "only \"${it.joinToString("\", \"")}\"" }
    Automation.words(p.excludeWords).takeIf { it.isNotEmpty() }?.let { parts += "skip \"${it.joinToString("\", \"")}\"" }
    p.minMinutes?.takeIf { it > 0 }?.let { parts += "at least $it min" }
    p.maxMinutes?.takeIf { it > 0 }?.let { parts += "at most $it min" }
    p.inboxDays?.let { parts += "archive after ${inboxDaysLabel(it).lowercase()}" }
    p.inboxKeep?.let { parts += "keep ${inboxKeepLabel(it).lowercase()}" }
    return "Rules: " + parts.joinToString(" · ")
}

/**
 * A podcast's automation rules (phase 7): where new episodes go, title and length filters, and the
 * tidy-up rules for its New Episodes. Changes apply when Save is tapped.
 */
@Composable
fun PodcastRulesDialog(
    podcast: Podcast,
    defaults: TidyDefaults,
    onSave: ((Podcast) -> Podcast) -> Unit,
    onDismiss: () -> Unit,
) {
    var action by remember { mutableStateOf(NewEpisodeAction.of(podcast.newEpisodeAction)) }
    var include by remember { mutableStateOf(podcast.includeWords.orEmpty()) }
    var exclude by remember { mutableStateOf(podcast.excludeWords.orEmpty()) }
    var min by remember { mutableStateOf(podcast.minMinutes) }
    var max by remember { mutableStateOf(podcast.maxMinutes) }
    var days by remember { mutableStateOf(podcast.inboxDays) }
    var keep by remember { mutableStateOf(podcast.inboxKeep) }
    val divider = @Composable { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp) }
    val minutes = listOf<Int?>(null) + Automation.MINUTE_CHOICES

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Automation") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "For ${podcast.title}. New-episode rules run when the app finds new episodes; already found ones aren't moved.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                SectionHeader("New episodes")
                ChoiceRow("Go to", null, action, NewEpisodeAction.entries, { it.label }) { action = it }
                divider()
                OutlinedTextField(
                    value = include,
                    onValueChange = { include = it },
                    label = { Text("Only if the title contains") },
                    placeholder = { Text("e.g. Part 1, interview") },
                    supportingText = { Text("Any of these, comma-separated. Blank = every episode.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                OutlinedTextField(
                    value = exclude,
                    onValueChange = { exclude = it },
                    label = { Text("Skip if the title contains") },
                    placeholder = { Text("e.g. bonus, trailer, rerun") },
                    supportingText = { Text("Any of these, comma-separated.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                ChoiceRow("At least", "Shorter ones are skipped", min, minutes, { it?.let { m -> "$m min" } ?: "Any length" }) { min = it }
                divider()
                ChoiceRow("At most", "Longer ones are skipped", max, minutes, { it?.let { m -> "$m min" } ?: "Any length" }) { max = it }
                divider()
                SectionHeader("Tidy up New Episodes")
                ChoiceRow(
                    "Archive after",
                    "Counted from release",
                    days,
                    listOf<Int?>(null) + Automation.INBOX_DAY_CHOICES,
                    { it?.let(::inboxDaysLabel) ?: "Default (${inboxDaysLabel(defaults.inboxDays).lowercase()})" },
                ) { days = it }
                divider()
                ChoiceRow(
                    "Keep only",
                    "Older ones are archived",
                    keep,
                    listOf<Int?>(null) + Automation.INBOX_KEEP_CHOICES,
                    { it?.let(::inboxKeepLabel) ?: "Default (${inboxKeepLabel(defaults.inboxKeep).lowercase()})" },
                ) { keep = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave {
                    it.copy(
                        newEpisodeAction = action.key,
                        includeWords = include,
                        excludeWords = exclude,
                        minMinutes = min,
                        maxMinutes = max,
                        inboxDays = days,
                        inboxKeep = keep,
                    )
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Settings > Automation: the app-wide tidy-up defaults. */
@Composable
fun TidySettings(d: TidyDefaults, onChange: ((TidyDefaults) -> TidyDefaults) -> Unit) {
    SectionHeader("Automation")
    Text(
        "Tidy up New Episodes for every podcast. Off until you choose. A podcast can have its own rules " +
            "(podcast page, Automation), including where its new episodes go.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    ChoiceRow("Archive New Episodes after", "Counted from release", d.inboxDays, Automation.INBOX_DAY_CHOICES, ::inboxDaysLabel) { v ->
        onChange { it.copy(inboxDays = v) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    ChoiceRow("Keep per podcast in New Episodes", "Older ones are archived", d.inboxKeep, Automation.INBOX_KEEP_CHOICES, ::inboxKeepLabel) { v ->
        onChange { it.copy(inboxKeep = v) }
    }
}
