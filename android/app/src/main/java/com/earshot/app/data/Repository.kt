package com.earshot.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.earshot.app.data.db.AppDatabase
import com.earshot.app.data.db.DownloadState
import com.earshot.app.data.db.Episode
import com.earshot.app.data.db.Podcast
import com.earshot.app.data.feed.Chapter
import com.earshot.app.data.feed.ChaptersParser
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
        podcasts.findByFeedUrl(url)?.let { existing ->
            if (existing.subscribed) return@withContext existing
            // Was unsubscribed but kept for its queued episodes: bring it back.
            podcasts.setSubscribed(existing.id, true)
            val podcast = existing.copy(subscribed = true)
            runCatching { refresh(podcast, if (inboxNewest) InboxMode.NEWEST else InboxMode.NONE) }
                .onFailure { if (it is CancellationException) throw it }
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
            ),
        )
        saveEpisodes(id, url, feed, if (inboxNewest) InboxMode.NEWEST else InboxMode.NONE)
        requireNotNull(podcasts.get(id))
    }

    /**
     * Removes the podcast, except episodes that are queued or loaded in the player ([keepId]):
     * those stay (podcast hidden, no refreshes) until played or removed from the queue.
     */
    suspend fun unsubscribe(podcastId: Long, keepId: String?) = withContext(Dispatchers.IO) {
        queueLock.withLock {
            val keep = keepId.orEmpty()
            val dropping = episodes.unqueuedForPodcast(podcastId, keep)
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

    private suspend fun autoDownload(id: String) {
        if (!Settings.autoDownloadQueued(context)) return
        val e = episodes.get(id) ?: return
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
        )
        saveEpisodes(podcast.id, podcast.feedUrl, feed, inbox)
    }

    suspend fun refresh(podcastId: Long): Int = podcasts.get(podcastId)?.let { refresh(it) } ?: 0

    /** Refreshes every feed; one broken feed doesn't stop the rest. */
    suspend fun refreshAll(): Int {
        var total = 0
        for (p in podcasts.getAll()) {
            total += try {
                refresh(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Refresh failed for \"${p.title}\" (${p.feedUrl})", e)
                0
            }
        }
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

    private suspend fun saveEpisodes(podcastId: Long, feedUrl: String, feed: ParsedFeed, inbox: InboxMode): Int {
        val existing = episodes.idsForPodcast(podcastId).toHashSet()
        val parsed = feed.episodes.map { it.toEntity(podcastId, feedUrl) }.distinctBy { it.id }
        val newest = parsed.filter { it.id !in existing }
        val inboxIds = when (inbox) {
            InboxMode.ALL_NEW -> newest.map { it.id }.toSet()
            InboxMode.NEWEST -> setOfNotNull(newest.maxByOrNull { it.pubDate }?.id)
            InboxMode.NONE -> emptySet()
        }
        val fresh = newest.map { if (it.id in inboxIds) it.copy(inInbox = true) else it }
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
                    )
                }
            }
        }
        return fresh.size
    }

    // ---- Episode state ----

    suspend fun setPlayed(id: String, played: Boolean) {
        queueLock.withLock {
            episodes.setPlayed(id, played)
            if (played) pruneUnsubscribed()
        }
    }

    suspend fun download(id: String) {
        episodes.setDownload(id, DownloadState.QUEUED, 0, null)
        DownloadWorker.enqueue(context, id)
    }

    suspend fun deleteDownload(id: String) = withContext(Dispatchers.IO) {
        DownloadWorker.cancel(context, id)
        episodes.get(id)?.localPath?.let { File(it).delete() }
        episodes.clearDownload(id)
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
            deleted + deleteOrphanFiles(now)
        }
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
    imageUrl = imageUrl,
    chaptersUrl = chaptersUrl,
    transcriptUrl = transcriptUrl,
    transcriptType = transcriptType,
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
