package com.earshot.app.car

import android.text.format.DateUtils
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.earshot.app.EarshotApp
import com.earshot.app.R
import com.earshot.app.data.PlaylistJson
import com.earshot.app.data.Settings
import com.earshot.app.data.db.EpisodeWithPodcast
import com.earshot.app.data.db.Playlist
import com.earshot.app.data.db.PodcastWithCount
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * Phase 12 custom car screens (Serena's choices, 2026-11-27):
 *  - Tabs like the phone's bottom bar. Cars allow 4 tabs, so: Queue, New Episodes (with Latest as a
 *    second section below), Playlists, Podcasts.
 *  - Podcasts: a grid of artwork, pinned first.
 *  - Tapping an episode plays it now (and opens Now Playing); each row has a Queue button that adds
 *    it to the end of the queue.
 *  - Now Playing: Google's playback screen (drawn from the media session) plus Speed and Chapters.
 */

private const val LATEST_LIMIT = 100

private fun app(context: CarContext) = context.applicationContext as EarshotApp

private fun icon(context: CarContext, res: Int): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(context, res)).build()

/** How many rows or tiles the car allows in one list (hosts differ, from 6 to 100). */
private fun limit(context: CarContext, type: Int, fallback: Int): Int = try {
    context.getCarService(ConstraintManager::class.java).getContentLimit(type)
} catch (e: Exception) {
    fallback
}

private fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) "%.0f" else "%.1f").format(speed) + "×"

