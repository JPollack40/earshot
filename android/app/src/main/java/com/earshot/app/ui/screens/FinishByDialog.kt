package com.earshot.app.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.earshot.app.playback.FinishBy
import com.earshot.app.playback.FinishByMath
import com.earshot.app.ui.formatSpeed
import java.util.Calendar
import java.util.Date

/** "8:30 PM" in the phone's own clock style. */
fun finishByTime(context: android.content.Context, ms: Long): String =
    DateFormat.getTimeFormat(context).format(Date(ms))

/** The player's chip while Finish by runs: "By 8:30 PM · 1.4×". */
fun finishByLabel(context: android.content.Context, f: FinishBy, speed: Float): String =
    "By ${finishByTime(context, f.deadlineMs)} · ${formatSpeed(speed)}"

/**
 * Phase 20 (C4): pick a time the playing episode should end by, and how fast it may go.
 * [remainingMs] is what's left of the episode; [base] the listener's own speed.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FinishByDialog(
    episodeId: String,
    remainingMs: Long,
    base: Float,
    current: FinishBy?,
    onSet: (FinishBy?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val now = remember { System.currentTimeMillis() }
    // First guess: a little sooner than the episode ends at your speed, on a 5-minute mark.
    var deadline by remember {
        mutableLongStateOf(current?.deadlineMs ?: roundUpTo5(FinishByMath.endsAt(remainingMs, now, base + 0.25f)))
    }
    var max by remember { mutableFloatStateOf(current?.maxSpeed ?: FinishByMath.defaultMax(base)) }
    var picking by remember { mutableStateOf(false) }

    val speed = FinishByMath.speedFor(remainingMs, now, deadline, base, max)
    val endsAt = FinishByMath.endsAt(remainingMs, now, speed)
    val note = when {
        remainingMs <= 0 -> "The episode's length isn't known yet."
        speed <= base + 1e-3f -> "Your speed already ends it by then (at ${finishByTime(context, FinishByMath.endsAt(remainingMs, now, base))})."
        endsAt > deadline + 30_000 -> "Too tight: even at ${formatSpeed(max)} it ends at ${finishByTime(context, endsAt)}. It will play at ${formatSpeed(max)}."
        else -> "Plays at about ${formatSpeed(speed)} (you're at ${formatSpeed(base)}), adjusted as you go."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Finish by") },
        text = {
            Column {
                Text(
                    "Speeds this episode up just enough to end by the time you pick, and never past the limit. Your own speed comes back when it ends.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("End by", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
                    OutlinedButton(onClick = { picking = true }) { Text(finishByTime(context, deadline)) }
                }
                Spacer(Modifier.height(8.dp))
                Text("Up to", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FinishByMath.maxChoices(base).forEach { s ->
                        FilterChip(selected = s == max, onClick = { max = s }, label = { Text(formatSpeed(s)) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            TextButton(
                enabled = remainingMs > 0,
                onClick = { onSet(FinishBy(episodeId, deadline, max)) },
            ) { Text(if (current == null) "Start" else "Update") }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSet(null) }) { Text("Turn off") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (picking) {
        val cal = remember { Calendar.getInstance().apply { timeInMillis = deadline } }
        val state = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = DateFormat.is24HourFormat(context),
        )
        TimePickerHost(
            state = state,
            onDismiss = { picking = false },
            onPick = {
                deadline = FinishByMath.deadlineFor(state.hour, state.minute, System.currentTimeMillis())
                picking = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerHost(state: androidx.compose.material3.TimePickerState, onDismiss: () -> Unit, onPick: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = onPick) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun roundUpTo5(ms: Long): Long {
    val step = 5 * 60_000L
    return ((ms + step - 1) / step) * step
}
