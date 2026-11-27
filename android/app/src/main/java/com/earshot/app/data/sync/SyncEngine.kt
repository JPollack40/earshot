package com.earshot.app.data.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.earshot.app.EarshotApp
import com.earshot.app.data.PlaybackDefaults
import com.earshot.app.data.Settings
import com.earshot.app.data.db.Episode
import com.earshot.app.data.db.Podcast
import com.earshot.app.playback.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class SyncStatus(val running: Boolean = false, val lastSyncAt: Long = 0, val error: String? = null)

/**
 * Two-way sync with Supabase (tables in supabase/migrations/001_sync.sql).
 *
 * Push: rows changed on this phone (dirty) are upserted; the server keeps whichever change is newest
 * (changed_at). Pull: rows whose rev is above the last one seen are applied here when they're newer
 * than the local copy. Runs after local changes (10 s after they settle), at app start and resume,
 * after feed refreshes, and from Settings > Sync now.
 */
@OptIn(FlowPreview::class)
class SyncEngine(private val app: EarshotApp) {
    val supabase = Supabase(app)
    private val prefs = app.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val repo get() = app.repository
    private val podcastDao get() = app.db.podcasts()
    private val episodeDao get() = app.db.episodes()

    private val _status = MutableStateFlow(SyncStatus(lastSyncAt = prefs.getLong("last_sync_at", 0)))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val canSync: Boolean get() = supabase.configured && supabase.session.value != null

    /** Starts syncing local changes automatically. Call once from the Application. */
    fun start() {
        app.appScope.launch {
            combine(podcastDao.observeDirtyCount(), episodeDao.observeDirtyCount()) { a, b -> a + b }
                .distinctUntilChanged() // playback saves positions every 5 s; react to new dirty rows, not each save
                .filter { it > 0 }
                .debounce(10_000)
                .collect { sync("local changes") }
        }
        request("app start")
    }

    fun request(reason: String) {
        app.appScope.launch { sync(reason) }
    }

    /** Pushes then pulls. Returns false if not signed in or it failed (see [status]). */
    suspend fun sync(reason: String): Boolean {
        if (!canSync) return false
        return lock.withLock {
            _status.value = _status.value.copy(running = true)
            try {
                val pushed = push()
                val pulled = pull()
                val now = System.currentTimeMillis()
                prefs.edit().putLong("last_sync_at", now).apply()
                _status.value = SyncStatus(running = false, lastSyncAt = now)
                Log.i(TAG, "Sync ($reason): pushed $pushed, pulled $pulled")
                true
            } catch (e: CancellationException) {
                _status.value = _status.value.copy(running = false)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sync ($reason) failed", e)
                _status.value = _status.value.copy(running = false, error = e.message ?: e.toString())
                false
            }
        }
    }

    /** After signing in on this phone: everything is uploaded again and everything pulled from the start. */
    suspend fun resetForNewAccount() {
        prefs.edit().remove("rev_podcasts").remove("rev_episodes").remove("rev_settings").apply()
    }

    // ---------------------------------------------------------------- push

    private suspend fun push(): Int {
        var n = 0
        val byId = podcastDao.getAllIncludingHidden().associateBy { it.id }

        // Podcasts: changed ones, plus hidden ones whose episodes are about to go up (so the web
        // player knows their title and artwork).
        val dirtyPodcasts = podcastDao.dirty()
        if (dirtyPodcasts.isNotEmpty()) {
            supabase.upsert("podcasts", "user_id,feed_url", JSONArray(dirtyPodcasts.map(::podcastJson)))
            for (p in dirtyPodcasts) podcastDao.markClean(p.id, p.changedAt)
            n += dirtyPodcasts.size
        }

        val pushedPodcastIds = dirtyPodcasts.map { it.id }.toMutableSet()
        for (round in 0 until 200) { // 500 at a time; capped so a row that keeps changing can't loop forever
            val batch = episodeDao.dirty(500)
            if (batch.isEmpty()) break
            val hidden = batch.mapNotNull { byId[it.podcastId] }.filter { !it.subscribed && it.id !in pushedPodcastIds }.distinctBy { it.id }
            if (hidden.isNotEmpty()) {
                supabase.upsert("podcasts", "user_id,feed_url", JSONArray(hidden.map(::podcastJson)))
                pushedPodcastIds += hidden.map { it.id }
            }
            val rows = batch.mapNotNull { e -> byId[e.podcastId]?.let { episodeJson(e, it.feedUrl) } }
            supabase.upsert("episodes", "user_id,id", JSONArray(rows))
            app.db.withTransaction { for (e in batch) episodeDao.markClean(e.id, e.changedAt) }
            n += batch.size
            if (batch.size < 500) break
        }
        return n + pushSettings()
    }

