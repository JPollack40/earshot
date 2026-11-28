@file:OptIn(ExperimentalMaterial3Api::class)

package com.earshot.app.ui.screens

import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.ui.platform.LocalUriHandler
import com.earshot.app.data.AdTrim
import com.earshot.app.data.feed.Persons

import com.earshot.app.ui.theme.LocalGlow
import com.earshot.app.ui.theme.rememberArtColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.data.EpisodeSearch
import com.earshot.app.data.db.EpisodeWithPodcast
import com.earshot.app.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Episodes that match a search within this podcast, with the bit of show notes that matched. */
private data class EpisodeMatches(val query: String, val episodes: List<EpisodeWithPodcast>, val snippets: Map<String, String>)

@Composable
fun PodcastScreen(vm: AppViewModel, podcastId: Long) {
    val podcast by remember(podcastId) { vm.podcast(podcastId) }.collectAsStateWithLifecycle(null)
    val episodes by remember(podcastId) { vm.episodesFor(podcastId) }.collectAsStateWithLifecycle(emptyList())
    var menuOpen by remember { mutableStateOf(false) }
    var chapterRulesOpen by remember { mutableStateOf(false) } // phase 19 (B4)
    if (chapterRulesOpen) ChapterRulesDialog(podcastId) { chapterRulesOpen = false }
    var confirmUnsubscribe by remember { mutableStateOf(false) }
    var showPlayback by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var showAdTrim by remember { mutableStateOf(false) }
    var sortMode by rememberSaveable(podcastId) { mutableStateOf(EpisodeSort.NEWEST) }
    // Phase 20 (B3): a serial show's page starts oldest first, until the sort is changed here.
    var sortTouched by rememberSaveable(podcastId) { mutableStateOf(false) }
    LaunchedEffect(podcast?.serial) {
        if (podcast?.serial == true && !sortTouched) sortMode = EpisodeSort.OLDEST
    }
    val defaults by vm.playbackDefaults.collectAsStateWithLifecycle()
    val tidy by vm.tidyDefaults.collectAsStateWithLifecycle()
    var descExpanded by remember { mutableStateOf(false) }
    // Phase 13: Episodes | You might like.
    var podTab by rememberSaveable(podcastId) { mutableIntStateOf(0) }
    val browseExcluded = rememberBrowseExcluded(vm)

    // ---- Search within this podcast (titles and show notes, on the phone) ----
    var searchOpen by rememberSaveable(podcastId) { mutableStateOf(false) }
    var query by rememberSaveable(podcastId) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Plain-text index of every episode, rebuilt only when the episode list changes.
    val index by produceState(emptyMap<String, String>(), episodes) {
        value = withContext(Dispatchers.Default) {
            episodes.associate { it.episode.id to EpisodeSearch.index(it.episode.title, it.episode.description) }
        }
    }
    val matches by produceState<EpisodeMatches?>(null, query, index, episodes) {
        val terms = EpisodeSearch.terms(query)
        if (terms.isEmpty()) {
            value = null
            return@produceState
        }
        delay(150) // let typing settle
        value = withContext(Dispatchers.Default) {
            val found = episodes.filter { e -> index[e.episode.id]?.let { EpisodeSearch.matches(it, terms) } == true }
            EpisodeMatches(
                query = query,
                episodes = found,
                snippets = found.mapNotNull { e ->
                    EpisodeSearch.snippet(e.episode.description, terms)?.let { e.episode.id to it }
                }.toMap(),
            )
        }
    }
    val searching = searchOpen && EpisodeSearch.terms(query).isNotEmpty()
    val hasNumbers = remember(episodes) { episodes.any { it.episode.episodeNo != null } }
    val sorted = remember(episodes, sortMode) { sortEpisodes(episodes, sortMode) }
    val shown = if (searching) matches?.episodes.orEmpty() else sorted

    fun closeSearch() {
        searchOpen = false
        query = ""
        focusManager.clearFocus()
    }
    BackHandler(enabled = searchOpen) { closeSearch() }
    LaunchedEffect(searchOpen) { if (searchOpen) focus.requestFocus() }

    val p = podcast
    val previewing = p != null && !p.subscribed

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    if (searchOpen) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            placeholder = { Text("Search episodes and show notes") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                        )
                    } else {
                        Text(p?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (searchOpen) closeSearch() else vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (searchOpen) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    } else {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search this podcast")
                        }
                        IconButton(onClick = { showPlayback = true }) {
                            Icon(Icons.Filled.Tune, contentDescription = "Playback settings")
                        }
                        RefreshAction(vm.refreshing) { vm.refresh(podcastId) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Playback settings") },
                                onClick = {
                                    menuOpen = false
                                    showPlayback = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (sortMode == EpisodeSort.NEWEST) "Sort: oldest first" else "Sort: newest first") },
                                onClick = {
                                    menuOpen = false
                                    sortTouched = true
                                    sortMode = if (sortMode == EpisodeSort.NEWEST) EpisodeSort.OLDEST else EpisodeSort.NEWEST
                                },
                            )
                            if (hasNumbers && sortMode != EpisodeSort.NUMBER) {
                                DropdownMenuItem(
                                    text = { Text("Sort: by episode number") },
                                    onClick = {
                                        menuOpen = false
                                        sortTouched = true
                                        sortMode = EpisodeSort.NUMBER
                                    },
                                )
                            }
                            if (!previewing && p != null) {
                                DropdownMenuItem(
                                    text = { Text("Trim inserted ads (${AdTrim.label(p.adTrim).lowercase()})") },
                                    onClick = {
                                        menuOpen = false
                                        showAdTrim = true
                                    },
                                )
                                if (p.serial || p.drip) {
                                    DropdownMenuItem(
                                        text = { Text(if (p.drip) "Catch-up drip: on" else "Catch-up drip: off") },
                                        onClick = {
                                            menuOpen = false
                                            vm.setDrip(podcastId, !p.drip)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Skip chapters named…") },
                                    onClick = {
                                        menuOpen = false
                                        chapterRulesOpen = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Automation (new episodes, tidy-up)") },
                                    onClick = {
                                        menuOpen = false
                                        showRules = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (p.pinned) "Unpin" else "Pin to top") },
                                    onClick = {
                                        menuOpen = false
                                        vm.setPinned(podcastId, !p.pinned)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Unsubscribe") },
                                    onClick = {
                                        menuOpen = false
                                        confirmUnsubscribe = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        EpisodeList(
            vm = vm,
            episodes = if (podTab == 1 && !searching) emptyList() else shown,
            showPodcast = false,
            modifier = Modifier.padding(padding).fillMaxSize(),
            snippets = if (searching) matches?.snippets.orEmpty() else emptyMap(),
            // Phase 7b: "Mark older/newer played" counts the whole podcast, even while searching.
            markRange = if (p != null && !previewing) episodes else null,
            header = {
                if (p != null && searching) {
                    item(key = "matches") {
                        val m = matches
                        Text(
                            when {
                                m == null || m.query != query -> "Searching…"
                                m.episodes.isEmpty() -> "No episodes match \"${query.trim()}\""
                                else -> "${m.episodes.size} of ${episodes.size} episodes match"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                } else if (p != null) {
                    item(key = "header") {
                        val glow = LocalGlow.current
                        val tint = rememberArtColor(p.imageUrl, p.feedUrl)
                        val bg = MaterialTheme.colorScheme.background
                        Column(
                            Modifier
                                .fillMaxWidth()
                                // Glow: the header fades from the artwork colour, with larger artwork and title.
                                .then(if (glow) Modifier.background(Brush.verticalGradient(0f to tint, 0.75f to tint.copy(alpha = 0.35f).compositeOver(bg), 1f to bg)) else Modifier)
                                .padding(if (glow) 16.dp else 12.dp),
                        ) {
                            Row(verticalAlignment = if (glow) Alignment.Bottom else Alignment.CenterVertically) {
                                Artwork(p.imageUrl, if (glow) 104 else 72, corner = if (glow) 10 else 8)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        p.title,
                                        style = if (glow) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                                        color = if (glow) Color.White else Color.Unspecified,
                                    )
                                    if (p.author.isNotBlank()) {
                                        Text(
                                            p.author,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (glow) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        "${episodes.size} episodes",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (glow) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (glow && !previewing) {
                                // Pin and playback settings as visible buttons (they're also in the menu).
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = { vm.setPinned(podcastId, !p.pinned) }) {
                                        Icon(if (p.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (p.pinned) "Pinned" else "Pin to top")
                                    }
                                    FilledTonalButton(onClick = { showPlayback = true }) {
                                        Icon(Icons.Filled.Tune, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Playback")
                                    }
                                    FilledTonalButton(onClick = { showRules = true }) {
                                        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Rules")
                                    }
                                }
                            }
                            if (!previewing) {
                                rulesSummary(p, tidy)?.let { summary ->
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        summary,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (glow) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { showRules = true },
                                    )
                                }
                            }
                            if (previewing) {
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (vm.subscribingUrl == p.feedUrl) {
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        FilledTonalButton(onClick = { vm.subscribe(p.feedUrl, openAfter = false) }) { Text("Subscribe") }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "Not subscribed. Episodes you play or queue stay in your queue until played.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            // Podcasting 2.0 extras (phase 10): people, funding, finished shows.
                            val people = remember(p.personsJson) { Persons.parse(p.personsJson) }
                            if (people.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    Persons.summary(people),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (glow) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (p.complete) {
                                Text(
                                    "Finished show: not checked for new episodes automatically (refresh still works).",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (glow) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (p.serial && !previewing) {
                                Text(
                                    if (p.drip) "Serial show · catch-up drip on: when an episode finishes, the next one joins the front of your queue."
                                    else "Serial show: meant to be heard in order. Turn on Catch-up drip in ⋮ to queue the next episode as each one finishes.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (glow) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clickable { vm.setDrip(podcastId, !p.drip) },
                                )
                            }
                            if (p.adTrim != null) {
                                Text(
                                    "Trimming inserted ads: ${AdTrim.label(p.adTrim).lowercase()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (glow) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { showAdTrim = true },
                                )
                            }
                            p.fundingUrl?.let { url ->
                                val uri = LocalUriHandler.current
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = { runCatching { uri.openUri(url) } }) {
                                    Icon(Icons.Filled.Favorite, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(p.fundingText?.take(40) ?: "Support this show", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (sortMode != EpisodeSort.NEWEST && !searching) {
                                Text(
                                    if (sortMode == EpisodeSort.NUMBER) "Sorted by episode number" else "Oldest first",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 6.dp).clickable {
                                        sortTouched = true
                                        sortMode = EpisodeSort.NEWEST
                                    },
                                )
                            }
                            if (p.description.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    AnnotatedString.fromHtml(p.description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (descExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { descExpanded = !descExpanded },
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item(key = "podtabs") {
                        androidx.compose.material3.PrimaryTabRow(selectedTabIndex = podTab) {
                            androidx.compose.material3.Tab(selected = podTab == 0, onClick = { podTab = 0 }, text = { Text("Episodes") })
                            androidx.compose.material3.Tab(selected = podTab == 1, onClick = { podTab = 1 }, text = { Text("You might like") })
                        }
                    }
                    if (podTab == 1) similarItems(vm, p.feedUrl, p.title, browseExcluded)
                }
            },
        )
    }

    val pod = podcast
    if (showPlayback && pod != null) {
        PodcastPlaybackDialog(
            podcast = pod,
            defaults = defaults,
            onChange = { change -> vm.updatePodcastPlayback(podcastId, change) },
            onDismiss = { showPlayback = false },
        )
    }

    if (showRules && pod != null) {
        PodcastRulesDialog(
            podcast = pod,
            defaults = tidy,
            onSave = { change -> vm.updateRules(podcastId, change) },
            onDismiss = { showRules = false },
        )
    }

    if (showAdTrim && pod != null) {
        AdTrimDialog(
            current = pod.adTrim,
            onPick = { vm.setAdTrim(podcastId, it); showAdTrim = false },
            onDismiss = { showAdTrim = false },
        )
    }

    if (confirmUnsubscribe) {
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            title = { Text("Unsubscribe?") },
            text = { Text("Remove ${podcast?.title.orEmpty()} and its downloads from this phone? Episodes in your queue stay until you play or remove them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsubscribe = false
                    vm.unsubscribe(podcastId)
                }) { Text("Unsubscribe") }
            },
            dismissButton = { TextButton(onClick = { confirmUnsubscribe = false }) { Text("Cancel") } },
        )
    }
}

/** Podcast page episode order (phase 10). NUMBER: season, then episode number, low to high. */
enum class EpisodeSort { NEWEST, OLDEST, NUMBER }

fun sortEpisodes(list: List<EpisodeWithPodcast>, mode: EpisodeSort): List<EpisodeWithPodcast> = when (mode) {
    EpisodeSort.NEWEST -> list
    EpisodeSort.OLDEST -> list.sortedBy { it.episode.pubDate }
    // Numbers compare as numbers (2 before 10); unnumbered episodes go last, oldest first.
    EpisodeSort.NUMBER -> list.sortedWith(
        compareBy<EpisodeWithPodcast>({ it.episode.episodeNo == null }, { it.episode.season ?: 0 }, { it.episode.episodeNo ?: 0.0 }, { it.episode.pubDate }),
    )
}

@Composable
private fun AdTrimDialog(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trim inserted ads") },
        text = {
            Column {
                Text(
                    "Some shows stitch ads onto every download, so the file is longer than the length the feed states. " +
                        "Earshot can skip that extra time where this show puts its ads. Only when the difference is between " +
                        "10 seconds and 20 minutes; episodes whose feed gives no length play in full.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                listOf(null, AdTrim.START, AdTrim.END).forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(mode) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == mode, onClick = { onPick(mode) })
                        Text(AdTrim.label(mode))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Phase 19 (B4): "always skip chapters whose title contains ..." for this show. */
@Composable
private fun ChapterRulesDialog(podcastId: Long, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf(com.earshot.app.data.ChapterRules.text(context, podcastId)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Skip chapters named…") },
        text = {
            Column {
                Text(
                    "In every episode of this show, chapters whose title contains any of these are skipped, as if you skipped them by hand. Separate them with commas. This phone only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("sponsor, housekeeping") },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { com.earshot.app.data.ChapterRules.set(context, podcastId, text); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
