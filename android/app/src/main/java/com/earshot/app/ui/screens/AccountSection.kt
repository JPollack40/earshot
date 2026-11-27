package com.earshot.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.ui.AppViewModel
import java.text.DateFormat
import java.util.Date

/** Settings > Sync: sign in to the Earshot account so the phone and the web player share everything. */
@Composable
fun AccountSection(vm: AppViewModel) {
    val session by vm.session.collectAsStateWithLifecycle()
    val status by vm.syncStatus.collectAsStateWithLifecycle()
    SectionHeader("Sync")
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (!vm.syncConfigured) {
            Text(
                "Sync isn't set up in this build.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val s = session
        var formOpen by rememberSaveable { mutableStateOf(false) }
        if (s == null && !formOpen) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Currently signed out", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Sign in to sync with the web player.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = { formOpen = true }) { Text("Sign in") }
            }
        } else if (s == null) {
            var email by rememberSaveable { mutableStateOf("") }
            // Phase 15: a secure text field (the old VisualTransformation one is deprecated).
            val passwordState = rememberTextFieldState()
            val password = passwordState.text.toString()
            var showPassword by rememberSaveable { mutableStateOf(false) }
            val focus = LocalFocusManager.current
            Text(
                "Sign in to sync subscriptions, queue, new episodes, positions and settings with the web player.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedSecureTextField(
                state = passwordState,
                label = { Text("Password") },
                textObfuscationMode = if (showPassword) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                onKeyboardAction = {
                    focus.clearFocus()
                    vm.signIn(email, passwordState.text.toString())
                },
                modifier = Modifier.fillMaxWidth(),
            )
            vm.signInError?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = {
                        focus.clearFocus()
                        vm.signIn(email, password)
                    },
                    enabled = !vm.signingIn && email.isNotBlank() && password.isNotEmpty(),
                ) { Text("Sign in") }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { formOpen = false }, enabled = !vm.signingIn) { Text("Cancel") }
                if (vm.signingIn) {
                    Spacer(Modifier.size(12.dp))
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        } else {
            Text("Signed in as ${s.email}", style = MaterialTheme.typography.bodyMedium)
            Text(
                when {
                    status.running -> "Syncing…"
                    status.error != null -> "Last sync failed: ${status.error}"
                    status.lastSyncAt > 0 -> "Last synced ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(status.lastSyncAt))}"
                    else -> "Not synced yet"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (status.error != null && !status.running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = vm::syncNow, enabled = !status.running) { Text("Sync now") }
                OutlinedButton(onClick = vm::signOut) { Text("Sign out") }
            }
        }
    }
}
