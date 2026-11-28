@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.earshot.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.earshot.app.data.Repository
import com.earshot.app.data.stats.Granularity
import com.earshot.app.data.stats.ListenEntry
import com.earshot.app.data.stats.ListeningStats
import com.earshot.app.data.stats.Period
import com.earshot.app.data.stats.Review
import com.earshot.app.data.stats.ReviewKind
import com.earshot.app.data.stats.StatsRange
import com.earshot.app.data.stats.StatsResult
import com.earshot.app.data.stats.Streaks
import com.earshot.app.ui.AppViewModel
import com.earshot.app.ui.Screen
import com.earshot.app.ui.StatsViewModel
import com.earshot.app.ui.formatDuration
import com.earshot.app.ui.formatSpeed
import com.earshot.app.ui.theme.LocalGlow
import com.earshot.app.ui.theme.rememberArtColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/*
 * Stats page and week / month / year in review. Built from the listening history (phase 7) with the
 * maths in data/stats/ListeningStats.kt. Charts are plain bars drawn with boxes: no chart library.
 * Sections that need more history than there is yet say what they're waiting for instead of
 * showing a near-empty chart.
 */

private val zone: ZoneId get() = ZoneId.systemDefault()

@Composable
fun StatsScreen(vm: AppViewModel) {
    val stats: StatsViewModel = viewModel()
    val entries by stats.entries.collectAsStateWithLifecycle()
    val lengths by stats.lengths.collectAsStateWithLifecycle()
    val art by stats.artwork.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { stats.refreshLengths() }

    val list = entries
    val range = stats.range
    val now = remember(list) { System.currentTimeMillis() }
    val data by produceState<Loaded?>(null, list, range, lengths) {
        value = list?.let { withContext(Dispatchers.Default) { load(it, range, lengths, now) } }
    }
    val d = data
    val summary = when {
        list == null || d == null -> null
        list.isEmpty() -> "Nothing yet"
        range == StatsRange.ALL -> "${formatTotal(d.result.totalMs)} in all"
        else -> "${formatTotal(d.result.totalMs)} in the last ${range.label.lowercase()}"
    }

    StatsScaffold(vm, title = "Stats", eyebrow = "Your listening", summary = summary, tintUrl = d?.result?.topPodcasts?.firstOrNull()?.let { art[Repository.feedKey(it.feedUrl)] }) {
        if (list == null || d == null) return@StatsScaffold
        if (list.isEmpty()) {
            EmptyStats()
            return@StatsScaffold
        }
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsRange.entries.forEach { r ->
                FilterChip(selected = range == r, onClick = { stats.range = r }, label = { Text(r.label) })
            }
        }
        StatsBody(vm, d.result, art, comparedWith = "the ${range.label.lowercase()} before", streaks = d.streaks)
        Reviews(vm, d.reviews)
        LibraryCheckupSection(vm) // phase 22 (B6)
        Footer(d.historyStart)
    }
}

/** Phase 22 (B6): suggestions for tidying the library. Nothing changes until a fix is tapped. */
@Composable
private fun LibraryCheckupSection(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshCheckup() }
    val list = vm.checkup ?: return
    Section("Library check-up") {
        if (list.isEmpty()) {
            Hint("All tidy: nothing to suggest.")
            return@Section
        }
        Hint("Suggestions only. Nothing changes until you tap one.")
        list.forEach { f ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(f.title, style = MaterialTheme.typography.bodyMedium)
                Text(f.detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    val K = com.earshot.app.data.LibraryCheckup.Kind
                    TextButton(onClick = { vm.fixFinding(f) }) {
                        Text(
                            when (f.kind) {
                                K.PASSED_OVER -> "Stop adding new episodes"
                                K.ALWAYS_FIRST -> "New episodes to the top of the queue"
                                K.QUIET -> "Open the show"
                                K.STALE_QUEUE -> "Take them out of the queue"
                            },
                        )
                    }
                    TextButton(onClick = { vm.dismissFinding(f) }) { Text("Not now") }
                }
            }
        }
    }
}