    private suspend fun pushSettings(): Int {
        if (!Settings.playbackDirty(app)) return 0
        val changedAt = Settings.playbackChangedAt(app).takeIf { it > 0 } ?: System.currentTimeMillis()
        val d = Settings.playbackDefaults(app).value
        val playback = JSONObject()
            .put("speed", d.speed.toDouble())
            .put("smartSpeed", d.smartSpeed)
            .put("voiceBoost", d.voiceBoost)
            .put("skipBackMs", d.skipBackMs)
            .put("skipForwardMs", d.skipForwardMs)
        supabase.upsert("settings", "user_id", JSONArray().put(JSONObject().put("playback", playback).put("changed_at", changedAt)))
        Settings.markPlaybackClean(app, Settings.playbackChangedAt(app))
        return 1
    }

    // ---------------------------------------------------------------- pull

    private suspend fun pull(): Int {
        var n = 0
        n += pullTable("podcasts", "rev_podcasts") { applyPodcast(it) }
        val newlyQueued = mutableSetOf<String>()
        var anyPlayed = false
        var anyEpisode = false
        n += pullTable("episodes", "rev_episodes") { o ->
            val r = applyEpisode(o)
            if (r != null) {
                anyEpisode = true
                if (r.queued) newlyQueued += o.getString("id")
                if (r.played) anyPlayed = true
            }
        }
        if (anyEpisode) repo.tidyQueueAfterSync(newlyQueued)
        if (anyPlayed) app.cleanupDownloads("sync")
        n += pullTable("settings", "rev_settings") { applySettings(it) }
        return n
    }

    /** Pages through rows with rev above the last seen, applying each; remembers progress per page. */
    private suspend fun pullTable(table: String, revKey: String, apply: suspend (JSONObject) -> Unit): Int {
        var last = prefs.getLong(revKey, 0)
        var n = 0
        while (true) {
            val page = supabase.select("$table?select=*&rev=gt.$last&order=rev.asc&limit=500")
            for (i in 0 until page.length()) {
                val o = page.getJSONObject(i)
                apply(o)
                last = o.getLong("rev")
                n++
            }
            prefs.edit().putLong(revKey, last).apply()
            if (page.length() < 500) return n
        }
    }

    private suspend fun applyPodcast(o: JSONObject) {
        val feedUrl = o.getString("feed_url")
        val changedAt = o.optLong("changed_at")
        val subscribed = o.optBoolean("subscribed", true)
        var local = repo.findPodcast(feedUrl)
        if (local == null) {
            if (!subscribed) return // hidden podcasts come in with their episodes, when needed
            try {
                repo.subscribe(feedUrl, inboxNewest = false) // fetches the feed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A dead or unreachable feed mustn't block every later sync; it's skipped (logged).
                Log.w(TAG, "Sync: couldn't subscribe to $feedUrl added elsewhere", e)
                return
            }
            local = repo.findPodcast(feedUrl) ?: return
        } else if (changedAt <= local.changedAt) {
            return // this phone's copy is as new or newer
        }
        val becomesSubscribed = subscribed && !local.subscribed
        podcastDao.applyRemote(
            id = local.id,
            subscribed = if (becomesSubscribed) false else subscribed,
            pinned = o.optBoolean("pinned"),
            speed = o.optNullableDouble("speed")?.toFloat(),
            smartSpeed = o.optNullableBoolean("smart_speed"),
            voiceBoost = o.optNullableBoolean("voice_boost"),
            skipBackMs = o.optNullableLong("skip_back_ms"),
            skipForwardMs = o.optNullableLong("skip_forward_ms"),
            changedAt = changedAt,
        )
        when {
            becomesSubscribed -> repo.subscribe(local.feedUrl, inboxNewest = false)
            local.subscribed && !subscribed -> repo.unsubscribe(local.id, keepId = PlaybackService.protectedEpisodeId)
        }
    }

    private class Applied(val queued: Boolean, val played: Boolean)

    private suspend fun applyEpisode(o: JSONObject): Applied? {
        val id = o.getString("id")
        val changedAt = o.optLong("changed_at")
        val queuePosition = o.optNullableLong("queue_position")
        val inInbox = o.optBoolean("in_inbox")
        val played = o.optBoolean("played")
        val positionMs = o.optLong("position_ms")
        val local = episodeDao.get(id)
        if (local == null) {
            // Only bring in episodes that need to be visible here: queued, new, or partly played.
            if (queuePosition == null && !inInbox && (played || positionMs == 0L)) return null
            val feedUrl = o.getString("feed_url")
            val podcast = repo.findPodcast(feedUrl) ?: createHiddenPodcast(feedUrl)
            episodeDao.insertAll(
                listOf(
                    Episode(
                        id = id,
                        podcastId = podcast.id,
                        guid = o.getString("guid"),
                        title = o.optString("title"),
                        description = o.optString("description"),
                        pubDate = o.optLong("pub_date"),
                        audioUrl = o.getString("audio_url"),
                        mimeType = o.optNullableString("mime_type"),
                        durationMs = o.optLong("duration_ms"),
                        imageUrl = o.optNullableString("image_url"),
                        chaptersUrl = o.optNullableString("chapters_url"),
                        transcriptUrl = o.optNullableString("transcript_url"),
                        transcriptType = o.optNullableString("transcript_type"),
                        positionMs = positionMs,
                        played = played,
                        lastPlayedAt = o.optLong("last_played_at"),
                        inInbox = inInbox,
                        queuePosition = queuePosition,
                        dirty = false,
                        changedAt = changedAt,
                    ),
                ),
            )
            return Applied(queued = queuePosition != null, played = played)
        }
        if (changedAt <= local.changedAt) return null
        episodeDao.applyRemote(
            id = id,
            positionMs = positionMs,
            played = played,
            lastPlayedAt = o.optLong("last_played_at"),
            inInbox = inInbox,
            queuePosition = queuePosition,
            changedAt = changedAt,
        )
        return Applied(queued = queuePosition != null && local.queuePosition == null, played = played && !local.played)
    }

