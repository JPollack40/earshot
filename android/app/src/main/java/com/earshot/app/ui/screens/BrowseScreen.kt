@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.earshot.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earshot.app.data.Repository
import com.earshot.app.data.browse.BrowseApi
import com.earshot.app.data.browse.BrowseCard
import com.earshot.app.data.browse.BrowsePrefs
import com.earshot.app.ui.AppViewModel
import com.earshot.app.ui.Screen

/** Followed shows (by feed key) and shows hidden on this visit: never shown in Browse. */
@Composable
private fun rememberExcluded(vm: AppViewModel): (BrowseCard) -> Boolean {
    val library by vm.library.collectAsStateWithLifecycle()
    val followed = remember(library) { library.map { Repository.feedKey(it.podcast.feedUrl) }.toSet() }
    val hidden = vm.browse.hiddenNow
    return { c -> hidden[c.feedUrl] == true || Repository.feedKey(c.feedUrl) in followed }
}

/** The Browse tab (phase 13): rows of suggested shows. Tap a show to preview it; long-press for "Not interested". */
@Composable
fun BrowseTab(vm: AppViewModel, modifier: Modifier = Modifier) {
    val b = vm.browse
    LaunchedEffect(Unit) { if (vm.signedIn) b.load() }
    val excluded = rememberExcluded(vm)
    val page = b.page

    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "tools") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = { vm.navigate(Screen.BrowseCategories) },
                    label = { Text("Categories") },
                    leadingIcon = { Icon(Icons.Outlined.Category, null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { if (page != null) vm.navigate(Screen.BrowseSettings) },
                    enabled = page != null,
                    label = { Text("Browse settings") },
                    leadingIcon = { Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp)) },
                )
                Spacer(Modifier.weight(1f))
                if (b.loading) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = { b.load(force = true) }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload suggestions") }
                }
            }
        }
        when {
            !vm.signedIn -> item(key = "signin") {
                Message("Sign in to browse", "Browse suggests shows from your own listening. Sign in under Settings > Sync first.")
            }
            page == null && b.error != null -> item(key = "error") {
                Message("Browse didn't load", b.error.orEmpty())
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Button(onClick = { b.load(force = true) }) { Text("Try again") } }
            }
            page == null -> item(key = "loading") {
                Message("Finding shows for you…", "The first load can take up to half a minute.")
            }
            else -> {
                if (b.error != null) {
                    item(key = "error-small") {
                        Text(
                            "Couldn't refresh: ${b.error}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                for (s in page.sections) {
                    val items = s.items.filterNot(excluded)
                    if (items.isEmpty() && s.group != "friends") continue
                    item(key = "s:${s.id}") {
                        Column(Modifier.padding(top = 12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    s.subtitle?.let {
                                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (items.size > 3) {
                                    TextButton(onClick = {
                                        if (s.category != null) b.openCategory(s.category) else b.showAll(s.title, s.items)
                                        vm.navigate(Screen.BrowseList)
                                    }) { Text("See all") }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(items, key = { it.feedUrl }) { c -> ShowCard(vm, c) }
                            }
                        }
                    }
                }
                if (page.notes.isNotEmpty()) {
                    item(key = "notes") {
                        Text(
                            page.notes.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                item(key = "sources") {
                    Text(
                        "Charts: Apple Podcasts · Data: Podcast Index · Some suggestions by Gemini",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Message(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Artwork card: tap opens the preview, long-press offers "Not interested". */
@Composable
private fun ShowCard(vm: AppViewModel, c: BrowseCard, width: Int = 120) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .width(width.dp)
                .combinedClickable(onClick = { vm.openPodcast(c.feedUrl) }, onLongClick = { menu = true }),
        ) {
            Box {
                Artwork(c.artworkUrl, width, corner = 10)
                if (vm.openingUrl == c.feedUrl) {
                    Box(Modifier.size(width.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(c.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (c.reason.isNotBlank()) {
                Text(
                    c.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; vm.openPodcast(c.feedUrl) })
            DropdownMenuItem(text = { Text("Not interested") }, onClick = { menu = false; vm.browse.notInterested(c) })
        }
    }
}

/** A suggestion as a list row (podcast pages' "You might like" tab). */
@Composable
private fun ShowRow(vm: AppViewModel, c: BrowseCard) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { vm.openPodcast(c.feedUrl) }, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(c.artworkUrl, 56)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.author.isNotBlank()) {
                    Text(c.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(c.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (vm.openingUrl == c.feedUrl) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Not interested") }, onClick = { menu = false; vm.browse.notInterested(c) })
        }
    }
}

/** The "You might like" tab of a podcast page: its podroll, then popular shows in its categories. */
fun LazyListScope.similarItems(vm: AppViewModel, feedUrl: String, title: String, excluded: (BrowseCard) -> Boolean) {
    item(key = "similar-load") {
        LaunchedEffect(feedUrl) { if (vm.signedIn) vm.browse.loadSimilar(feedUrl, title) }
    }
    val state = vm.browse.similar[feedUrl]
    val items = state?.items?.filterNot(excluded)
    when {
        !vm.signedIn -> item(key = "similar-signin") { Message("Sign in to see suggestions", "Suggestions come from your Earshot account (Settings > Sync).") }
        state?.items == null -> item(key = "similar-loading") {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
        }
        state.error != null -> item(key = "similar-error") { Message("Suggestions didn't load", state.error) }
        items.isNullOrEmpty() -> item(key = "similar-none") { Message("No suggestions yet", "This show has no podroll, and nothing new turned up in its categories.") }
        else -> items(items, key = { "similar:" + it.feedUrl }) { c ->
            ShowRow(vm, c)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        }
    }
}

@Composable
fun rememberBrowseExcluded(vm: AppViewModel): (BrowseCard) -> Boolean = rememberExcluded(vm)

@Composable
private fun SubPage(vm: AppViewModel, title: String, actions: @Composable () -> Unit = {}, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { actions() },
            )
        },
    ) { padding -> content(Modifier.padding(padding).fillMaxSize()) }
}

/** "See all" for a row, or one category's shows, as an artwork grid. */
@Composable
fun BrowseListScreen(vm: AppViewModel) {
    val list = vm.browse.list
    val excluded = rememberExcluded(vm)
    SubPage(vm, list?.title ?: "Browse") { modifier ->
        val items = list?.items?.filterNot(excluded)
        when {
            list == null -> Unit
            items == null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.error != null -> Box(modifier) { Message("Didn't load", list.error) }
            items.isEmpty() -> Box(modifier) { Message("Nothing to show", "Everything here is already in your library or hidden.") }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = modifier,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items, key = { it.feedUrl }) { c -> ShowCard(vm, c, width = 112) }
            }
        }
    }
}

/** Every Podcast Index category; tap one for its trending shows. */
@Composable
fun BrowseCategoriesScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.browse.loadCategories() }
    val cats = vm.browse.categories
    SubPage(vm, "Categories") { modifier ->
        if (cats == null) {
            Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = modifier,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(cats, key = { it }) { name ->
                    OutlinedButton(onClick = {
                        vm.browse.openCategory(name)
                        vm.navigate(Screen.BrowseList)
                    }, modifier = Modifier.fillMaxWidth()) { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/**
 * Browse settings: which sections show and in what order, the category rows, the chart country,
 * "Popular with your friends", and shows marked Not interested. Saved when leaving the page.
 */
@Composable
fun BrowseSettingsScreen(vm: AppViewModel) {
    val b = vm.browse
    val page = b.page
    val start = page?.prefs ?: BrowsePrefs()
    var prefs by remember { mutableStateOf(start) }
    val startOrder = remember { (page?.groups.orEmpty() + BrowseApi.GROUP_NAMES.keys).distinct().filter { it in BrowseApi.GROUP_NAMES } }
    var order by remember { mutableStateOf(startOrder) }
    var addCategory by remember { mutableStateOf(false) }
    var countryOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { b.loadHidden() }
    val latest by rememberUpdatedState(prefs.copy(sectionOrder = order))
    DisposableEffect(Unit) {
        onDispose { if (page != null && latest != start.copy(sectionOrder = startOrder)) b.savePrefs(latest) }
    }

    SubPage(vm, "Browse settings") { modifier ->
        LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Heading("Sections", "Tick to show; arrows to move. Changes apply when you go back.") }
            items(order, key = { "g:$it" }) { g ->
                val i = order.indexOf(g)
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = g !in prefs.hiddenSections,
                        onCheckedChange = { on -> prefs = prefs.copy(hiddenSections = if (on) prefs.hiddenSections - g else prefs.hiddenSections + g) },
                    )
                    Text(BrowseApi.GROUP_NAMES[g] ?: g, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(enabled = i > 0, onClick = { order = order.toMutableList().apply { add(i - 1, removeAt(i)) } }) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
                    }
                    IconButton(enabled = i < order.lastIndex, onClick = { order = order.toMutableList().apply { add(i + 1, removeAt(i)) } }) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
                    }
                }
            }
            item {
                Heading("Category rows", "One row of popular shows for each (English only).")
                FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    prefs.categories.forEach { c ->
                        InputChip(
                            selected = false,
                            onClick = { prefs = prefs.copy(categories = prefs.categories - c) },
                            label = { Text(c) },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $c", Modifier.size(16.dp)) },
                        )
                    }
                }
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { b.loadCategories(); addCategory = true }) { Text("Add a category") }
                    TextButton(onClick = { prefs = prefs.copy(categories = BrowsePrefs.DEFAULT_CATEGORIES) }) { Text("Reset to the usual eight") }
                }
            }
            item {
                Heading("Top podcasts chart", "Apple Podcasts' daily chart for one country.")
                Box(Modifier.padding(horizontal = 12.dp)) {
                    OutlinedButton(onClick = { countryOpen = true }) { Text(BrowseApi.COUNTRIES[prefs.country] ?: prefs.country.uppercase()) }
                    DropdownMenu(expanded = countryOpen, onDismissRequest = { countryOpen = false }) {
                        BrowseApi.COUNTRIES.forEach { (code, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { prefs = prefs.copy(country = code); countryOpen = false })
                        }
                    }
                }
            }
            item {
                Heading("Popular with your friends", null)
                Row(Modifier.fillMaxWidth().clickable { prefs = prefs.copy(friendsOptIn = !prefs.friendsOptIn) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Count my listening (no names) and show what other Earshot friends who turned this on listen to. A show appears only when at least 2 of them listen to it.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = prefs.friendsOptIn, onCheckedChange = { prefs = prefs.copy(friendsOptIn = it) })
                }
            }
            item { Heading("Not interested", "Shows you hid. Undo to see them in Browse again.") }
            val hidden = b.hidden
            if (hidden == null) {
                item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
            } else if (hidden.isEmpty()) {
                item { Text("None", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp)) }
            } else {
                items(hidden, key = { "h:" + it.feedUrl }) { h ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(h.imageUrl, 40)
                        Spacer(Modifier.width(10.dp))
                        Text(h.title.ifBlank { h.feedUrl }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = { b.unhide(h) }) { Text("Undo") }
                    }
                }
            }
        }
    }

    if (addCategory) {
        val all = b.categories
        AlertDialog(
            onDismissRequest = { addCategory = false },
            confirmButton = { TextButton(onClick = { addCategory = false }) { Text("Done") } },
            title = { Text("Add a category") },
            text = {
                if (all == null) {
                    CircularProgressIndicator()
                } else {
                    LazyColumn(Modifier.height(420.dp)) {
                        items(all.filterNot { it in prefs.categories }, key = { it }) { c ->
                            Row(
                                Modifier.fillMaxWidth().clickable { prefs = prefs.copy(categories = prefs.categories + c) }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = false, onClick = { prefs = prefs.copy(categories = prefs.categories + c) })
                                Text(c)
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun Heading(title: String, body: String?) {
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (body != null) Text(body, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