/** A week, month or year in review: the same sections for that calendar period, led by its highlights. */
@Composable
fun StatsReviewScreen(vm: AppViewModel, kind: ReviewKind, from: Long) {
    val stats: StatsViewModel = viewModel()
    val entries by stats.entries.collectAsStateWithLifecycle()
    val lengths by stats.lengths.collectAsStateWithLifecycle()
    val art by stats.artwork.collectAsStateWithLifecycle()
    val list = entries
    val period = remember(kind, from) { ListeningStats.reviewPeriod(kind, from, zone) }
    val now = remember(list) { System.currentTimeMillis() }
    val result by produceState<StatsResult?>(null, list, lengths) {
        value = list?.let {
            withContext(Dispatchers.Default) {
                ListeningStats.compute(
                    it, period, zone,
                    feedKey = Repository::feedKey,
                    durationOf = { id -> lengths[id] },
                    previous = ListeningStats.previousReview(kind, period, zone),
                )
            }
        }
    }
    val r = result
    val inProgress = now < period.to
    StatsScaffold(
        vm,
        title = reviewTitle(kind, period),
        eyebrow = when (kind) {
            ReviewKind.WEEK -> "Week in review"
            ReviewKind.MONTH -> "Month in review"
            ReviewKind.YEAR -> "Year in review"
        } + if (inProgress) " · so far" else "",
        summary = r?.let { if (it.totalMs > 0) formatTotal(it.totalMs) + " listened" else "No listening" },
        tintUrl = r?.topPodcasts?.firstOrNull()?.let { art[Repository.feedKey(it.feedUrl)] },
    ) {
        if (r == null) return@StatsScaffold
        Highlights(r, kind)
        StatsBody(vm, r, art, comparedWith = "the ${kind.name.lowercase()} before", streaks = null)
    }
}

private class Loaded(val result: StatsResult, val streaks: Streaks, val reviews: List<Review>, val historyStart: Long?)

private fun load(list: List<ListenEntry>, range: StatsRange, lengths: Map<String, Long>, now: Long): Loaded {
    val historyStart = list.minOfOrNull { it.startedAt }
    val p = ListeningStats.period(range, now, zone, historyStart)
    val prev = if (range == StatsRange.ALL) null else Period(p.from - (p.to - p.from), p.from)
    val result = ListeningStats.compute(list, p, zone, feedKey = Repository::feedKey, durationOf = { lengths[it] }, previous = prev)
    return Loaded(result, ListeningStats.streaks(list, now, zone), ListeningStats.reviews(list, now, zone), historyStart)
}

/** Glow: a tinted header like the library; Classic: a plain top bar. Content scrolls. */
@Composable
private fun StatsScaffold(
    vm: AppViewModel,
    title: String,
    eyebrow: String,
    summary: String?,
    tintUrl: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glow = LocalGlow.current
    val body: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
    if (glow) {
        val tint = rememberArtColor(tintUrl, "earshot-stats")
        Column(Modifier.fillMaxSize()) {
            GlowHero(
                tint = tint,
                eyebrow = eyebrow,
                title = title,
                summary = summary,
                top = {
                    CompositionLocalProvider(LocalContentColor provides Color.White) {
                        IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                    Spacer(Modifier.weight(1f))
                },
            )
            body(Modifier.weight(1f).padding(top = 4.dp))
        }
    } else {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(eyebrow, summary).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                )
            },
        ) { padding -> body(Modifier.padding(padding).padding(top = 8.dp)) }
    }
}

@Composable
private fun EmptyStats() {
    Section("Your stats start here") {
        Hint(
            "Earshot has kept a listening log since the phase 7 update (Nov 27, 2026). Play something and " +
                "your stats appear here: listening on this phone, in the car and in the web player all count.",
        )
    }
}

