package com.earshot.app.data.ai

import android.util.Log
import com.earshot.app.EarshotApp
import com.earshot.app.data.db.Episode
import com.earshot.app.data.db.EpisodeAi
import com.earshot.app.data.feed.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What has been made for one episode so far (each part null until made). */
data class EpisodeAiState(
    val chapters: List<Chapter>? = null,
    val summary: EpisodeSummary? = null,
    val ads: List<AdSpan>? = null,
)

/**
 * Phase 11: chapters, summaries and ad spans made from an episode's transcript by the "ai" edge
 * function (Gemini). The function saves each result on the server (public.episode_ai) and sync
 * brings it to the other devices; this phone also keeps it in Room (table episode_ai).
 */
class EpisodeAiStore(private val app: EarshotApp) {
    private val dao get() = app.db.episodeAi()

    /** Kinds being made right now, as "episodeId|kind", so buttons can show progress. */
    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    fun observe(episodeId: String): Flow<EpisodeAiState> = dao.observe(episodeId).map { rows -> stateOf(rows) }

    suspend fun ads(episodeId: String): List<AdSpan> = dao.get(episodeId, AiKinds.ADS)?.let { parseAds(it.dataJson) }.orEmpty()

    /** Generated chapters (the chapter loader's last resort). */
    suspend fun chapters(episodeId: String): List<Chapter> =
        dao.get(episodeId, AiKinds.CHAPTERS)?.let { parseChapters(it.dataJson) }.orEmpty()

    /**
     * Asks the server to make [kind] for [episode] and saves the answer. The transcript is synced
     * first, because the function reads the copy on the server. Throws with a readable message.
     */
    suspend fun generate(episode: Episode, podcastTitle: String, kind: String) {
        if (!app.sync.canSync) throw IllegalStateException("Sign in (Settings > Sync) to use AI features")
        val t = app.db.transcripts().get(episode.id)
            ?: throw IllegalStateException("Get a transcript first (Transcript, then Transcribe)")
        if (!t.timed) throw IllegalStateException("This transcript has no timings. Transcribe the episode instead.")
        val key = "${episode.id}|$kind"
        _running.value = _running.value + key
        try {
            if (t.dirty) app.sync.sync("ai")
            val res = app.sync.supabase.function(
                "ai",
                JSONObject()
                    .put("episodeId", episode.id)
                    .put("kind", kind)
                    .put("title", episode.title)
                    .put("podcast", podcastTitle),
                readTimeoutMs = 180_000,
            )
            val data = res.optJSONObject("data") ?: throw IllegalStateException(res.optString("error").ifBlank { "No answer" })
            val now = System.currentTimeMillis()
            dao.upsert(EpisodeAi(episode.id, kind, data.toString(), res.optString("model"), now, now))
            if (kind == AiKinds.CHAPTERS) app.chapters.invalidate(episode.id)
            Log.i(TAG, "Made $kind for ${episode.title} (${res.optString("model")}, saved=${res.optBoolean("saved")})")
        } finally {
            _running.value = _running.value - key
        }
    }

    /**
     * Phase 13: "Ask about this episode". The answer comes from the transcript on the server (synced
     * first if needed) and isn't saved. Throws with a readable message.
     */
    suspend fun ask(episode: Episode, podcastTitle: String, question: String): EpisodeAnswer {
        if (!app.sync.canSync) throw IllegalStateException("Sign in (Settings > Sync) to use AI features")
        val t = app.db.transcripts().get(episode.id)
            ?: throw IllegalStateException("Get a transcript first (Transcript, then Transcribe)")
        if (!t.timed) throw IllegalStateException("This transcript has no timings. Transcribe the episode instead.")
        if (t.dirty) app.sync.sync("ai")
        val res = app.sync.supabase.function(
            "ai",
            JSONObject()
                .put("episodeId", episode.id)
                .put("kind", "ask")
                .put("question", question)
                .put("title", episode.title)
                .put("podcast", podcastTitle),
            readTimeoutMs = 180_000,
        )
        val data = res.optJSONObject("data") ?: throw IllegalStateException(res.optString("error").ifBlank { "No answer" })
        val a = data.optJSONArray("quotes") ?: JSONArray()
        val quotes = (0 until a.length()).mapNotNull { i ->
            val q = a.optJSONObject(i) ?: return@mapNotNull null
            SummaryPoint(q.optLong("at"), q.optString("text").trim()).takeIf { it.text.isNotEmpty() }
        }
        return EpisodeAnswer(question, data.optString("answer").trim(), quotes, res.optString("model"))
    }

    /** A row from sync (public.episode_ai). Newer rows win. */
    suspend fun applyRemote(o: JSONObject) {
        val episodeId = o.getString("episode_id")
        val kind = o.getString("kind")
        val changedAt = o.optLong("changed_at")
        val mine = dao.get(episodeId, kind)
        if (mine != null && mine.changedAt >= changedAt) return
        val data = o.optJSONObject("data") ?: return
        dao.upsert(EpisodeAi(episodeId, kind, data.toString(), o.optString("model"), o.optLong("created_at"), changedAt))
        if (kind == AiKinds.CHAPTERS) app.chapters.invalidate(episodeId)
    }

    private suspend fun stateOf(rows: List<EpisodeAi>): EpisodeAiState = withContext(Dispatchers.Default) {
        var s = EpisodeAiState()
        for (r in rows) {
            s = when (r.kind) {
                AiKinds.CHAPTERS -> s.copy(chapters = parseChapters(r.dataJson))
                AiKinds.SUMMARY -> s.copy(summary = parseSummary(r.dataJson, r.model))
                AiKinds.ADS -> s.copy(ads = parseAds(r.dataJson))
                else -> s
            }
        }
        s
    }

    companion object {
        private const val TAG = "EpisodeAi"

        fun parseChapters(json: String): List<Chapter> = runCatching {
            val a = JSONObject(json).optJSONArray("chapters") ?: JSONArray()
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").trim()
                if (title.isEmpty()) null else Chapter(startMs = o.optLong("start"), title = title, imageUrl = null, generated = true)
            }.sortedBy { it.startMs }
        }.getOrDefault(emptyList())

        fun parseSummary(json: String, model: String): EpisodeSummary? = runCatching {
            val o = JSONObject(json)
            val a = o.optJSONArray("points") ?: JSONArray()
            val points = (0 until a.length()).mapNotNull { i ->
                val p = a.optJSONObject(i) ?: return@mapNotNull null
                SummaryPoint(p.optLong("at"), p.optString("text").trim()).takeIf { it.text.isNotEmpty() }
            }
            EpisodeSummary(o.optString("summary").trim(), points, model)
        }.getOrNull()

        fun parseAds(json: String): List<AdSpan> = runCatching {
            val a = JSONObject(json).optJSONArray("ads") ?: JSONArray()
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val s = o.optLong("start")
                val e = o.optLong("end")
                if (e > s) AdSpan(s, e, o.optString("label").ifBlank { "Ad" }) else null
            }.sortedBy { it.startMs }
        }.getOrDefault(emptyList())
    }
}
