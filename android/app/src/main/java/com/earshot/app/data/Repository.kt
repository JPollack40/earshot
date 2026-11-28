package com.earshot.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.earshot.app.data.db.AppDatabase
import com.earshot.app.data.db.DownloadState
import com.earshot.app.data.db.Episode
import com.earshot.app.data.db.Podcast
import com.earshot.app.data.db.EpisodeWithPodcast
import com.earshot.app.data.db.Playlist
import com.earshot.app.data.db.PlaylistRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import com.earshot.app.data.feed.Chapter
import com.earshot.app.data.feed.ChaptersParser
import com.earshot.app.data.feed.Persons
import com.earshot.app.data.feed.FeedParser
import com.earshot.app.data.feed.OpmlFeed
import com.earshot.app.data.feed.OpmlParser
import com.earshot.app.data.feed.ParsedEpisode
import com.earshot.app.data.feed.ParsedFeed
import com.earshot.app.data.net.Http
import com.earshot.app.work.DownloadWorker
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** [added] counts feeds now in the library (including ones that already were); [failed] holds titles. */
data class ImportResult(val added: Int, val failed: List<String>)

private const val TAG = "Earshot"

/** The server answered, but what it sent isn't a feed we can read. Not worth retrying. */
class FeedFormatException(message: String, cause: Throwable) : IOException(message, cause)
private const val DAY_MS = 24L * 60 * 60 * 1000

@OptIn(ExperimentalCoroutinesApi::class)
class Repository(private val context: Context, private val db: AppDatabase) {
    val podcasts = db.podcasts()
    val episodes = db.episodes()

    fun observeLibrary() = podcasts.observeAllWithCounts()
    fun observeLatest() = episodes.observeLatest(300)
    fun observePodcast(id: Long) = podcasts.observe(id)
    fun observeEpisodes(podcastId: Long) = episodes.observeForPodcast(podcastId)
    fun observeEpisode(id: String) = episodes.observeWithPodcast(id)

    // ---- Subscriptions ----

