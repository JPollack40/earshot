@file:OptIn(ExperimentalMaterial3Api::class)

package com.earshot.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.data.EpisodeSearch
import com.earshot.app.ui.AppViewModel
import com.earshot.app.ui.Screen
import com.earshot.app.ui.formatClock

/** Search across every saved transcript. A result opens the transcript at that moment. */
@Composable
fun TranscriptSearchScreen(vm: AppViewModel) {
    val focus = remember { FocusRequester() }
    val ids by vm.transcriptIds.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = vm.transcriptQuery,
                        onValueChange = vm::searchTranscripts,
                        placeholder = { Text("Search transcripts") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (vm.transcriptQuery.isNotEmpty()) {
                        IconButton(onClick = { vm.searchTranscripts("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                    }
                },
            )
        },
    ) { padding ->
        val results = vm.transcriptResults
        val terms = remember(vm.transcriptQuery) { EpisodeSearch.terms(vm.transcriptQuery) }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "status") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (vm.searchingTranscripts) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        when {
                            vm.transcriptQuery.isBlank() -> "${ids.size} saved transcript${if (ids.size == 1) "" else "s"}. " +
                                "Words can be in any order; put \"quotes\" around a phrase."
                            results.isEmpty() && !vm.searchingTranscripts -> "No matches in ${ids.size} transcripts"
                            else -> "${results.size} episode${if (results.size == 1) "" else "s"}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            results.forEach { hit ->
                item(key = "h-${hit.episodeId}") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.navigate(Screen.TranscriptView(hit.episodeId)) }
                            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val openPodcast: () -> Unit = {
                            hit.podcastId?.let(vm::openPodcastPage) ?: vm.openPodcastByFeed(hit.feedUrl)
                        }
                        Artwork(hit.artworkUrl, 36, modifier = Modifier.clickable(onClickLabel = "Open podcast", onClick = openPodcast))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            PodcastLink(hit.podcastTitle, openPodcast)
                        }
                    }
                }
                items(hit.matches, key = { "m-${hit.episodeId}-${it.startMs}-${it.text.hashCode()}" }) { seg ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.navigate(Screen.TranscriptView(hit.episodeId, focusMs = seg.startMs)) }
                            .padding(start = 58.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        Text(
                            formatClock(seg.startMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(52.dp).padding(top = 2.dp),
                        )
                        Text(highlight(seg.text, terms), style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
                item(key = "d-${hit.episodeId}") { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp) }
            }
        }
    }
}

/** The text with each search term in bold. */
@Composable
private fun highlight(text: String, terms: List<String>): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    return remember(text, terms, color) {
        val lower = text.lowercase().replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        val marks = BooleanArray(text.length)
        for (t in terms) {
            var i = lower.indexOf(t)
            while (i >= 0) {
                for (k in i until minOf(text.length, i + t.length)) marks[k] = true
                i = lower.indexOf(t, i + t.length)
            }
        }
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val on = marks[i]
                var j = i
                while (j < text.length && marks[j] == on) j++
                if (on) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color))
                    append(text.substring(i, j))
                    pop()
                } else {
                    append(text.substring(i, j))
                }
                i = j
            }
        }
    }
}