@Composable
private fun StatsBody(vm: AppViewModel, r: StatsResult, art: Map<String, String>, comparedWith: String, streaks: Streaks?) {
    if (r.totalMs <= 0) {
        Section(null) { Hint("No listening in this period.") }
        return
    }
    Tiles(r, comparedWith, streaks)

    TopPodcasts(vm, r, art)
    TopEpisodes(vm, r)

    if (r.timeline.size > 1) {
        Section(
            when (r.granularity) {
                Granularity.DAY -> "Each day"
                Granularity.WEEK -> "Each week"
                Granularity.MONTH -> "Each month"
            },
        ) {
            val max = r.timeline.maxOf { it.ms }
            Bars(
                values = r.timeline.map { it.ms },
                description = "Listening ${r.granularity.name.lowercase()} by ${r.granularity.name.lowercase()}, most ${formatTotal(max)}",
            )
            Row(Modifier.fillMaxWidth()) {
                Text(bucketLabel(r.timeline.first().start, r.granularity), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(bucketLabel(r.timeline.last().start, r.granularity), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            r.busiestDay?.let { Hint("Busiest day: ${dayLabel(it.start)} (${formatTotal(it.ms)})") }
        }
    }

    Section("Time of day") {
        val peak = ListeningStats.peakHours(r.hours)
        if (peak == null) {
            Hint("Shows when you listen once there's 10 minutes of listening in this period.")
        } else {
            Bars(r.hours.toList(), description = "Listening by hour of the day, mostly ${hourRange(peak)}")
            Row(Modifier.fillMaxWidth()) {
                listOf("12 AM", "6 AM", "12 PM", "6 PM").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
            Hint("Mostly ${hourRange(peak)}")
        }
    }

    Section("Day of the week") {
        if (r.activeDays < 3) {
            Hint("Shows your pattern after 3 days of listening (${r.activeDays} so far).")
        } else {
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            val names = (1..7).map { java.time.DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) }
            Bars(r.weekdays.toList(), description = "Listening by day of the week, most on ${names[r.weekdays.indices.maxBy { r.weekdays[it] }]}")
            Row(Modifier.fillMaxWidth()) {
                names.forEach {
                    Text(it.take(2), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    Section("Where you listen") { Devices(r) }

    r.newPodcasts?.takeIf { it.isNotEmpty() }?.let { list ->
        Section("New to you") {
            Hint("Podcasts you first played in this period.")
            list.take(5).forEach { p ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PodcastLink(
                        p.title.ifBlank { "Unknown podcast" },
                        onOpenPodcast = { vm.openPodcastByFeed(p.feedUrl) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(" · ${formatTotal(p.ms)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (list.size > 5) Hint("and ${list.size - 5} more")
        }
    }
}

/** Totals in a 2×2 grid, then a line of smaller facts. */
@Composable
private fun Tiles(r: StatsResult, comparedWith: String, streaks: Streaks?) {
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Listened", formatTotal(r.totalMs), r.previousTotalMs?.let { compare(r.totalMs, it, comparedWith) }, Modifier.weight(1f))
            Tile("Days listened", "${r.activeDays} of ${r.days}", r.longestStreak.takeIf { it > 1 }?.let { "Longest run: $it days" }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Episodes", r.episodes.toString(), r.finished?.let { "$it finished" }, Modifier.weight(1f))
            Tile("Podcasts", r.podcasts.toString(), null, Modifier.weight(1f))
        }
        val context = androidx.compose.ui.platform.LocalContext.current
        val smartSaved = remember(r.from, r.to) { com.earshot.app.data.stats.SmartSavedLog.savedBetween(context, r.from, r.to) }
        val facts = listOfNotNull(
            streaks?.takeIf { it.current > 0 }?.let { "Streak: ${it.current} day${if (it.current == 1) "" else "s"} in a row (best ${it.longest})" },
            r.savedBySpeedMs.takeIf { it >= 60_000 }?.let { "Saved ${formatTotal(it)} by playing faster (average ${formatSpeed(r.averageSpeed.roundSpeed())})" },
            // Phase 22: Smart Speed's trimmed silences, recorded per day on this phone from now on.
            smartSaved.takeIf { it >= 60_000 }?.let { "Smart Speed trimmed ${formatTotal(it)} of silence (this phone)" },
        )
        facts.forEach { Hint(it) }
    }
}

private fun Float.roundSpeed(): Float = Math.round(this * 20) / 20f

@Composable
private fun Tile(label: String, value: String, note: String?, modifier: Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
        if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun compare(now: Long, before: Long, comparedWith: String): String {
    val diff = now - before
    return when {
        before <= 0 -> "Nothing $comparedWith"
        kotlin.math.abs(diff) < 60_000 -> "Same as $comparedWith"
        diff > 0 -> "${formatTotal(diff)} more than $comparedWith"
        else -> "${formatTotal(-diff)} less than $comparedWith"
    }
}

@Composable
private fun TopPodcasts(vm: AppViewModel, r: StatsResult, art: Map<String, String>) {
    var all by rememberSaveable(r.from, r.to) { mutableStateOf(false) }
    val top = r.topPodcasts.firstOrNull()?.ms ?: return
    Section("Top podcasts") {
        (if (all) r.topPodcasts else r.topPodcasts.take(5)).forEachIndexed { i, p ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Open podcast") { vm.openPodcastByFeed(p.feedUrl) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp))
                Artwork(art[Repository.feedKey(p.feedUrl)], 40)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title.ifBlank { "Unknown podcast" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${formatTotal(p.ms)} · ${p.episodes} episode${if (p.episodes == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ShareBar(p.ms.toFloat() / top)
                }
            }
        }
        if (r.topPodcasts.size > 5) {
            TextButton(onClick = { all = !all }) { Text(if (all) "Show top 5" else "Show all ${r.topPodcasts.size}") }
        }
    }
}

@Composable
private fun TopEpisodes(vm: AppViewModel, r: StatsResult) {
    var all by rememberSaveable(r.from, r.to) { mutableStateOf(false) }
    if (r.topEpisodes.isEmpty()) return
    Section("Top episodes") {
        (if (all) r.topEpisodes.take(50) else r.topEpisodes.take(5)).forEachIndexed { i, e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title.ifBlank { "Unknown episode" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row {
                        PodcastLink(e.podcastTitle.ifBlank { "Unknown podcast" }, onOpenPodcast = { vm.openPodcastByFeed(e.feedUrl) }, modifier = Modifier.weight(1f, fill = false))
                        Text(" · ${formatTotal(e.ms)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (r.topEpisodes.size > 5) {
            TextButton(onClick = { all = !all }) { Text(if (all) "Show top 5" else "Show more") }
        }
    }
}

@Composable
private fun ShareBar(fraction: Float) {
    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
    }
}

/** Simple bar chart; the tallest bar fills the height, empty slots show a thin line so gaps read as gaps. */
@Composable
private fun Bars(values: List<Long>, description: String) {
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    val bar = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = description },
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEach { v ->
            Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp), contentAlignment = Alignment.BottomCenter) {
                if (v > 0) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight((v.toFloat() / max).coerceAtLeast(0.04f)).background(bar, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
                } else {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(empty))
                }
            }
        }
    }
}

@Composable
private fun Devices(r: StatsResult) {
    val colors = mapOf(
        "phone" to MaterialTheme.colorScheme.primary,
        "car" to MaterialTheme.colorScheme.secondary,
        "web" to MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val names = mapOf("phone" to "Phone", "car" to "Car", "web" to "Web")
    val used = r.devices.filter { it.second > 0 }
    Row(Modifier.fillMaxWidth().height(10.dp).semantics { contentDescription = used.joinToString { "${names[it.first]} ${percent(it.second, r.totalMs)}" } }) {
        used.forEach { (d, ms) ->
            Box(Modifier.weight(ms.toFloat()).fillMaxHeight().padding(end = 2.dp).background(colors.getValue(d), RoundedCornerShape(3.dp)))
        }
    }
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        used.forEach { (d, ms) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(colors.getValue(d), RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(5.dp))
                Text("${names[d]} ${formatTotal(ms)} (${percent(ms, r.totalMs)})", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun percent(part: Long, total: Long) = if (total <= 0) "0%" else "${Math.round(part * 100.0 / total)}%"

/** The opening card of a review: a few sentences, like a short Wrapped without the slideshow. */
@Composable
private fun Highlights(r: StatsResult, kind: ReviewKind) {
    if (r.totalMs <= 0) return
    val unit = kind.name.lowercase()
    val lines = listOfNotNull(
        "You listened for ${formatTotal(r.totalMs)}: ${r.episodes} episode${if (r.episodes == 1) "" else "s"} from ${r.podcasts} podcast${if (r.podcasts == 1) "" else "s"}.",
        r.topPodcasts.firstOrNull()?.let { "Top podcast: ${it.title} (${formatTotal(it.ms)})." },
        r.topEpisodes.firstOrNull()?.let { "Most played episode: ${it.title}." },
        r.busiestDay?.takeIf { kind != ReviewKind.YEAR || r.activeDays > 1 }?.let { "Busiest day: ${dayLabel(it.start)}." },
        ListeningStats.peakHours(r.hours)?.let { "Usually ${hourRange(it)}." },
        r.previousTotalMs?.let { compare(r.totalMs, it, "the $unit before") + "." },
        r.longestStreak.takeIf { it > 1 }?.let { "Longest run: $it days in a row." },
    )
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Highlights", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Reviews(vm: AppViewModel, reviews: List<Review>) {
    if (reviews.isEmpty()) return
    Section("Reviews") {
        Hint("Every past week, month and year stays here to open again.")
        listOf(ReviewKind.WEEK to "Weeks", ReviewKind.MONTH to "Months", ReviewKind.YEAR to "Years").forEach { (kind, label) ->
            val list = reviews.filter { it.kind == kind }
            if (list.isEmpty()) return@forEach
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            list.forEach { rv ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.navigate(Screen.StatsReview(rv.kind, rv.from)) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        reviewTitle(rv.kind, Period(rv.from, rv.to)) + if (rv.inProgress) " (so far)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatTotal(rv.totalMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Footer(historyStart: Long?) {
    if (historyStart == null) return
    Text(
        "History kept since ${dateFmt("MMM d, yyyy").format(Instant.ofEpochMilli(historyStart).atZone(zone))}. Times are in this phone's time zone.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
        }
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
}

/** "3h 05m", "42m", "under a minute". */
private fun formatTotal(ms: Long): String = if (ms < 60_000) "under a minute" else formatDuration(ms)

private fun dateFmt(pattern: String) = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())

private fun dayLabel(ms: Long) = dateFmt("EEEE, MMM d").format(Instant.ofEpochMilli(ms).atZone(zone))

private fun bucketLabel(ms: Long, g: Granularity): String {
    val z = Instant.ofEpochMilli(ms).atZone(zone)
    return when (g) {
        Granularity.DAY -> dateFmt("MMM d").format(z)
        Granularity.WEEK -> "Week of " + dateFmt("MMM d").format(z)
        Granularity.MONTH -> dateFmt("MMM yyyy").format(z)
    }
}

private fun reviewTitle(kind: ReviewKind, p: Period): String {
    val z = Instant.ofEpochMilli(p.from).atZone(zone)
    return when (kind) {
        ReviewKind.WEEK -> "Week of " + dateFmt("MMM d, yyyy").format(z)
        ReviewKind.MONTH -> dateFmt("MMMM yyyy").format(z)
        ReviewKind.YEAR -> z.year.toString()
    }
}

/** "7–10 PM", "11 PM–2 AM" for a 3-hour window starting at [start]. */
private fun hourRange(start: Int): String {
    fun h(x: Int): Pair<Int, String> {
        val hour = ((x % 24) + 24) % 24
        val label = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return label to if (hour < 12) "AM" else "PM"
    }
    val (a, am) = h(start)
    val (b, bm) = h(start + 3)
    return if (am == bm) "$a–$b $bm" else "$a $am–$b $bm"
}
