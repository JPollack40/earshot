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

    suspend fun subscribe(rawUrl: String): Podcast = withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawUrl)
        podcasts.findByFeedUrl(url)?.let { return@withContext it }
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
        saveEpisodes(id, url, feed)
        requireNotNull(podcasts.get(id))
    }

    suspend fun unsubscribe(podcastId: Long) = withContext(Dispatchers.IO) {
        for (e in episodes.downloadsForPodcast(podcastId)) {
            DownloadWorker.cancel(context, e.id)
            e.localPath?.let { File(it).delete() }
        }
        podcasts.delete(podcastId) // episodes cascade
    }

    /** Reads the feed list from an OPML file. Throws IOException with a readable message if it can't. */
    suspend fun readOpml(uri: Uri): List<OpmlFeed> = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("The file couldn't be opened")
            stream.use { OpmlParser.parse(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "OPML import: couldn't read $uri", e)
            throw if (e is IOException) e else IOException("That doesn't look like an OPML file (${e.message})", e)
        }
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
                subscribe(f.url)
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

    // ---- Refresh ----

    /** Returns the number of new episodes found. */
    suspend fun refresh(podcast: Podcast): Int = withContext(Dispatchers.IO) {
        val feed = fetchFeed(podcast.feedUrl)
        podcasts.update(
            podcast.copy(
                title = feed.title.ifBlank { podcast.title },
                author = feed.author.ifBlank { podcast.author },
                description = feed.description.ifBlank { podcast.description },
                imageUrl = feed.imageUrl ?: podcast.imageUrl,
                lastRefreshedAt = System.currentTimeMillis(),
            ),
        )
        saveEpisodes(podcast.id, podcast.feedUrl, feed)
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

    private suspend fun saveEpisodes(podcastId: Long, feedUrl: String, feed: ParsedFeed): Int {
        val existing = episodes.idsForPodcast(podcastId).toHashSet()
        val parsed = feed.episodes.map { it.toEntity(podcastId, feedUrl) }.distinctBy { it.id }
        val fresh = parsed.filter { it.id !in existing }
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

    suspend fun setPlayed(id: String, played: Boolean) = episodes.setPlayed(id, played)

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
            deleted
        }
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
