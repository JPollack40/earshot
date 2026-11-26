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
import com.earshot.app.data.feed.OpmlParser
import com.earshot.app.data.feed.ParsedEpisode
import com.earshot.app.data.feed.ParsedFeed
import com.earshot.app.data.net.Http
import com.earshot.app.work.DownloadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class ImportResult(val added: Int, val failed: Int)

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

    suspend fun importOpml(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val feeds = context.contentResolver.openInputStream(uri)?.use { OpmlParser.parse(it) }.orEmpty()
        var added = 0
        var failed = 0
        for (f in feeds) {
            try {
                subscribe(f.url)
                added++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            }
        }
        ImportResult(added, failed)
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
            throw IOException("Couldn't read that feed (${e.message})", e)
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
        episodes.setDownload(id, DownloadState.NONE, 0, null)
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