    /** A podcast this phone doesn't have, for an episode queued elsewhere (kept hidden, like a preview). */
    private suspend fun createHiddenPodcast(feedUrl: String): Podcast {
        val row = supabase.select("podcasts?select=*&feed_url=eq.${URLEncoder.encode(feedUrl, "UTF-8")}&limit=1")
            .optJSONObject(0)
        val id = podcastDao.insert(
            Podcast(
                feedUrl = feedUrl,
                title = row?.optString("title")?.ifBlank { null } ?: feedUrl,
                author = row?.optString("author").orEmpty(),
                description = row?.optString("description").orEmpty(),
                imageUrl = row?.optNullableString("image_url"),
                subscribed = false,
                dirty = false,
                changedAt = row?.optLong("changed_at") ?: 0,
            ),
        )
        return requireNotNull(podcastDao.get(id))
    }

    private fun applySettings(o: JSONObject) {
        val changedAt = o.optLong("changed_at")
        if (changedAt <= Settings.playbackChangedAt(app)) return
        val p = o.optJSONObject("playback") ?: return
        val cur = Settings.playbackDefaults(app).value
        Settings.applyRemotePlayback(
            app,
            PlaybackDefaults(
                speed = p.optDouble("speed", cur.speed.toDouble()).toFloat(),
                smartSpeed = p.optBoolean("smartSpeed", cur.smartSpeed),
                voiceBoost = p.optBoolean("voiceBoost", cur.voiceBoost),
                skipBackMs = p.optLong("skipBackMs", cur.skipBackMs),
                skipForwardMs = p.optLong("skipForwardMs", cur.skipForwardMs),
            ),
            changedAt,
        )
    }

    // ---------------------------------------------------------------- JSON

    private fun podcastJson(p: Podcast) = JSONObject()
        .put("feed_url", p.feedUrl)
        .put("title", p.title)
        .put("author", p.author)
        .put("description", p.description.take(MAX_TEXT))
        .put("image_url", p.imageUrl ?: JSONObject.NULL)
        .put("subscribed", p.subscribed)
        .put("pinned", p.pinned)
        .put("speed", p.speed?.toDouble() ?: JSONObject.NULL)
        .put("smart_speed", p.smartSpeed ?: JSONObject.NULL)
        .put("voice_boost", p.voiceBoost ?: JSONObject.NULL)
        .put("skip_back_ms", p.skipBackMs ?: JSONObject.NULL)
        .put("skip_forward_ms", p.skipForwardMs ?: JSONObject.NULL)
        .put("changed_at", p.changedAt)

    private fun episodeJson(e: Episode, feedUrl: String) = JSONObject()
        .put("id", e.id)
        .put("feed_url", feedUrl)
        .put("guid", e.guid)
        .put("title", e.title)
        .put("description", e.description.take(MAX_TEXT))
        .put("pub_date", e.pubDate)
        .put("audio_url", e.audioUrl)
        .put("mime_type", e.mimeType ?: JSONObject.NULL)
        .put("duration_ms", e.durationMs)
        .put("image_url", e.imageUrl ?: JSONObject.NULL)
        .put("chapters_url", e.chaptersUrl ?: JSONObject.NULL)
        .put("transcript_url", e.transcriptUrl ?: JSONObject.NULL)
        .put("transcript_type", e.transcriptType ?: JSONObject.NULL)
        .put("position_ms", e.positionMs)
        .put("played", e.played)
        .put("last_played_at", e.lastPlayedAt)
        .put("in_inbox", e.inInbox)
        .put("queue_position", e.queuePosition ?: JSONObject.NULL)
        .put("changed_at", e.changedAt)

    private companion object {
        const val TAG = "Earshot"
        const val MAX_TEXT = 20_000
    }
}

private fun JSONObject.optNullableString(name: String): String? = if (isNull(name)) null else optString(name).ifBlank { null }
private fun JSONObject.optNullableLong(name: String): Long? = if (isNull(name)) null else optLong(name)
private fun JSONObject.optNullableDouble(name: String): Double? = if (isNull(name)) null else optDouble(name)
private fun JSONObject.optNullableBoolean(name: String): Boolean? = if (isNull(name)) null else optBoolean(name)
