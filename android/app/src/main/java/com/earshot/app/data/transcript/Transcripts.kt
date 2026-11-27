package com.earshot.app.data.transcript

import android.content.Context
import android.util.Log
import com.earshot.app.EarshotApp
import com.earshot.app.data.EpisodeSearch
import com.earshot.app.data.db.Bookmark
import com.earshot.app.data.db.Episode
import com.earshot.app.data.db.EpisodeWithPodcast
import com.earshot.app.data.db.Transcript
import com.earshot.app.data.net.Http
import com.earshot.app.work.TranscribeWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val TAG = "Earshot"

/** A transcript ready to show: segments parsed from storage. */
data class LoadedTranscript(val episodeId: String, val segments: List<Segment>, val timed: Boolean, val source: String) {
    fun info() = MarkdownExport.TranscriptInfo(segments, timed, source)
}

/** Where a Gemini transcription has got to. [part] parts of [parts] are done (parts = 0 if unknown). */
sealed interface TranscribeState {
    data object Waiting : TranscribeState
    data class Running(val part: Int, val parts: Int) : TranscribeState
    data class Failed(val message: String) : TranscribeState
}

/** One episode's search matches: segment start times and their text. */
data class TranscriptHit(
    val episodeId: String,
    val title: String,
    val podcastTitle: String,
    val artworkUrl: String?,
    val matches: List<Segment>,
)

/**
 * Transcripts and bookmarks (phase 6).
 *
 * Transcripts come from the feed (Podcasting 2.0, free; saved in the background for queued, new and
 * played episodes) or from Gemini 3.5 Transcribe, only when asked ([requestTranscription], which runs
 * [TranscribeWorker]). Both are saved in Room and synced through Supabase.
 * Bookmarks keep a moment, the 60 s of transcript before it, and an optional note.
 */
class Transcripts(private val app: EarshotApp) {
    private val dao get() = app.db.transcripts()
    private val bookmarkDao get() = app.db.bookmarks()
    private val episodeDao get() = app.db.episodes()
    private val prefs = app.getSharedPreferences("transcripts", Context.MODE_PRIVATE)
    private val feedLock = Mutex()

    // ------------------------------------------------------------ reading

    fun observe(episodeId: String): Flow<LoadedTranscript?> = dao.observe(episodeId)
        .distinctUntilChanged()
        .map { it?.let(::load) }
        .flowOn(Dispatchers.Default)

    suspend fun get(episodeId: String): LoadedTranscript? = withContext(Dispatchers.Default) { dao.get(episodeId)?.let(::load) }

    private fun load(t: Transcript): LoadedTranscript? = try {
        LoadedTranscript(t.episodeId, TranscriptFormat.fromJson(t.segmentsJson), t.timed, t.source)
    } catch (e: Exception) {
        Log.w(TAG, "Unreadable transcript for ${t.episodeId}", e)
        null
    }

