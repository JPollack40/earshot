package com.earshot.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.earshot.app.data.SleepSchedule

/**
 * Phase 17: Settings > Playback > Automatic sleep timer. When on: the window (from, to), the days it
 * starts on and the timer's length.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepScheduleSetting(schedule: SleepSchedule, onChange: (SleepSchedule) -> Unit) {
    var picking by remember { mutableStateOf<String?>(null) } // "start" or "end"
    SettingRow(
        title = "Automatic sleep timer",
        subtitle = if (schedule.on) {
            "Press play between ${SleepSchedule.timeLabel(schedule.startMin)} and ${SleepSchedule.timeLabel(schedule.endMin)} " +
                "and a ${SleepSchedule.minutesLabel(schedule.minutes).lowercase()} timer starts. Phone and car."
        } else {
            "Sets a sleep timer when you press play at night (times and days of your choice)."
        },
        onClick = { onChange(schedule.copy(on = !schedule.on)) },
    ) {
        Switch(checked = schedule.on, onCheckedChange = { onChange(schedule.copy(on = it)) })
    }
    if (!schedule.on) return
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    SettingRow(title = "From", subtitle = "Start of the window", onClick = { picking = "start" }) {
        Text(SleepSchedule.timeLabel(schedule.startMin), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    SettingRow(title = "To", subtitle = "End of the window (can be the next morning)", onClick = { picking = "end" }) {
        Text(SleepSchedule.timeLabel(schedule.endMin), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("On these nights", style = MaterialTheme.typography.bodyMedium)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..7).forEach { d ->
                FilterChip(
                    selected = d in schedule.days,
                    onClick = {
                        onChange(schedule.copy(days = if (d in schedule.days) schedule.days - d else schedule.days + d))
                    },
                    label = { Text(SleepSchedule.dayLetter(d), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
    ChoiceRow("Timer length", null, schedule.minutes, SleepSchedule.MINUTE_CHOICES, SleepSchedule::minutesLabel) {
        onChange(schedule.copy(minutes = it))
    }
    picking?.let { which ->
        TimeDialog(
            initialMin = if (which == "start") schedule.startMin else schedule.endMin,
            onDismiss = { picking = null },
            onPick = { m ->
                onChange(if (which == "start") schedule.copy(startMin = m) else schedule.copy(endMin = m))
                picking = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initialMin: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = initialMin / 60, initialMinute = initialMin % 60, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
