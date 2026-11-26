@file:OptIn(ExperimentalMaterial3Api::class)

package com.earshot.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.data.Repository
import com.earshot.app.ui.AppViewModel

@Composable
fun AddScreen(vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val library by vm.library.collectAsStateWithLifecycle()
    val subscribedUrls = library.map { it.podcast.feedUrl }.toSet()
    val focus = LocalFocusManager.current
    val looksLikeUrl = query.trim().let { it.contains("://") || (it.contains('.') && !it.contains(' ')) }

    val opmlPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.importOpml(uri)
            vm.back()
        }
    }

    fun submit() {
        focus.clearFocus()
        if (looksLikeUrl) vm.subscribe(query.trim(), openAfter = true) else vm.search(query)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Add podcast") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Search podcasts or paste a feed URL") },
                singleLine = true,
                leadingIcon = { Icon(if (looksLikeUrl) Icons.Outlined.RssFeed else Icons.Filled.Search, null) },
                trailingIcon = {
                    if (vm.searching || vm.subscribingUrl != null) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = if (looksLikeUrl) ImeAction.Go else ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { submit() }, onGo = { submit() }),
            )
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (looksLikeUrl) {
                    FilledTonalButton(onClick = { submit() }) { Text("Subscribe to this feed") }
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedButton(onClick = { opmlPicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import OPML")
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.searchResults, key = { it.feedUrl }) { r ->
                    val normalized = Repository.normalizeUrl(r.feedUrl)
                    val subscribed = normalized in subscribedUrls
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(r.artworkUrl, 48)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                r.author,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        when {
                            subscribed -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Check, contentDescription = "Subscribed", tint = MaterialTheme.colorScheme.primary)
                            }
                            vm.subscribingUrl == r.feedUrl -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                            else -> OutlinedButton(onClick = { vm.subscribe(r.feedUrl, openAfter = false) }) {
                                Text("Subscribe")
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }
}