private fun mmss(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * Podcast and episode artwork as car icons. Loads in the background and redraws the screen once
 * (debounced: hosts limit how often a screen may refresh).
 */
private class Artwork(private val screen: Screen) {
    private val cache = HashMap<String, CarIcon>()
    private val loading = HashSet<String>()
    private var redraw: Job? = null

    fun get(url: String?): CarIcon? {
        if (url.isNullOrBlank()) return null
        cache[url]?.let { return it }
        if (loading.add(url)) {
            screen.lifecycleScope.launch {
                val ctx = screen.carContext
                val result = runCatching {
                    SingletonImageLoader.get(ctx).execute(
                        ImageRequest.Builder(ctx).data(url).size(256).allowHardware(false).build(),
                    )
                }.getOrNull()
                val bitmap = result?.image?.toBitmap() ?: return@launch
                cache[url] = CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
                redraw?.cancel()
                redraw = screen.lifecycleScope.launch {
                    delay(400)
                    screen.invalidate()
                }
            }
        }
        return null
    }
}

/** One episode row: tap plays it; the Queue button adds it to the end of the queue. */
private fun Screen.episodeRow(item: EpisodeWithPodcast, art: Artwork, queueButton: Boolean): Row {
    val ctx = carContext
    val e = item.episode
    val detail = buildString {
        append(item.podcastTitle)
        if (e.pubDate > 0) {
            append(" · ")
            append(DateUtils.getRelativeTimeSpanString(e.pubDate, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS))
        }
        if (e.durationMs > 0 && e.positionMs > 0 && !e.played) {
            append(" · ")
            append(mmss((e.durationMs - e.positionMs).coerceAtLeast(0)))
            append(" left")
        }
    }
    val row = Row.Builder()
        .setTitle(e.title.ifBlank { "Untitled" })
        .addText(detail)
        .setImage(art.get(item.artworkUrl) ?: icon(ctx, R.drawable.ic_tab_podcasts), Row.IMAGE_TYPE_SMALL)
        .setOnClickListener {
            app(ctx).player.play(item)
            screenManager.push(NowPlayingScreen(ctx))
        }
    if (queueButton) {
        row.addAction(
            Action.Builder()
                .setIcon(icon(ctx, R.drawable.ic_car_queue_add))
                .setOnClickListener {
                    lifecycleScope.launch {
                        app(ctx).repository.queueLast(e.id)
                        CarToast.makeText(ctx, "Added to queue", CarToast.LENGTH_SHORT).show()
                    }
                }
                .build(),
        )
    }
    return row.build()
}

/** The first screen: four tabs, like the phone's bottom bar. */
class HomeScreen(carContext: CarContext) : Screen(carContext) {
    private val app = app(carContext)
    private val art = Artwork(this)

    private var activeTab = TAB_QUEUE
    private var queue: List<EpisodeWithPodcast>? = null
    private var inbox: List<EpisodeWithPodcast>? = null
    private var latest: List<EpisodeWithPodcast>? = null
    private var playlists: List<Playlist>? = null
    private var podcasts: List<PodcastWithCount>? = null
    private var nowPlayingId: String? = null
    private var nowPlayingTitle: String = ""

    init {
        val repo = app.repository
        lifecycleScope.launch { repo.observeQueue().collect { queue = it; invalidate() } }
        lifecycleScope.launch { repo.episodes.observeInbox().collect { inbox = it; invalidate() } }
        lifecycleScope.launch { repo.episodes.observeLatest(LATEST_LIMIT).collect { latest = it; invalidate() } }
        lifecycleScope.launch {
            repo.playlists.observeAll().collect { all ->
                playlists = all.filter { !it.deleted }.sortedBy { it.position }
                invalidate()
            }
        }
        // Pinned first, then by title (the DAO's order, same as the phone's library).
        lifecycleScope.launch { repo.podcasts.observeAllWithCounts().collect { podcasts = it; invalidate() } }
        lifecycleScope.launch {
            app.player.state.collect { s ->
                if (s.episodeId != nowPlayingId || s.title != nowPlayingTitle) {
                    nowPlayingId = s.episodeId
                    nowPlayingTitle = s.title
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val tabs = TabTemplate.Builder(object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {
                activeTab = tabContentId
                invalidate()
            }
        })
            .setHeaderAction(Action.APP_ICON)
            .addTab(tab(TAB_QUEUE, "Queue", R.drawable.ic_tab_queue))
            .addTab(tab(TAB_NEW, "New Episodes", R.drawable.ic_tab_new))
            .addTab(tab(TAB_PLAYLISTS, "Playlists", R.drawable.ic_tab_playlists))
            .addTab(tab(TAB_PODCASTS, "Podcasts", R.drawable.ic_tab_podcasts))
        val content = when (activeTab) {
            TAB_NEW -> newEpisodesTab()
            TAB_PLAYLISTS -> playlistsTab()
            TAB_PODCASTS -> podcastsTab()
            else -> queueTab()
        }
        return tabs.setTabContents(TabContents.Builder(content).build())
            .setActiveTabContentId(activeTab)
            .build()
    }

    private fun tab(id: String, title: String, iconRes: Int): Tab =
        Tab.Builder().setContentId(id).setTitle(title).setIcon(icon(carContext, iconRes)).build()

    private fun listLimit() = limit(carContext, ConstraintManager.CONTENT_LIMIT_TYPE_LIST, 20)

    private fun queueTab(): Template {
        val items = queue ?: return ListTemplate.Builder().setLoading(true).build()
        val list = ItemList.Builder().setNoItemsMessage("Your queue is empty")
        var room = listLimit()
        // The way back to the playback screen (Car App Library 1.7 has no "now playing" button on tabs).
        if (nowPlayingId != null) {
            list.addItem(
                Row.Builder()
                    .setTitle("Now playing")
                    .addText(nowPlayingTitle.ifBlank { "Open the player" })
                    .setImage(icon(carContext, R.drawable.ic_tab_queue), Row.IMAGE_TYPE_ICON)
                    .setOnClickListener { screenManager.push(NowPlayingScreen(carContext)) }
                    .build(),
            )
            room--
        }
        items.take(room.coerceAtLeast(0)).forEach { list.addItem(episodeRow(it, art, queueButton = false)) }
        return ListTemplate.Builder().setSingleList(list.build()).build()
    }

    private fun newEpisodesTab(): Template {
        val newItems = inbox
        val latestItems = latest
        if (newItems == null || latestItems == null) return ListTemplate.Builder().setLoading(true).build()
        val max = listLimit()
        val newIds = newItems.mapTo(HashSet()) { it.episode.id }
        val latestOnly = latestItems.filter { it.episode.id !in newIds }
        // New Episodes first; Latest gets what's left (at least a third of the space when there is some).
        val newShown = if (latestOnly.isEmpty()) newItems.take(max) else newItems.take(max - (max / 3).coerceAtLeast(1))
        val latestShown = latestOnly.take(max - newShown.size)
        val builder = ListTemplate.Builder()
        if (newShown.isEmpty() && latestShown.isEmpty()) {
            return builder.setSingleList(ItemList.Builder().setNoItemsMessage("No new episodes").build()).build()
        }
        if (newShown.isNotEmpty()) {
            val list = ItemList.Builder()
            newShown.forEach { list.addItem(episodeRow(it, art, queueButton = true)) }
            builder.addSectionedList(SectionedItemList.create(list.build(), "New Episodes"))
        }
        if (latestShown.isNotEmpty()) {
            val list = ItemList.Builder()
            latestShown.forEach { list.addItem(episodeRow(it, art, queueButton = true)) }
            builder.addSectionedList(SectionedItemList.create(list.build(), "Latest"))
        }
        return builder.build()
    }

    private fun playlistsTab(): Template {
        val items = playlists ?: return ListTemplate.Builder().setLoading(true).build()
        val list = ItemList.Builder().setNoItemsMessage("No playlists yet")
        items.take(listLimit()).forEach { p ->
            list.addItem(
                Row.Builder()
                    .setTitle(p.name.ifBlank { "Playlist" })
                    .setImage(icon(carContext, R.drawable.ic_tab_playlists), Row.IMAGE_TYPE_ICON)
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(PlaylistScreen(carContext, p.id, p.name)) }
                    .build(),
            )
        }
        return ListTemplate.Builder().setSingleList(list.build()).build()
    }

    private fun podcastsTab(): Template {
        val items = podcasts ?: return GridTemplate.Builder().setLoading(true).build()
        val list = ItemList.Builder().setNoItemsMessage("No podcasts yet")
        items.take(limit(carContext, ConstraintManager.CONTENT_LIMIT_TYPE_GRID, 20)).forEach { pc ->
            val p = pc.podcast
            val tile = GridItem.Builder()
                .setTitle(p.title.ifBlank { "Podcast" })
                .setImage(art.get(p.imageUrl) ?: icon(carContext, R.drawable.ic_tab_podcasts), GridItem.IMAGE_TYPE_LARGE)
                .setOnClickListener { screenManager.push(PodcastScreen(carContext, p.id, p.title)) }
            val text = listOfNotNull(
                "Pinned".takeIf { p.pinned },
                "${pc.unplayedCount} new".takeIf { pc.unplayedCount > 0 },
            ).joinToString(" · ")
            if (text.isNotEmpty()) tile.setText(text)
            list.addItem(tile.build())
        }
        return GridTemplate.Builder().setSingleList(list.build()).build()
    }

    private companion object {
        const val TAB_QUEUE = "queue"
        const val TAB_NEW = "new"
        const val TAB_PLAYLISTS = "playlists"
        const val TAB_PODCASTS = "podcasts"
    }
}

/** A list of episodes behind a back button: one podcast, or one smart playlist. */
abstract class EpisodeListScreen(carContext: CarContext, private val title: String) : Screen(carContext) {
    private val art = Artwork(this)
    private var items: List<EpisodeWithPodcast>? = null

    abstract suspend fun load(): List<EpisodeWithPodcast>

    init {
        lifecycleScope.launch {
            items = runCatching { load() }.getOrDefault(emptyList())
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val header = Header.Builder().setTitle(title.ifBlank { "Episodes" }).setStartHeaderAction(Action.BACK).build()
        val builder = ListTemplate.Builder().setHeader(header)
        val list = items ?: return builder.setLoading(true).build()
        val rows = ItemList.Builder().setNoItemsMessage("No episodes")
        list.take(limit(carContext, ConstraintManager.CONTENT_LIMIT_TYPE_LIST, 20))
            .forEach { rows.addItem(episodeRow(it, art, queueButton = true)) }
        return builder.setSingleList(rows.build()).build()
    }
}

class PodcastScreen(carContext: CarContext, private val podcastId: Long, title: String) :
    EpisodeListScreen(carContext, title) {
    override suspend fun load() = app(carContext).repository.episodes.getForPodcast(podcastId, 100)
}

class PlaylistScreen(carContext: CarContext, private val playlistId: String, title: String) :
    EpisodeListScreen(carContext, title) {
    override suspend fun load(): List<EpisodeWithPodcast> {
        val repo = app(carContext).repository
        val p = repo.playlists.get(playlistId) ?: return emptyList()
        if (p.deleted) return emptyList()
        return repo.playlistEpisodes(PlaylistJson.parse(p.filtersJson))
    }
}

/**
 * Now Playing: Google's playback screen. Artwork, title, progress and the play/skip buttons come
 * from the media session; the header adds Speed and Chapters.
 */
class NowPlayingScreen(carContext: CarContext) : Screen(carContext) {
    private val app = app(carContext)
    private var speed = app.player.state.value.speed

    init {
        lifecycleScope.launch {
            app.player.state.collect { s ->
                if (abs(s.speed - speed) > 0.01f) {
                    speed = s.speed
                    invalidate()
                }
            }
        }
    }

    @OptIn(ExperimentalCarApi::class)
    override fun onGetTemplate(): Template {
        val header = Header.Builder()
            .setStartHeaderAction(Action.BACK)
            .addEndHeaderAction(
                Action.Builder()
                    .setTitle(speedLabel(speed))
                    .setOnClickListener { screenManager.push(SpeedScreen(carContext)) }
                    .build(),
            )
            .addEndHeaderAction(
                Action.Builder()
                    .setIcon(icon(carContext, R.drawable.ic_car_chapters))
                    .setOnClickListener { screenManager.push(ChaptersScreen(carContext)) }
                    .build(),
            )
            .build()
        return MediaPlaybackTemplate.Builder().setHeader(header).build()
    }
}

/** Pick a speed. Saved the same way the phone saves it (a podcast with its own settings keeps it to itself). */
class SpeedScreen(carContext: CarContext) : Screen(carContext) {
    private val app = app(carContext)

    override fun onGetTemplate(): Template {
        val current = app.player.state.value.speed
        val list = ItemList.Builder()
        Settings.SPEED_CHOICES.forEach { s ->
            val row = Row.Builder()
                .setTitle(speedLabel(s))
                .setOnClickListener {
                    setSpeed(s)
                    screenManager.pop()
                }
            if (abs(s - current) < 0.01f) row.addText("Current speed")
            list.addItem(row.build())
        }
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Speed").setStartHeaderAction(Action.BACK).build())
            .setSingleList(list.build())
            .build()
    }

    private fun setSpeed(speed: Float) {
        val id = app.player.state.value.episodeId
        app.appScope.launch {
            val repo = app.repository
            val podcast = id?.let { repo.episodes.get(it) }?.podcastId?.let { repo.podcasts.get(it) }
            if (podcast != null && (podcast.speed != null || podcast.smartSpeed != null || podcast.voiceBoost != null)) {
                repo.updatePodcastPlayback(podcast.id) { it.copy(speed = speed) }
            } else {
                Settings.updatePlaybackDefaults(carContext.applicationContext) { it.copy(speed = speed) }
            }
        }
    }
}

/** The playing episode's chapters; tap one to jump there. */
class ChaptersScreen(carContext: CarContext) : Screen(carContext) {
    private val app = app(carContext)
    private var chapters: List<com.earshot.app.data.feed.Chapter>? = null

    init {
        lifecycleScope.launch {
            val id = app.player.state.value.episodeId
            val ep = id?.let { app.repository.episodes.get(it) }
            chapters = if (ep == null) emptyList() else runCatching { app.chapters.load(ep) }.getOrDefault(emptyList())
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val builder = ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Chapters").setStartHeaderAction(Action.BACK).build())
        val all = chapters ?: return builder.setLoading(true).build()
        val position = app.player.state.value.positionMs
        val currentIndex = all.indexOfLast { it.startMs <= position }
        val list = ItemList.Builder().setNoItemsMessage("This episode has no chapters")
        val max = limit(carContext, ConstraintManager.CONTENT_LIMIT_TYPE_LIST, 20)
        // Start the list near the current chapter when there are more than fit.
        val from = if (all.size <= max || currentIndex < 0) 0 else (currentIndex - 2).coerceIn(0, all.size - max)
        all.drop(from).take(max).forEachIndexed { i, c ->
            val row = Row.Builder()
                .setTitle(c.title.ifBlank { "Chapter ${from + i + 1}" })
                .addText(if (from + i == currentIndex) "${mmss(c.startMs)} · Playing" else mmss(c.startMs))
                .setOnClickListener {
                    app.player.seekTo(c.startMs)
                    screenManager.pop()
                }
            list.addItem(row.build())
        }
        return builder.setSingleList(list.build()).build()
    }
}