    /**
     * [inboxNewest]: put the newest episode in the Inbox (single subscribe), or nothing (OPML import,
     * so importing many feeds doesn't flood the Inbox). Later refreshes inbox every new episode.
     */
    suspend fun subscribe(rawUrl: String, inboxNewest: Boolean = true): Podcast = withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawUrl)
        findPodcast(url)?.let { existing ->
            if (existing.subscribed) return@withContext existing
            // Was unsubscribed but kept for its queued episodes, or is being previewed: bring it back.
            podcasts.setSubscribed(existing.id, true)
            val podcast = existing.copy(subscribed = true)
            runCatching { refresh(podcast, InboxMode.NONE) }
                .onFailure { if (it is CancellationException) throw it }
            // Same rule as a fresh subscribe: only the newest episode goes to the Inbox (when not
            // already played or queued). A preview has already stored every episode, so the refresh
            // above finds none "new" and can't do this itself.
            episodes.markRecentForUpload(existing.id, System.currentTimeMillis() - AppDatabase.RECENT_MS)
            if (inboxNewest) {
                episodes.getForPodcast(existing.id, 1).firstOrNull()?.episode?.let { newest ->
                    if (!newest.played && newest.queuePosition == null) episodes.setInInbox(newest.id, true)
                }
            }
            return@withContext podcast
        }
        val feed = fetchFeed(url)
        val id = podcasts.insert(
            Podcast(
                feedUrl = url,
                title = feed.title.ifBlank { url },
                author = feed.author,
                description = feed.description,
                imageUrl = feed.imageUrl,
                lastRefreshedAt = System.currentTimeMillis(),
                fundingUrl = feed.fundingUrl,
                fundingText = feed.fundingText,
                personsJson = Persons.toJson(feed.persons),
                complete = feed.complete,
            ),
        )
        saveEpisodes(id, url, feed, if (inboxNewest) InboxMode.NEWEST else InboxMode.NONE, upload = true)
        requireNotNull(podcasts.get(id))
    }

    /**
     * Removes the podcast, except episodes that are queued, partly played (Continue listening) or
     * loaded in the player ([keepId]): those stay (podcast hidden, no refreshes) until played or
     * removed from the queue.
     */
    suspend fun unsubscribe(podcastId: Long, keepId: String?) = withContext(Dispatchers.IO) {
        queueLock.withLock {
            val keep = keepId.orEmpty()
            val dropping = episodes.unqueuedForPodcast(podcastId, keep)
            // Already hidden with nothing to drop (a show kept for its queued or half-played episodes,
            // checked again at every app start): nothing to do.
            if (dropping.isEmpty() && podcasts.get(podcastId)?.subscribed == false) return@withLock
            for (e in dropping) {
                if (e.downloadState != DownloadState.NONE) {
                    DownloadWorker.cancel(context, e.id)
                    e.localPath?.let { File(it).delete() }
                }
            }
            val total = episodes.idsForPodcast(podcastId).size
            if (dropping.size == total) {
                podcasts.delete(podcastId) // episodes cascade
            } else {
                db.withTransaction {
                    podcasts.setSubscribed(podcastId, false)
                    episodes.deleteUnqueuedForPodcast(podcastId, keep)
                }
                Log.i(TAG, "Unsubscribed from $podcastId, keeping ${total - dropping.size} queued/playing episode(s)")
            }
        }
    }

    // ---- Preview (look at a podcast without subscribing) ----

    /** Opening and closing previews one at a time, so a quick back-and-reopen can't delete the page being opened. */
    private val previewLock = Mutex()

    /**
     * Loads a podcast for a look before subscribing. A podcast already in the library (subscribed, or
     * kept for its queued episodes) is reused and, if unsubscribed, refreshed so every episode shows.
     * Otherwise the feed is stored as an unsubscribed podcast: no Inbox, no refreshes, not in the
     * library lists. Its episodes can be played, queued and downloaded like any other.
     * Call [closePreview] when leaving its page.
     */
    suspend fun preview(rawUrl: String): Podcast = withContext(Dispatchers.IO) {
        previewLock.withLock {
            val url = normalizeUrl(rawUrl)
            val existing = findPodcast(url)
            if (existing != null) {
                if (!existing.subscribed) {
                    runCatching { refresh(existing, InboxMode.NONE) }
                        .onFailure { if (it is CancellationException) throw it }
                }
                existing
            } else {
                val feed = fetchFeed(url)
                val id = podcasts.insert(
                    Podcast(
                        feedUrl = url,
                        title = feed.title.ifBlank { url },
                        author = feed.author,
                        description = feed.description,
                        imageUrl = feed.imageUrl,
                        lastRefreshedAt = System.currentTimeMillis(),
                        fundingUrl = feed.fundingUrl,
                        fundingText = feed.fundingText,
                        personsJson = Persons.toJson(feed.persons),
                        complete = feed.complete,
                        subscribed = false,
                        dirty = false, // uploaded only once one of its episodes is (see SyncEngine)
                    ),
                )
                saveEpisodes(id, url, feed, InboxMode.NONE, upload = false)
                requireNotNull(podcasts.get(id))
            }
        }
    }

    /**
     * After leaving a previewed podcast's page without subscribing: keeps only its queued episodes
     * and the one loaded in the player ([keepId]), the same as unsubscribing; with none, removes it.
     * Does nothing if the podcast was subscribed meanwhile.
     */
    suspend fun closePreview(podcastId: Long, keepId: String?) {
        previewLock.withLock {
            val p = podcasts.get(podcastId)
            if (p != null && !p.subscribed) unsubscribe(podcastId, keepId)
        }
    }

    /** Tidies previews left behind if the app was closed while one was open. */
    suspend fun closeAllPreviews(keepId: String?, except: Set<Long>) {
        for (id in podcasts.unsubscribedIds()) if (id !in except) closePreview(id, keepId)
    }

    /** Deletes unsubscribed podcasts whose last queued episode has gone. */
    private suspend fun pruneUnsubscribed() {
        for (e in podcasts.downloadsOfUnusedUnsubscribed()) {
            DownloadWorker.cancel(context, e.id)
            e.localPath?.let { File(it).delete() }
        }
        val n = podcasts.deleteUnusedUnsubscribed()
        if (n > 0) Log.i(TAG, "Removed $n unsubscribed podcast(s) with nothing left in the queue")
    }

    // ---- Inbox ----

    fun observeInbox() = episodes.observeInbox()

    suspend fun archive(id: String) = episodes.setInInbox(id, false)

    suspend fun archiveInbox(): Int = episodes.archiveInbox()

    // ---- Queue ----
    // The queue is small (tens of episodes), so every change simply renumbers it 0..n-1.

    private val queueLock = Mutex()

    fun observeQueue() = episodes.observeQueue()

    suspend fun queueHead() = episodes.queueHead()

    private suspend fun writeQueue(ids: List<String>) {
        db.withTransaction {
            ids.forEachIndexed { i, id -> episodes.setQueuePosition(id, i.toLong()) }
        }
    }

    /** Inserts (or moves) [id] at [index] of the queue, clamped to its length. */
    private suspend fun queueAt(id: String, index: (List<String>) -> Int) {
        queueLock.withLock {
            val ids = episodes.queueIds().toMutableList()
            ids.remove(id)
            ids.add(index(ids).coerceIn(0, ids.size), id)
            db.withTransaction {
                writeQueue(ids)
                episodes.setInInbox(id, false)
            }
        }
        autoDownload(id)
    }

    /** Playing an episode puts it at the top of the queue; whatever was playing moves down one. */
    suspend fun queueFirst(id: String) = queueAt(id) { 0 }

    /** After the episode at the top (the one playing, usually). */
    suspend fun queueNext(id: String) = queueAt(id) { if (it.isEmpty()) 0 else 1 }

    suspend fun queueLast(id: String) = queueAt(id) { it.size }

    suspend fun removeFromQueue(id: String) {
        queueLock.withLock {
            episodes.setQueuePosition(id, null)
            pruneUnsubscribed()
        }
    }

    /** Saves a new order after a drag; ids not in [ordered] (changed meanwhile) keep their relative order at the end. */
    suspend fun reorderQueue(ordered: List<String>) {
        queueLock.withLock {
            val current = episodes.queueIds()
            val known = ordered.filter { it in current }
            writeQueue(known + current.filter { it !in known })
        }
    }

    /**
     * After a sync brought in queue changes from another device: renumbers the queue 0..n-1 in its
     * current order, removes what's played, prunes unneeded hidden podcasts, and downloads newly
     * queued episodes (per the setting).
     */
    suspend fun tidyQueueAfterSync(newlyQueued: Collection<String>) {
        queueLock.withLock {
            writeQueue(episodes.queueIds())
            pruneUnsubscribed()
        }
        for (id in newlyQueued) autoDownload(id)
    }

    suspend fun setPinned(podcastId: Long, pinned: Boolean) = podcasts.setPinned(podcastId, pinned)

    private suspend fun autoDownload(id: String) {
        if (!Settings.autoDownloadQueued(context)) return
        val e = episodes.get(id) ?: return
        if (overStorageCap()) {
            Log.i(TAG, "Not downloading \"${e.title}\" automatically: downloads are at the storage limit")
            return
        }
        if (e.played) return
        val fileOk = e.downloadState == DownloadState.DONE && e.localPath?.let { File(it).exists() } == true
        if (e.downloadState == DownloadState.NONE || e.downloadState == DownloadState.FAILED ||
            (e.downloadState == DownloadState.DONE && !fileOk)
        ) {
            download(id)
        }
    }

    /** Reads the feed list from an OPML file. Throws IOException with a readable message if it can't. */
    suspend fun readOpml(uri: Uri): List<OpmlFeed> = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri)?.buffered()
                ?: throw IOException("The file couldn't be opened")
            stream.use {
                if (isIntuneEncrypted(it)) {
                    Log.w(TAG, "OPML import: $uri is encrypted by Microsoft Intune app protection")
                    throw IOException(
                        "This file is encrypted by your work profile's Microsoft Intune protection, so other " +
                            "apps can't read it. It was probably saved through a work app (Outlook, Teams, " +
                            "OneDrive or Edge). Export it again from Podcast Addict straight to Downloads.",
                    )
                }
                OpmlParser.parse(it)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "OPML import: couldn't read $uri", e)
            throw if (e is IOException) e else IOException("That doesn't look like an OPML subscriptions file", e)
        }
    }

    /**
     * Files saved by Intune-managed apps start with a "MSMAMARPCRYPT" header and are AES-encrypted.
     * Peeks at the start of [input] (which must support mark/reset) without consuming it.
     */
    private fun isIntuneEncrypted(input: java.io.InputStream): Boolean {
        val magic = "MSMAMARPCRYPT".toByteArray()
        val head = ByteArray(32)
        input.mark(head.size)
        val n = input.read(head).coerceAtLeast(0)
        input.reset()
        return String(head, 0, n, Charsets.ISO_8859_1).contains(String(magic, Charsets.ISO_8859_1))
    }

    /**
     * Subscribes to every feed in the list, [parallelism] at a time. One bad feed never stops the
     * rest; each failure is logged and reported back by title. [onProgress] gets (finished, total).
     */
    suspend fun importFeeds(
        feeds: List<OpmlFeed>,
        parallelism: Int = 4,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportResult = withContext(Dispatchers.IO) {
        val unique = feeds.distinctBy { normalizeUrl(it.url) }
        val gate = Semaphore(parallelism)
        val done = AtomicInteger(0)
        val outcomes = coroutineScope {
            unique.map { f ->
                async {
                    gate.withPermit {
                        val failure = subscribeWithRetry(f)
                        onProgress(done.incrementAndGet(), unique.size)
                        failure
                    }
                }
            }.awaitAll()
        }
        val failed = outcomes.filterNotNull()
        Log.i(TAG, "OPML import finished: ${unique.size - failed.size} of ${unique.size} added")
        ImportResult(added = unique.size - failed.size, failed = failed)
    }

    /**
     * Network errors are retried (phones often drop connections while switching between Wi-Fi and
     * mobile data); anything else, like a broken feed, fails at once. Returns the title on failure.
     */
    private suspend fun subscribeWithRetry(f: OpmlFeed): String? {
        val retryDelaysMs = longArrayOf(2_000, 5_000)
        for (attempt in 0..retryDelaysMs.size) {
            try {
                subscribe(f.url, inboxNewest = false)
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: FeedFormatException) {
                Log.w(TAG, "OPML import: couldn't add \"${f.title}\" (${f.url})", e)
                return f.title
            } catch (e: IOException) {
                if (attempt < retryDelaysMs.size) {
                    Log.w(TAG, "OPML import: \"${f.title}\" failed (${e.message}), retrying")
                    delay(retryDelaysMs[attempt])
                } else {
                    Log.w(TAG, "OPML import: couldn't add \"${f.title}\" (${f.url})", e)
                }
            } catch (e: Exception) {
                Log.w(TAG, "OPML import: couldn't add \"${f.title}\" (${f.url})", e)
                return f.title
            }
        }
        return f.title
    }

    // ---- Per-podcast playback settings ----

    /** Changes a podcast's playback settings (a null field means "use the app default"). */
    suspend fun updatePodcastPlayback(id: Long, change: (Podcast) -> Podcast) {
        val p = podcasts.get(id) ?: return
        val n = change(p)
        podcasts.updatePlayback(id, n.speed, n.smartSpeed, n.voiceBoost, n.skipBackMs, n.skipForwardMs)
    }

    // ---- Refresh ----

    /** Returns the number of new episodes found. */
    suspend fun refresh(podcast: Podcast, inbox: InboxMode = InboxMode.ALL_NEW): Int = withContext(Dispatchers.IO) {
        val feed = fetchFeed(podcast.feedUrl)
        // Only the feed's fields: [podcast] may be stale, and its settings may have changed since.
        podcasts.updateMeta(
            id = podcast.id,
            title = feed.title.ifBlank { podcast.title },
            author = feed.author.ifBlank { podcast.author },
            description = feed.description.ifBlank { podcast.description },
            imageUrl = feed.imageUrl ?: podcast.imageUrl,
            refreshedAt = System.currentTimeMillis(),
            fundingUrl = feed.fundingUrl,
            fundingText = feed.fundingText,
            personsJson = Persons.toJson(feed.persons),
            complete = feed.complete,
        )
        // Rules use the podcast as it is now (they may have changed since [podcast] was read).
        val rules = podcasts.get(podcast.id) ?: podcast
        saveEpisodes(podcast.id, podcast.feedUrl, feed, inbox, upload = podcast.subscribed, rules = rules.takeIf { it.subscribed })
    }

    suspend fun refresh(podcastId: Long): Int = podcasts.get(podcastId)?.let { refresh(it) } ?: 0

    /**
     * Refreshes every feed; one broken feed doesn't stop the rest. Finished shows (phase 10:
     * podcast:updateFrequency complete, itunes:complete) are skipped; their page's refresh still works.
     */
    suspend fun refreshAll(): Int {
        var total = 0
        for (p in podcasts.getAll().filter { !it.complete }) {
            total += try {
                refresh(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Refresh failed for \"${p.title}\" (${p.feedUrl})", e)
                0
            }
        }
        runCatching { tidyInbox() }.onFailure { Log.w(TAG, "Tidy-up after refresh failed", it) }
        return total
    }

    private fun fetchFeed(url: String): ParsedFeed {
        val conn = Http.open(url)
        try {
            return conn.inputStream.use { FeedParser.parse(it) }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw FeedFormatException("Couldn't read that feed (${e.message})", e)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * [upload]: mark new episodes for the next sync when they matter to other devices (in the New
     * Episodes list, or recent enough for the web player's Latest tab). False for previews.
     */
    private suspend fun saveEpisodes(
        podcastId: Long,
        feedUrl: String,
        feed: ParsedFeed,
        inbox: InboxMode,
        upload: Boolean,
        rules: Podcast? = null,
    ): Int {
        val existing = episodes.idsForPodcast(podcastId).toHashSet()
        val parsed = feed.episodes.map { it.toEntity(podcastId, feedUrl) }.distinctBy { it.id }
        val newest = parsed.filter { it.id !in existing }
        // New-episode rules (phase 7) apply to episodes found by a refresh of a subscribed podcast.
        val actions = if (inbox == InboxMode.ALL_NEW && rules != null) {
            newest.associate { it.id to Automation.actionFor(rules, it.title, it.durationMs) }
        } else {
            emptyMap()
        }
        val inboxIds = when (inbox) {
            InboxMode.ALL_NEW -> newest.filter { (actions[it.id] ?: Automation.NewEpisodeAction.INBOX) == Automation.NewEpisodeAction.INBOX }
                .map { it.id }.toSet()
            InboxMode.NEWEST -> setOfNotNull(newest.maxByOrNull { it.pubDate }?.id)
            InboxMode.NONE -> emptySet()
        }
        val now = System.currentTimeMillis()
        val fresh = newest.map { e ->
            val inInbox = e.id in inboxIds
            val share = upload && (inInbox || e.pubDate > now - AppDatabase.RECENT_MS)
            e.copy(inInbox = inInbox, dirty = share, changedAt = if (share) now else 0)
        }
        db.withTransaction {
            episodes.insertAll(fresh)
            for (e in parsed) {
                if (e.id in existing) {
                    episodes.updateMeta(
                        id = e.id,
                        title = e.title,
                        description = e.description,
                        pubDate = e.pubDate,
                        audioUrl = e.audioUrl,
                        mimeType = e.mimeType,
                        durationMs = e.durationMs,
                        imageUrl = e.imageUrl,
                        chaptersUrl = e.chaptersUrl,
                        transcriptUrl = e.transcriptUrl,
                        transcriptType = e.transcriptType,
                        season = e.season,
                        episodeNo = e.episodeNo,
                        personsJson = e.personsJson,
                    )
                }
            }
        }
        // Rules that queue: oldest first, so the newest ends up first (play next) or last (end of queue).
        for (e in newest.sortedBy { it.pubDate }) {
            when (actions[e.id]) {
                Automation.NewEpisodeAction.QUEUE_NEXT -> queueNext(e.id)
                Automation.NewEpisodeAction.QUEUE_LAST -> queueLast(e.id)
                Automation.NewEpisodeAction.SKIP -> Log.i(TAG, "Rule skipped new episode \"${e.title}\"")
                else -> Unit
            }
        }
        return fresh.size
    }

    // ---- Automation (phase 7) ----

    suspend fun updateRules(id: Long, change: (Podcast) -> Podcast) {
        val p = podcasts.get(id) ?: return
        val n = change(p)
        podcasts.updateRules(
            id = id,
            action = n.newEpisodeAction?.takeIf { it != Automation.NewEpisodeAction.INBOX.key },
            includeWords = n.includeWords?.trim()?.ifBlank { null },
            excludeWords = n.excludeWords?.trim()?.ifBlank { null },
            minMinutes = n.minMinutes?.takeIf { it > 0 },
            maxMinutes = n.maxMinutes?.takeIf { it > 0 },
            inboxDays = n.inboxDays,
            inboxKeep = n.inboxKeep,
        )
        if (n.inboxDays != p.inboxDays || n.inboxKeep != p.inboxKeep) tidyInbox()
    }

    private val tidyLock = Mutex()

    /**
     * Applies the tidy-up rules to New Episodes: archives entries older than the podcast's (or the
     * default) number of days, and beyond its "keep latest" count. Returns how many were archived.
     */
    suspend fun tidyInbox(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        tidyLock.withLock {
            val d = Settings.tidyDefaults(context).value
            val byId = podcasts.getAllIncludingHidden().associateBy { it.id }
            val ids = Automation.toArchive(
                inbox = episodes.inboxEntries(),
                now = now,
                days = { byId[it]?.inboxDays ?: d.inboxDays },
                keep = { byId[it]?.inboxKeep ?: d.inboxKeep },
            )
            if (ids.isNotEmpty()) {
                db.withTransaction { for (id in ids) episodes.setInInbox(id, false) }
                Log.i(TAG, "Tidy-up archived ${ids.size} episode(s) from New Episodes")
            }
            ids.size
        }
    }

    // ---- Smart playlists (phase 7) ----

    val playlists = db.playlists()

    fun observePlaylists() = playlists.observeAll()

    fun selectPlaylistIds(filters: PlaylistFilters, rows: List<PlaylistRow>): List<String> = select(filters, rows)

    private fun select(filters: PlaylistFilters, rows: List<PlaylistRow>): List<String> =
        SmartPlaylists.select(
            filters,
            rows.map {
                PlaylistCandidate(
                    id = it.id,
                    feedKey = feedKey(it.feedUrl),
                    title = it.title,
                    pubDate = it.pubDate,
                    durationMs = it.durationMs,
                    positionMs = it.positionMs,
                    played = it.played,
                    downloaded = it.downloadState == DownloadState.DONE,
                )
            },
            System.currentTimeMillis(),
            ::feedKey,
        )

    /** Episodes by id, in the order given. */
    private suspend fun inOrder(ids: List<String>): List<EpisodeWithPodcast> {
        if (ids.isEmpty()) return emptyList()
        val byId = ids.chunked(500).flatMap { episodes.getManyWithPodcast(it) }.associateBy { it.episode.id }
        return ids.mapNotNull { byId[it] }
    }

    /** The playlist's episodes now (Android Auto). */
    suspend fun playlistEpisodes(filters: PlaylistFilters): List<EpisodeWithPodcast> = withContext(Dispatchers.IO) {
        inOrder(select(filters, episodes.getPlaylistRows()))
    }

    /** The playlist's episodes, kept up to date (the Playlists tab). */
    fun observePlaylistEpisodes(id: String): Flow<List<EpisodeWithPodcast>> =
        combine(playlists.observe(id), episodes.observePlaylistRows()) { p, rows ->
            if (p == null || p.deleted) emptyList() else select(PlaylistJson.parse(p.filtersJson), rows)
        }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    episodes.observeManyWithPodcast(ids.take(500)).map { list ->
                        val byId = list.associateBy { it.episode.id }
                        ids.mapNotNull { byId[it] }
                    }
                }
            }
            .flowOn(Dispatchers.Default)

    /** How many episodes each playlist has now, by playlist id (the Playlists tab). */
    fun observePlaylistCounts(): Flow<Map<String, Int>> =
        combine(playlists.observeAll(), episodes.observePlaylistRows()) { list, rows ->
            list.associate { it.id to select(PlaylistJson.parse(it.filtersJson), rows).size }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /**
     * Phase 13: Browse replaced the Latest tab, so a "Latest" smart playlist (every podcast, any
     * status, newest first) is made once. Its changedAt is 1, so a copy already on the server (made by
     * another phone, renamed or deleted) always wins over this one when they sync.
     */
    suspend fun ensureLatestPlaylist() {
        if (playlists.get(LATEST_PLAYLIST_ID) != null) return
        playlists.upsert(
            Playlist(
                id = LATEST_PLAYLIST_ID,
                name = "Latest",
                position = -1,
                filtersJson = PlaylistJson.toText(PlaylistFilters(status = PlaylistFilters.Status.ANY, sort = PlaylistFilters.Sort.NEWEST, limit = 200)),
                dirty = true,
                changedAt = 1,
            ),
        )
    }

    suspend fun createPlaylist(name: String, filters: PlaylistFilters): String {
        val id = java.util.UUID.randomUUID().toString()
        playlists.upsert(
            Playlist(
                id = id,
                name = name.trim().ifBlank { "Playlist" },
                position = playlists.maxPosition() + 1,
                filtersJson = PlaylistJson.toText(filters),
                dirty = true,
                changedAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    suspend fun updatePlaylist(id: String, name: String, filters: PlaylistFilters) {
        val p = playlists.get(id) ?: return
        playlists.upsert(
            p.copy(
                name = name.trim().ifBlank { p.name },
                filtersJson = PlaylistJson.toText(filters),
                dirty = true,
                changedAt = maxOf(System.currentTimeMillis(), p.changedAt + 1),
            ),
        )
    }

    suspend fun deletePlaylist(id: String) {
        val p = playlists.get(id) ?: return
        playlists.upsert(p.copy(deleted = true, dirty = true, changedAt = maxOf(System.currentTimeMillis(), p.changedAt + 1)))
    }

    /** Adds episodes to the end of the queue, in order (skipping ones already queued). */
    suspend fun queueAllLast(ids: List<String>): Int {
        val queued = episodes.queueIds().toSet()
        val add = ids.filter { it !in queued }
        for (id in add) queueLast(id)
        return add.size
    }

    // ---- Episode state ----

    /** Phase 16: what Undo puts back for one episode. */
    data class EpisodeSnapshot(val id: String, val played: Boolean, val positionMs: Long, val inInbox: Boolean, val queueIndex: Int?)

    suspend fun snapshotEpisodes(ids: List<String>): List<EpisodeSnapshot> = withContext(Dispatchers.IO) {
        val queue = episodes.queueIds()
        ids.mapNotNull { id ->
            val e = episodes.get(id) ?: return@mapNotNull null
            EpisodeSnapshot(id, e.played, e.positionMs, e.inInbox, queue.indexOf(id).takeIf { it >= 0 })
        }
    }

    /**
     * Phase 16 Undo: puts [snaps] back (played, position, New Episodes, place in the queue). Episodes
     * removed meanwhile are skipped. The server keeps a played episode played when an unplayed write
     * still has a position (009_sync_safety), so an unplayed episode goes back at position 0 first,
     * [syncNow] sends that, and only then is its position restored.
     */
    suspend fun restoreEpisodes(snaps: List<EpisodeSnapshot>, syncNow: suspend () -> Unit) {
        val present = snaps.filter { episodes.get(it.id) != null }
        if (present.isEmpty()) return
        var needsSecondStep = false
        queueLock.withLock {
            db.withTransaction {
                for (s in present) {
                    val cur = episodes.get(s.id) ?: continue
                    val unplaying = cur.played && !s.played && s.positionMs > 0
                    if (unplaying) needsSecondStep = true
                    episodes.restoreState(s.id, s.played, if (unplaying) 0 else s.positionMs, s.inInbox)
                }
                val ids = episodes.queueIds().filter { id -> present.none { it.id == id } }.toMutableList()
                for (s in present.filter { it.queueIndex != null }.sortedBy { it.queueIndex }) {
                    ids.add(s.queueIndex!!.coerceIn(0, ids.size), s.id)
                }
                writeQueue(ids)
                for (s in present.filter { it.queueIndex == null }) episodes.setQueuePosition(s.id, null)
            }
        }
        if (needsSecondStep) {
            runCatching { syncNow() }
            for (s in present) if (!s.played && s.positionMs > 0) episodes.restorePosition(s.id, s.positionMs)
        }
    }

    /** Phase 16 Undo for "Delete playlist". */
    suspend fun restorePlaylist(id: String) {
        val p = playlists.get(id) ?: return
        playlists.upsert(p.copy(deleted = false, dirty = true, changedAt = maxOf(System.currentTimeMillis(), p.changedAt + 1)))
    }

    suspend fun setPlayed(id: String, played: Boolean) {
        queueLock.withLock {
            episodes.setPlayed(id, played)
            if (played) pruneUnsubscribed()
        }
    }

    /** Marks many episodes played in one go (phase 7b). Returns how many changed. */
    suspend fun markPlayed(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        return queueLock.withLock {
            var changed = 0
            db.withTransaction {
                for (chunk in ids.chunked(500)) changed += episodes.markPlayed(chunk)
            }
            pruneUnsubscribed()
            changed
        }
    }

    /** Starts a download. Phase 17: refused (false) while downloads are at the storage limit. */
    suspend fun download(id: String): Boolean {
        if (overStorageCap()) {
            Log.i(TAG, "Not downloading $id: downloads are at the storage limit")
            return false
        }
        episodes.setDownload(id, DownloadState.QUEUED, 0, null)
        DownloadWorker.enqueue(context, id)
        return true
    }

    suspend fun deleteDownload(id: String) = withContext(Dispatchers.IO) {
        DownloadWorker.cancel(context, id)
        episodes.get(id)?.localPath?.let { File(it).delete() }
        episodes.clearDownload(id)
    }

    /** Phase 14: how many episodes are downloaded and the space the downloads folder takes, in bytes. */
    suspend fun downloadsUsage(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "episodes")
        val bytes = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        episodes.completedDownloads().size to bytes
    }

    /** Phase 14b: space taken by streamed audio kept for rewinds and resume, in bytes. */
    suspend fun streamCacheBytes(): Long = withContext(Dispatchers.IO) {
        com.earshot.app.playback.StreamCache.sizeBytes(context)
    }

    /** Phase 14b: waiting downloads take the new Download over setting. */
    suspend fun applyDownloadNetworkSetting() = withContext(Dispatchers.IO) {
        val waiting = episodes.anyDownloads()
            .filter { it.downloadState == DownloadState.QUEUED || it.downloadState == DownloadState.DOWNLOADING }
            .map { it.id }
        DownloadWorker.applyNetworkSetting(context, waiting)
    }

    /**
     * Phase 14: Settings > Downloads > Delete all downloads. Stops waiting and running downloads, deletes
     * every downloaded file (and anything else in the downloads folder) and marks the episodes not
     * downloaded. Nothing else changes: queue, positions and played state stay. Returns files deleted.
     */
    suspend fun deleteAllDownloads(): Int = withContext(Dispatchers.IO) {
        cleanupLock.withLock {
            androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("download")
            var n = 0
            for (e in episodes.anyDownloads()) {
                DownloadWorker.cancel(context, e.id)
                if (e.localPath?.let { File(it).delete() } == true) n++
                episodes.clearDownload(e.id)
            }
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "episodes")
            dir.listFiles()?.forEach { if (it.isFile && it.delete()) n++ }
            // Phase 14b: streamed audio kept for rewinds and resume goes too.
            runCatching { com.earshot.app.playback.StreamCache.clear(context) }
            Log.i(TAG, "Deleted all downloads ($n files)")
            n
        }
    }

    private val cleanupLock = Mutex()

    /**
     * Applies the download-retention rules from Settings:
     *  - finished (played) episodes lose their download right away, if "delete when finished" is on;
     *  - unfinished downloads expire N days after the later of download time and last play.
     * [protectedId] is the episode loaded in the player (unless it has ended); it is never touched.
     * Only completed downloads are considered, so queued and in-progress ones are safe.
     * Returns the number of files deleted.
     */
    suspend fun cleanupDownloads(
        protectedId: String?,
        now: Long = System.currentTimeMillis(),
    ): Int = withContext(Dispatchers.IO) {
        cleanupLock.withLock {
            val deleteFinished = Settings.deleteWhenFinished(context)
            val days = Settings.expireDays(context)
            var deleted = 0
            for (e in episodes.completedDownloads()) {
                if (e.id == protectedId) continue
                // Queued episodes never expire; finished ones have already left the queue.
                if (e.queuePosition != null && !e.played) continue
                val file = e.localPath?.let { File(it) }
                var downloadedAt = e.downloadedAt
                if (downloadedAt == 0L) {
                    // Downloaded before v2 tracked this: use the file's own timestamp.
                    downloadedAt = file?.takeIf { it.exists() }?.lastModified()?.takeIf { it > 0 } ?: now
                    episodes.setDownloadedAt(e.id, downloadedAt)
                }
                val reason = when {
                    e.played && deleteFinished -> "finished"
                    !e.played && days > 0 && now - maxOf(downloadedAt, e.lastPlayedAt) > days * DAY_MS ->
                        "unfinished for over $days days"
                    else -> null
                } ?: continue
                file?.delete()
                episodes.clearDownload(e.id)
                deleted++
                Log.i(TAG, "Deleted download of \"${e.title}\" ($reason)")
            }
            deleted += deleteOverCap(protectedId, now)
            deleted + deleteOrphanFiles(now)
        }
    }

    /** Phase 17: deletes downloads until they fit in the storage limit ([StorageCap]). */
    private suspend fun deleteOverCap(protectedId: String?, now: Long): Int {
        val cap = Settings.storageCapGb(context) * StorageCap.GB
        if (cap <= 0) return 0
        val done = episodes.completedDownloads()
        val items = done.mapNotNull { e ->
            val f = e.localPath?.let { File(it) }?.takeIf { it.exists() } ?: return@mapNotNull null
            StorageCap.Item(
                id = e.id,
                bytes = f.length(),
                keep = e.queuePosition != null && !e.played,
                lastUsedAt = maxOf(e.downloadedAt, e.lastPlayedAt),
                downloadedAt = e.downloadedAt,
            )
        }
        val ids = StorageCap.toDelete(items, cap, protectedId, now).toSet()
        for (e in done.filter { it.id in ids }) {
            e.localPath?.let { File(it).delete() }
            episodes.clearDownload(e.id)
            Log.i(TAG, "Deleted download of \"${e.title}\" (over the ${Settings.storageCapGb(context)} GB storage limit)")
        }
        return ids.size
    }

    /** Phase 17: true when downloads already take the storage limit (automatic downloads wait). */
    suspend fun overStorageCap(): Boolean {
        val cap = Settings.storageCapGb(context) * StorageCap.GB
        return cap > 0 && downloadsUsage().second >= cap
    }

    /**
     * Deletes files in the downloads folder that no episode points to any more (for example left
     * behind by an older build). Skips in-progress ".part" files and anything modified in the last
     * 10 minutes, so a download that has just finished but isn't recorded yet is safe.
     */
    private suspend fun deleteOrphanFiles(now: Long): Int {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "episodes")
        val files = dir.listFiles() ?: return 0
        // Compared by file name, so different spellings of the same folder path can never cause a wrong delete.
        val referenced = episodes.completedDownloads().mapNotNull { it.localPath?.let { p -> File(p).name } }.toHashSet()
        var n = 0
        for (f in files) {
            if (!f.isFile || f.name.endsWith(".part") || f.name in referenced) continue
            if (now - f.lastModified() < 10 * 60_000L) continue
            if (f.delete()) {
                n++
                Log.i(TAG, "Deleted orphaned download file ${f.name}")
            }
        }
        return n
    }

    suspend fun fetchChapters(url: String): List<Chapter> = withContext(Dispatchers.IO) {
        ChaptersParser.parse(Http.getText(url))
    }

    companion object {
        fun episodeId(feedUrl: String, guid: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("$feedUrl\n$guid".toByteArray())
            return digest.take(10).joinToString("") { "%02x".format(it) }
        }

        fun normalizeUrl(raw: String): String {
            var u = raw.trim()
            if (u.startsWith("feed://")) u = "https://" + u.removePrefix("feed://")
            if (!u.contains("://")) u = "https://$u"
            return u
        }

        /**
         * For deciding whether two feed addresses are the same feed. Directories often list a feed a
         * little differently from how it was first added (http vs https, "www.", letter case, a
         * trailing slash), e.g. Behind the Bastards is "www.omnycontent.com/.../D64F..." in one and
         * "omnycontent.com/.../d64f..." in another.
         */
        const val LATEST_PLAYLIST_ID = "latest"

        fun feedKey(raw: String): String = normalizeUrl(raw).lowercase()
            .substringAfter("://")
            .removePrefix("www.")
            .trimEnd('/')
    }

    /** The podcast with this feed (including hidden and previewed ones), matched by [feedKey]. */
    suspend fun findPodcast(url: String): Podcast? {
        podcasts.findByFeedUrl(url)?.let { return it }
        val key = feedKey(url)
        return podcasts.getAllIncludingHidden().firstOrNull { feedKey(it.feedUrl) == key }
    }
}

private fun ParsedEpisode.toEntity(podcastId: Long, feedUrl: String) = Episode(
    id = Repository.episodeId(feedUrl, guid),
    podcastId = podcastId,
    guid = guid,
    title = title,
    description = description,
    pubDate = pubDate,
    audioUrl = audioUrl,
    mimeType = mimeType,
    durationMs = durationMs,
    feedDurationMs = durationMs,
    imageUrl = imageUrl,
    chaptersUrl = chaptersUrl,
    transcriptUrl = transcriptUrl,
    transcriptType = transcriptType,
    season = season,
    episodeNo = episodeNo,
    personsJson = Persons.toJson(persons),
)

/** Which newly found episodes go to the Inbox. */
enum class InboxMode {
    /** Every new episode (regular refreshes). */
    ALL_NEW,

    /** Only the newest one (subscribing to a single podcast). */
    NEWEST,

    /** None (OPML import). */
    NONE,
}