    /** Saves a transcript made or fetched on this phone (uploaded by the next sync). */
    suspend fun save(episodeId: String, source: String, segments: List<Segment>, timed: Boolean) {
        val json = TranscriptFormat.toJson(segments)
        dao.upsert(
            Transcript(
                episodeId = episodeId,
                source = source,
                timed = timed,
                segmentsJson = json,
                plain = TranscriptFormat.plain(segments),
                dirty = true,
                changedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** A transcript from another device (not uploaded again). */
    suspend fun applyRemote(episodeId: String, source: String, timed: Boolean, segments: JSONArray, changedAt: Long) {
        val local = dao.get(episodeId)
        if (local != null && local.changedAt >= changedAt) return
        val parsed = TranscriptFormat.fromJsonArray(segments)
        dao.upsert(
            Transcript(
                episodeId = episodeId,
                source = source,
                timed = timed,
                segmentsJson = segments.toString(),
                plain = TranscriptFormat.plain(parsed),
                dirty = false,
                changedAt = changedAt,
            ),
        )
    }

    // ------------------------------------------------------------ feed transcripts

    /**
     * Fetches and saves the feed's transcript for [episode] if it has one and none is saved yet.
     * Returns true when a transcript is saved now (or already was).
     */
    suspend fun ensureFeedTranscript(episode: Episode): Boolean {
        if (dao.get(episode.id) != null) return true
        val url = episode.transcriptUrl ?: return false
        return feedLock.withLock {
            if (dao.get(episode.id) != null) return@withLock true
            val body = Http.getText(url)
            val parsed = withContext(Dispatchers.Default) { TranscriptFormat.parse(body, episode.transcriptType) }
            if (parsed.segments.isEmpty()) throw IllegalStateException("The feed's transcript is empty")
            save(episode.id, SOURCE_FEED, parsed.segments, parsed.timed)
            Log.i(TAG, "Saved feed transcript for ${episode.title} (${parsed.segments.size} segments)")
            true
        }
    }

    /**
     * Saves feed transcripts for queued, new and played episodes that don't have one yet, a few at a
     * time. A transcript that failed is tried again after 3 days.
     */
    suspend fun fetchMissingFeedTranscripts(limit: Int = 25): Int {
        val now = System.currentTimeMillis()
        var saved = 0
        for (e in dao.missingFeedTranscripts(limit * 2)) {
            if (saved >= limit) break
            val failedAt = prefs.getLong("failed_${e.id}", 0)
            if (now - failedAt < RETRY_FAILED_MS) continue
            try {
                if (ensureFeedTranscript(e)) saved++
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Log.w(TAG, "Couldn't fetch the feed transcript for ${e.title}", ex)
                prefs.edit().putLong("failed_${e.id}", now).apply()
            }
        }
        if (saved > 0) Log.i(TAG, "Saved $saved feed transcripts")
        return saved
    }

    fun fetchMissingInBackground(reason: String) {
        app.appScope.launch {
            try {
                fetchMissingFeedTranscripts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Feed transcripts ($reason) failed", e)
            }
        }
    }

    // ------------------------------------------------------------ Gemini

    private val _transcribing = MutableStateFlow<Map<String, TranscribeState>>(emptyMap())
    /** Gemini transcriptions waiting, running or failed, by episode id. */
    val transcribing: StateFlow<Map<String, TranscribeState>> = _transcribing.asStateFlow()

    fun setState(episodeId: String, state: TranscribeState?) {
        _transcribing.update { if (state == null) it - episodeId else it + (episodeId to state) }
    }

    /** Asks for a Gemini transcript of this episode (runs in the background, even if the app closes). */
    fun requestTranscription(episodeId: String) {
        setState(episodeId, TranscribeState.Waiting)
        TranscribeWorker.enqueue(app, episodeId)
    }

    fun cancelTranscription(episodeId: String) {
        TranscribeWorker.cancel(app, episodeId)
        setState(episodeId, null)
    }

    private fun partsFile(episodeId: String) = File(File(app.filesDir, "transcribe").apply { mkdirs() }, "$episodeId.json")

    /**
     * Transcribes [episode] with Deepgram through the "transcribe" Edge Function, then saves it. The
     * function answers in parts (one, with Deepgram); parts already done are kept in a file for a retry.
     */
    suspend fun transcribeWithGemini(episode: Episode, onPart: suspend (done: Int, parts: Int) -> Unit) {
        val supabase = app.sync.supabase
        if (!app.sync.canSync) throw IllegalStateException("Sign in (Settings > Sync) to transcribe episodes")
        val file = partsFile(episode.id)
        val saved = withContext(Dispatchers.IO) { if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrNull() else null }
        val segments = JSONArray()
        var part = 0
        // Parts saved by the first (Gemini) build are ignored: they used a different engine.
        if (saved != null && saved.optString("audioUrl") == episode.audioUrl && saved.optString("engine") == SOURCE_DEEPGRAM) {
            val s = saved.optJSONArray("segments") ?: JSONArray()
            for (i in 0 until s.length()) segments.put(s.get(i))
            part = saved.optInt("next", 0)
        }
        var parts = 1 // Deepgram does the whole episode in one call; the function says if there are more
        onPart(part, parts)
        while (true) {
            val res = supabase.function(
                "transcribe",
                JSONObject()
                    .put("audioUrl", episode.audioUrl)
                    .put("mimeType", episode.mimeType ?: JSONObject.NULL)
                    .put("durationMs", episode.durationMs)
                    .put("part", part),
                readTimeoutMs = 240_000,
            )
            val got = res.optJSONArray("segments") ?: JSONArray()
            for (i in 0 until got.length()) segments.put(got.get(i))
            val reported = res.optInt("parts", 0)
            if (reported > 0) parts = reported
            part++
            val done = res.optBoolean("done", false)
            withContext(Dispatchers.IO) {
                file.writeText(JSONObject().put("audioUrl", episode.audioUrl).put("engine", SOURCE_DEEPGRAM).put("next", part).put("segments", segments).toString())
            }
            onPart(part, if (done) part else maxOf(parts, part + 1))
            if (done) break
            if (part > 200) throw IllegalStateException("The episode seems endless; stopped after 200 parts")
        }
        val list = TranscriptFormat.fromJsonArray(segments)
        if (list.isEmpty()) throw IllegalStateException("No speech was found in this episode")
        save(episode.id, SOURCE_DEEPGRAM, list, timed = true)
        withContext(Dispatchers.IO) { file.delete() }
        Log.i(TAG, "Deepgram transcript saved for ${episode.title}: ${list.size} segments in $part parts")
        app.sync.request("transcript")
    }

    // ------------------------------------------------------------ search

    /** Search across every saved transcript (words in any order; "quoted phrases" as written). */
    suspend fun search(query: String, maxEpisodes: Int = 50): List<TranscriptHit> = withContext(Dispatchers.Default) {
        val terms = EpisodeSearch.terms(query.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"'))
        if (terms.isEmpty()) return@withContext emptyList()
        val sorted = terms.sortedByDescending { it.length }
        val like = (sorted.take(6) + List(6) { "" }).take(6).map { it.replace("\\", "").replace("%", "").replace("_", "") }
        val rows = dao.search(like[0], like[1], like[2], like[3], like[4], like[5], maxEpisodes * 2)
        val bookmarksByEpisode = bookmarkDao.getAll().associateBy { it.episodeId }
        val out = mutableListOf<TranscriptHit>()
        for (t in rows) {
            if (out.size >= maxEpisodes) break
            val loaded = load(t) ?: continue
            val idx = TranscriptFormat.matchingSegments(loaded.segments, terms)
            if (idx.isEmpty()) continue
            val ep = episodeDao.getWithPodcast(t.episodeId)
            val b = bookmarksByEpisode[t.episodeId]
            val title = ep?.episode?.title ?: b?.episodeTitle ?: continue
            out += TranscriptHit(
                episodeId = t.episodeId,
                title = title,
                podcastTitle = ep?.podcastTitle ?: b?.podcastTitle.orEmpty(),
                artworkUrl = ep?.artworkUrl ?: b?.imageUrl,
                matches = idx.take(20).map { loaded.segments[it] },
            )
        }
        out
    }

    // ------------------------------------------------------------ bookmarks

    fun observeBookmarks(): Flow<List<Bookmark>> = bookmarkDao.observeAll()
    fun observeBookmarks(episodeId: String): Flow<List<Bookmark>> = bookmarkDao.observeForEpisode(episodeId)
    suspend fun bookmarksFor(episodeId: String) = bookmarkDao.getForEpisode(episodeId)
    suspend fun allBookmarks() = bookmarkDao.getAll()

    /**
     * Bookmarks [positionMs] in an episode: the quote runs from 60 s before to 5 s after. Also fetches
     * the feed's transcript (free) if there is one and it isn't saved yet.
     */
    suspend fun addBookmark(episodeId: String, positionMs: Long): Bookmark? {
        val ep = episodeDao.getWithPodcast(episodeId) ?: return null
        val podcast = app.repository.podcasts.get(ep.episode.podcastId)
        val now = System.currentTimeMillis()
        val pos = positionMs.coerceAtLeast(0)
        val b = Bookmark(
            id = UUID.randomUUID().toString(),
            episodeId = episodeId,
            feedUrl = podcast?.feedUrl.orEmpty(),
            episodeTitle = ep.episode.title,
            podcastTitle = ep.podcastTitle,
            imageUrl = ep.artworkUrl,
            audioUrl = ep.episode.audioUrl,
            positionMs = pos,
            startMs = (pos - QUOTE_BEFORE_MS).coerceAtLeast(0),
            endMs = pos + QUOTE_AFTER_MS,
            createdAt = now,
            dirty = true,
            changedAt = now,
        )
        bookmarkDao.upsert(b)
        app.appScope.launch {
            try {
                ensureFeedTranscript(ep.episode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't fetch the feed transcript after bookmarking", e)
            }
        }
        return b
    }

    suspend fun updateBookmark(b: Bookmark) {
        bookmarkDao.upsert(b.copy(dirty = true, changedAt = System.currentTimeMillis()))
    }

    suspend fun deleteBookmark(b: Bookmark) {
        bookmarkDao.upsert(b.copy(deleted = true, dirty = true, changedAt = System.currentTimeMillis()))
    }

    /** What an export needs about an episode, from the library or (if it's gone) the bookmark. */
    suspend fun episodeInfo(episodeId: String): MarkdownExport.EpisodeInfo? {
        episodeDao.getWithPodcast(episodeId)?.let { return infoOf(it) }
        val b = bookmarkDao.getForEpisode(episodeId).firstOrNull() ?: return null
        return MarkdownExport.EpisodeInfo(episodeId, b.episodeTitle, b.podcastTitle, b.audioUrl)
    }

    fun infoOf(e: EpisodeWithPodcast) =
        MarkdownExport.EpisodeInfo(e.episode.id, e.episode.title, e.podcastTitle, e.episode.audioUrl, e.episode.pubDate)

    suspend fun exportEpisodeBookmarks(episodeId: String): Pair<String, String>? {
        val info = episodeInfo(episodeId) ?: return null
        val md = MarkdownExport.episodeBookmarks(info, bookmarkDao.getForEpisode(episodeId), get(episodeId)?.info())
        return MarkdownExport.fileName("${info.title} bookmarks") to md
    }

    suspend fun exportTranscript(episodeId: String): Pair<String, String>? {
        val info = episodeInfo(episodeId) ?: return null
        val t = get(episodeId) ?: return null
        val md = MarkdownExport.fullTranscript(info, t.info(), bookmarkDao.getForEpisode(episodeId))
        return MarkdownExport.fileName("${info.title} transcript") to md
    }

    suspend fun exportAllBookmarks(): Pair<String, String> {
        val all = bookmarkDao.getAll() // newest first
        val groups = all.groupBy { it.episodeId }.map { (id, list) ->
            val info = episodeDao.getWithPodcast(id)?.let(::infoOf)
                ?: list.first().let { MarkdownExport.EpisodeInfo(id, it.episodeTitle, it.podcastTitle, it.audioUrl) }
            Triple(info, list, get(id)?.info())
        }
        return MarkdownExport.fileName("Earshot bookmarks") to MarkdownExport.allBookmarks(groups, System.currentTimeMillis())
    }

    companion object {
        const val SOURCE_FEED = "feed"
        const val SOURCE_GEMINI = "gemini" // earlier transcripts (phase 6 first build)
        const val SOURCE_DEEPGRAM = "deepgram"

        /** Where a transcript came from, for the transcript page. */
        fun sourceLabel(source: String) = when (source) {
            SOURCE_DEEPGRAM -> "Transcribed by Deepgram"
            SOURCE_GEMINI -> "Transcribed by Gemini"
            else -> "From the podcast's feed"
        }
        const val QUOTE_BEFORE_MS = 60_000L
        const val QUOTE_AFTER_MS = 5_000L
        const val PART_MS = 15 * 60 * 1000L
        private const val RETRY_FAILED_MS = 3L * 24 * 60 * 60 * 1000
    }
}
