package com.earshot.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.playback.Output
import com.earshot.app.playback.OutputProfiles
import com.earshot.app.ui.formatSpeed

/** Phase 20 (C1): Settings > Playback > "Remember settings per output". */
@Composable
fun OutputProfilesSetting() {
    val context = LocalContext.current
    val on by OutputProfiles.enabled(context).collectAsStateWithLifecycle()
    val profiles by OutputProfiles.profiles(context).collectAsStateWithLifecycle()
    val current by OutputProfiles.current.collectAsStateWithLifecycle()
    val device by OutputProfiles.currentDevice.collectAsStateWithLifecycle()
    val kinds by OutputProfiles.deviceKinds(context).collectAsStateWithLifecycle()

    SettingRow(
        title = "Remember settings per output",
        subtitle = "Speed, Smart Speed and Voice Boost changed in the player are kept for the car, earbuds, a speaker or the phone, " +
            "and come back when you use it again. A podcast's own settings still win. This phone only.",
        onClick = { OutputProfiles.setEnabled(context, !on) },
    ) {
        Switch(checked = on, onCheckedChange = { OutputProfiles.setEnabled(context, it) })
    }
    if (!on) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "Playing through: ${current.label}" + (device?.let { " ($it)" } ?: ""),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Output.entries.forEach { out ->
            val p = profiles[out]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    out.label + ": " + (p?.let {
                        formatSpeed(it.speed) + (if (it.smartSpeed) " · Smart Speed" else "") + (if (it.voiceBoost) " · Voice Boost" else "")
                    } ?: "the defaults above"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (p != null) TextButton(onClick = { OutputProfiles.forget(context, out) }) { Text("Forget") }
            }
        }
        // A Bluetooth device is taken for earbuds unless it says it's a speaker; let it be marked.
        device?.let { name ->
            val kind = kinds[name] ?: if (current == Output.SPEAKER) Output.SPEAKER else Output.HEADPHONES
            Text("$name is:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            Row {
                listOf(Output.HEADPHONES to "Earbuds", Output.SPEAKER to "Speaker", Output.CAR to "Car").forEach { (k, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = kind == k,
                        onClick = { OutputProfiles.setDeviceKind(context, name, k) },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }
    }
}
