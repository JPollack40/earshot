package com.earshot.app.data.sync

import androidx.core.content.edit
import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.earshot.app.EarshotApp
import com.earshot.app.data.PlaybackDefaults
import com.earshot.app.data.Settings
import com.earshot.app.data.db.Bookmark
import com.earshot.app.data.db.Episode
import com.earshot.app.data.db.Listen
import com.earshot.app.data.db.Playlist
import com.earshot.app.data.db.Podcast
import com.earshot.app.data.TidyDefaults
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
            combine(
                listOf(
                    podcastDao.observeDirtyCount(),
                    episodeDao.observeDirtyCount(),
                    app.db.transcripts().observeDirtyCount(),
                    app.db.bookmarks().observeDirtyCount(),
                    app.db.playlists().observeDirtyCount(),
                    app.db.listens().observeDirtyCount(),
                ),
            ) { counts -> counts.sum() }
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
                prefs.edit { putLong("last_sync_at", now) }
                _status.value = SyncStatus(running = false, lastSyncAt = now)
                Log.i(TAG, "Sync ($reason): pushed $pushed, pulled $pulled")
                com.earshot.app.data.AppDevices.report(app, supabase, now) // phase 21: for the owner console, hourly
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
        prefs.edit().remove("rev_podcasts").remove("rev_episodes").remove("rev_settings")
            .remove("rev_transcripts").remove("rev_bookmarks").remove("rev_playlists").remove("rev_episode_ai").apply()
    }

    // ---------------------------------------------------------------- push

    private suspend fun push(): Int {
        var n = 0
        val byId = podcastDao.getAllIncludingHidden().associateBy { it.id }

        // Podcasts: changed ones, plus hidden ones whose episodes are about to go up (so the web
        // player knows their title and artwork).
        // Phase 23: the Articles show lives on this phone only (its audio is made here).
        val allDirty = podcastDao.dirty()
        val dirtyPodcasts = allDirty.filter { !com.earshot.app.data.articles.Articles.isLocalFeed(it.feedUrl) }
        if (dirtyPodcasts.isNotEmpty()) upsertPodcasts(dirtyPodcasts)
        for (p in allDirty) podcastDao.markClean(p.id, p.changedAt)
        n += dirtyPodcasts.size

        val pushedPodcastIds = dirtyPodcasts.map { it.id }.toMutableSet()
        for (round in 0 until 200) { // 500 at a time; capped so a row that keeps changing can't loop forever
            val batch = episodeDao.dirty(500)
            if (batch.isEmpty()) break
            val hidden = batch.mapNotNull { byId[it.podcastId] }
                .filter { !it.subscribed && it.id !in pushedPodcastIds && !com.earshot.app.data.articles.Articles.isLocalFeed(it.feedUrl) }.distinctBy { it.id }
            if (hidden.isNotEmpty()) {
                upsertPodcasts(hidden)
                pushedPodcastIds += hidden.map { it.id }
            }
            val up = batch.filter { !com.earshot.app.data.articles.Articles.isArticle(it.id) }.mapNotNull { e -> byId[e.podcastId]?.let { e to it.feedUrl } }
            if (up.isNotEmpty()) upsertEpisodes(up)
            app.db.withTransaction { for (e in batch) episodeDao.markClean(e.id, e.changedAt) }
            n += batch.size
            if (batch.size < 500) break
        }
        return n + pushSettings() + phase6 { pushTranscripts() } + phase6 { pushBookmarks() } +
            phase7 { pushPlaylists() } + phase7 { pushListens() }
    }

    /**
     * The rule columns on podcasts come from supabase/migrations/003_automation_playlists.sql. Until
     * that has been run, podcasts go up without them (logged) so the rest of sync keeps working.
     */
    private var rulesColumns = true

    /**
     * Phase 10 columns (podcasts.ad_trim; episodes.skipped_chapters, season, episode_no) come from
     * supabase/migrations/004_listening_upgrades.sql. Until it has been run, rows go up without them.
     */
    private var phase10Columns = true

    private suspend fun upsertPodcasts(list: List<Podcast>) {
        if (rulesColumns && phase10Columns) {
            try {
                supabase.upsert("podcasts", "user_id,feed_url", JSONArray(list.map { podcastJson(it, rules = true, p10 = true) }))
                return
            } catch (e: SupabaseException) {
                if (!missingSchema(e)) throw e
                phase10Columns = false
                Log.w(TAG, "Sync: podcasts have no ad_trim column yet (run 004_listening_upgrades.sql)")
            }
        }
        if (rulesColumns) {
            try {
                supabase.upsert("podcasts", "user_id,feed_url", JSONArray(list.map { podcastJson(it, rules = true, p10 = false) }))
                return
            } catch (e: SupabaseException) {
                if (!missingSchema(e)) throw e
                rulesColumns = false
                Log.w(TAG, "Sync: podcasts have no rule columns yet (run 003_automation_playlists.sql)")
            }
        }
        supabase.upsert("podcasts", "user_id,feed_url", JSONArray(list.map { podcastJson(it, rules = false, p10 = false) }))
    }

    private suspend fun upsertEpisodes(rows: List<Pair<Episode, String>>) {
        if (phase10Columns) {
            try {
                supabase.upsert("episodes", "user_id,id", JSONArray(rows.map { (e, feed) -> episodeJson(e, feed, p10 = true) }))
                return
            } catch (e: SupabaseException) {
                if (!missingSchema(e)) throw e
                phase10Columns = false
                Log.w(TAG, "Sync: episodes have no phase 10 columns yet (run 004_listening_upgrades.sql)")
            }
        }
        supabase.upsert("episodes", "user_id,id", JSONArray(rows.map { (e, feed) -> episodeJson(e, feed, p10 = false) }))
    }

    private fun missingSchema(e: SupabaseException): Boolean {
        val m = e.message ?: ""
        return e.status == 404 || m.contains("Could not find the") || m.contains("PGRST204") || m.contains("PGRST205")
    }

    /** Like [phase6], for the episode_ai table (006_ai.sql), which only the server writes. */
    private suspend fun phase11(block: suspend () -> Int): Int = try {
        block()
    } catch (e: SupabaseException) {
        if (!missingSchema(e)) throw e
        Log.w(TAG, "Sync: episode_ai table missing (run 006_ai.sql)")
        0
    }

    /** Like [phase6], for the playlists and listens tables (003_automation_playlists.sql). */
    private suspend fun phase7(block: suspend () -> Int): Int = try {
        block()
    } catch (e: SupabaseException) {
        if (!missingSchema(e)) throw e
        Log.w(TAG, "Sync: playlists/listens tables missing (run 003_automation_playlists.sql)")
        0
    }

    private suspend fun pushPlaylists(): Int {
        val dao = app.db.playlists()
        val batch = dao.dirty(500)
        if (batch.isNotEmpty()) {
            val rows = JSONArray()
            for (p in batch) {
                rows.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("position", p.position)
                        .put("filters", JSONObject(p.filtersJson))
                        .put("deleted", p.deleted)
                        .put("changed_at", p.changedAt),
                )
            }
            supabase.upsert("playlists", "user_id,id", rows)
            for (p in batch) dao.markClean(p.id, p.changedAt)
        }
        dao.purgeDeleted(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        return batch.size
    }

    /** This phone's listening history goes up; the web's comes down in [applyListen] (Stats page). */
    private suspend fun pushListens(): Int {
        val dao = app.db.listens()
        var n = 0
        for (round in 0 until 20) {
            val batch = dao.dirty(500)
            if (batch.isEmpty()) break
            val rows = JSONArray()
            for (l in batch) {
                if (com.earshot.app.data.articles.Articles.isArticle(l.episodeId)) continue // phase 23: phone only
                rows.put(
                    JSONObject()
                        .put("id", l.id)
                        .put("episode_id", l.episodeId)
                        .put("feed_url", l.feedUrl)
                        .put("podcast_title", l.podcastTitle)
                        .put("episode_title", l.episodeTitle)
                        .put("started_at", l.startedAt)
                        .put("ended_at", l.endedAt)
                        .put("listened_ms", l.listenedMs)
                        .put("from_ms", l.fromMs)
                        .put("to_ms", l.toMs)
                        .put("speed", l.speed.toDouble())
                        .put("device", l.device)
                        .put("changed_at", l.changedAt),
                )
            }
            if (rows.length() > 0) supabase.upsert("listens", "user_id,id", rows)
            for (l in batch) dao.markClean(l.id, l.changedAt)
            n += batch.size
            if (batch.size < 500) break
        }
        return n
    }

    /**
     * The transcripts and bookmarks tables come from supabase/migrations/002_transcripts_bookmarks.sql.
     * Until that has been run, syncing them is skipped (logged) instead of failing the whole sync.
     */
    private suspend fun phase6(block: suspend () -> Int): Int = try {
        block()
    } catch (e: SupabaseException) {
        if (e.status != 404 && !(e.message ?: "").contains("Could not find the table")) throw e
        Log.w(TAG, "Sync: transcripts/bookmarks tables missing (run 002_transcripts_bookmarks.sql)")
        0
    }

    /** Transcripts are large (tens of kB each), so they go up a few at a time. */
    private suspend fun pushTranscripts(): Int {
        val dao = app.db.transcripts()
        var n = 0
        for (round in 0 until 100) {
            val batch = dao.dirty(10)
            if (batch.isEmpty()) break
            val rows = JSONArray()
            for (t in batch) {
                if (com.earshot.app.data.articles.Articles.isArticle(t.episodeId)) continue // phase 23: phone only
                rows.put(
                    JSONObject()
                        .put("episode_id", t.episodeId)
                        .put("source", t.source)
                        .put("timed", t.timed)
                        .put("segments", JSONArray(t.segmentsJson))
                        .put("changed_at", t.changedAt),
                )
            }
            if (rows.length() > 0) supabase.upsert("transcripts", "user_id,episode_id", rows)
            for (t in batch) dao.markClean(t.episodeId, t.changedAt)
            n += batch.size
            if (batch.size < 10) break
        }
        return n
    }

    private suspend fun pushBookmarks(): Int {
        val dao = app.db.bookmarks()
        var n = 0
        for (round in 0 until 20) {
            val batch = dao.dirty(500)
            if (batch.isEmpty()) break
            val up = batch.filter { !com.earshot.app.data.articles.Articles.isArticle(it.episodeId) } // phase 23: phone only
            if (up.isNotEmpty()) supabase.upsert("bookmarks", "user_id,id", JSONArray(up.map(::bookmarkJson)))
            for (b in batch) dao.markClean(b.id, b.changedAt)
            n += batch.size
            if (batch.size < 500) break
        }
        // Deletions that have reached the server can be forgotten here after a month.
        dao.purgeDeleted(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        return n
    }

    private suspend fun pushSettings(): Int {
        if (!Settings.playbackDirty(app)) return 0
        val changedAt = Settings.playbackChangedAt(app).takeIf { it > 0 } ?: System.currentTimeMillis()
        val d = Settings.playbackDefaults(app).value
        val t = Settings.tidyDefaults(app).value
        // The tidy-up defaults (phase 7) ride in the same JSON as the playback defaults.
        val playback = JSONObject()
            .put("speed", d.speed.toDouble())
            .put("smartSpeed", d.smartSpeed)
            .put("voiceBoost", d.voiceBoost)
            .put("skipBackMs", d.skipBackMs)
            .put("skipForwardMs", d.skipForwardMs)
            .put("inboxDays", t.inboxDays)
            .put("inboxKeep", t.inboxKeep)
            .put("adSkip", Settings.adSkip(app).value)
            .put("resumeRewind", Settings.resumeRewind(app).value)
        supabase.upsert("settings", "user_id", JSONArray().put(JSONObject().put("playback", playback).put("changed_at", changedAt)))
        Settings.markPlaybackClean(app, Settings.playbackChangedAt(app))
        return 1
    }

    // ---------------------------------------------------------------- pull

    private suspend fun pull(): Int {
        var n = 0
        var rulesChanged = false
        n += pullTable("podcasts", "rev_podcasts") { if (applyPodcast(it)) rulesChanged = true }
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
        n += pullTable("settings", "rev_settings") { if (applySettings(it)) rulesChanged = true }
        n += phase7 { pullTable("playlists", "rev_playlists") { applyPlaylist(it) } }
        n += phase7 { pullTable("listens", "rev_listens") { applyListen(it) } }
        // Tidy-up rules changed elsewhere (e.g. in the web player): apply them here now.
        if (rulesChanged) runCatching { repo.tidyInbox() }
        n += phase6 { pullTable("transcripts", "rev_transcripts", pageSize = 10) { o ->
            val episodeId = o.getString("episode_id")
            var segments = o.optJSONArray("segments") ?: JSONArray()
            var timed = o.optBoolean("timed", true)
            // Phase 18-pre: after 012 the text lives in the shared copy (episode_transcripts) and this row is
            // only a pointer. Never save an empty transcript over one this phone has.
            if (segments.length() == 0) {
                val shared = try {
                    supabase.select("episode_transcripts?select=segments,timed&episode_id=eq.${java.net.URLEncoder.encode(episodeId, "UTF-8")}").optJSONObject(0)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null // 011 not run yet
                }
                segments = shared?.optJSONArray("segments") ?: JSONArray()
                if (shared != null) timed = shared.optBoolean("timed", timed)
            }
            if (segments.length() > 0) {
                app.transcripts.applyRemote(
                    episodeId = episodeId,
                    source = o.optString("source", "feed"),
                    timed = timed,
                    segments = segments,
                    changedAt = o.optLong("changed_at"),
                )
            }
        } }
        n += phase6 { pullTable("bookmarks", "rev_bookmarks") { applyBookmark(it) } }
        n += phase11 { pullTable("episode_ai", "rev_episode_ai", pageSize = 50) { app.ai.applyRemote(it) } }
        if (anyEpisode) app.transcripts.fetchMissingInBackground("sync")
        return n
    }

    /** Pages through rows with rev above the last seen, applying each; remembers progress per page. */
    private suspend fun pullTable(table: String, revKey: String, pageSize: Int = 500, apply: suspend (JSONObject) -> Unit): Int {
        var last = prefs.getLong(revKey, 0)
        var n = 0
        // The transcripts table has a generated search column that isn't needed here.
        val columns = if (table == "transcripts") "episode_id,source,timed,segments,changed_at,rev" else "*"
        while (true) {
            val page = supabase.select("$table?select=$columns&rev=gt.$last&order=rev.asc&limit=$pageSize")
            for (i in 0 until page.length()) {
                val o = page.getJSONObject(i)
                apply(o)
                last = o.getLong("rev")
                n++
            }
            prefs.edit { putLong(revKey, last) }
            if (page.length() < pageSize) return n
        }
    }

    /** Returns true if it changed the podcast's tidy-up rules. */
    private suspend fun applyPodcast(o: JSONObject): Boolean {
        val feedUrl = o.getString("feed_url")
        val changedAt = o.optLong("changed_at")
        val subscribed = o.optBoolean("subscribed", true)
        var local = repo.findPodcast(feedUrl)
        if (local == null) {
            if (!subscribed) return false // hidden podcasts come in with their episodes, when needed
            try {
                repo.subscribe(feedUrl, inboxNewest = false) // fetches the feed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A dead or unreachable feed mustn't block every later sync; it's skipped (logged).
                Log.w(TAG, "Sync: couldn't subscribe to $feedUrl added elsewhere", e)
                return false
            }
            local = repo.findPodcast(feedUrl) ?: return false
        } else if (changedAt <= local.changedAt) {
            return false // this phone's copy is as new or newer
        }
        // Rule columns are missing from rows written before 003 was run: keep this phone's values then.
        fun <T> rule(name: String, read: () -> T?, mine: T?): T? = if (o.has(name)) read() else mine
        val inboxDays = rule("inbox_days", { o.optNullableLong("inbox_days")?.toInt() }, local.inboxDays)
        val inboxKeep = rule("inbox_keep", { o.optNullableLong("inbox_keep")?.toInt() }, local.inboxKeep)
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
            action = rule("new_episode_action", { o.optNullableString("new_episode_action") }, local.newEpisodeAction),
            includeWords = rule("include_words", { o.optNullableString("include_words") }, local.includeWords),
            excludeWords = rule("exclude_words", { o.optNullableString("exclude_words") }, local.excludeWords),
            minMinutes = rule("min_minutes", { o.optNullableLong("min_minutes")?.toInt() }, local.minMinutes),
            maxMinutes = rule("max_minutes", { o.optNullableLong("max_minutes")?.toInt() }, local.maxMinutes),
            inboxDays = inboxDays,
            inboxKeep = inboxKeep,
            changedAt = changedAt,
        )
        if (o.has("ad_trim")) podcastDao.applyRemoteAdTrim(local.id, o.optNullableString("ad_trim"))
        when {
            becomesSubscribed -> repo.subscribe(local.feedUrl, inboxNewest = false)
            local.subscribed && !subscribed -> repo.unsubscribe(local.id, keepId = PlaybackService.protectedEpisodeId)
        }
        return inboxDays != local.inboxDays || inboxKeep != local.inboxKeep
    }

    private suspend fun applyPlaylist(o: JSONObject) {
        val dao = app.db.playlists()
        val id = o.getString("id")
        val changedAt = o.optLong("changed_at")
        val local = dao.get(id)
        if (local != null && local.changedAt >= changedAt) return
        dao.upsert(
            Playlist(
                id = id,
                name = o.optString("name"),
                position = o.optInt("position"),
                filtersJson = (o.optJSONObject("filters") ?: JSONObject()).toString(),
                deleted = o.optBoolean("deleted"),
                dirty = false,
                changedAt = changedAt,
            ),
        )
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
                        season = o.optNullableLong("season")?.toInt(),
                        episodeNo = o.optNullableDouble("episode_no"),
                        feedDurationMs = o.optLong("duration_ms"),
                        skippedChapters = skippedChaptersOf(o),
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
        if (o.has("skipped_chapters")) episodeDao.applyRemoteSkippedChapters(id, skippedChaptersOf(o))
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

    /**
     * Listening history recorded elsewhere (the web player), for the Stats page. Rows never change
     * once uploaded, so one already here (including this phone's own, coming back) is left alone.
     */
    private suspend fun applyListen(o: JSONObject) {
        val dao = app.db.listens()
        val id = o.getString("id")
        if (dao.get(id) != null) return
        dao.upsert(
            Listen(
                id = id,
                episodeId = o.optString("episode_id"),
                feedUrl = o.optString("feed_url"),
                podcastTitle = o.optString("podcast_title"),
                episodeTitle = o.optString("episode_title"),
                startedAt = o.optLong("started_at"),
                endedAt = o.optLong("ended_at"),
                listenedMs = o.optLong("listened_ms"),
                fromMs = o.optLong("from_ms"),
                toMs = o.optLong("to_ms"),
                speed = o.optDouble("speed", 1.0).toFloat(),
                device = o.optString("device", "web"),
                dirty = false,
                changedAt = o.optLong("changed_at"),
            ),
        )
    }

    private suspend fun applyBookmark(o: JSONObject) {
        val dao = app.db.bookmarks()
        val id = o.getString("id")
        val changedAt = o.optLong("changed_at")
        val local = dao.get(id)
        if (local != null && local.changedAt >= changedAt) return
        dao.upsert(
            Bookmark(
                id = id,
                episodeId = o.getString("episode_id"),
                feedUrl = o.optString("feed_url"),
                episodeTitle = o.optString("episode_title"),
                podcastTitle = o.optString("podcast_title"),
                imageUrl = o.optNullableString("image_url"),
                audioUrl = o.optNullableString("audio_url"),
                positionMs = o.optLong("position_ms"),
                startMs = o.optLong("start_ms"),
                endMs = o.optLong("end_ms"),
                note = o.optString("note"),
                title = o.optNullableString("title"),
                createdAt = o.optLong("created_at"),
                deleted = o.optBoolean("deleted"),
                dirty = false,
                changedAt = changedAt,
            ),
        )
    }

    /** Returns true if it changed the tidy-up defaults. */
    private fun applySettings(o: JSONObject): Boolean {
        val changedAt = o.optLong("changed_at")
        if (changedAt <= Settings.playbackChangedAt(app)) return false
        val p = o.optJSONObject("playback") ?: return false
        val cur = Settings.playbackDefaults(app).value
        val tidy = Settings.tidyDefaults(app).value
        val newTidy = TidyDefaults(
            inboxDays = p.optInt("inboxDays", tidy.inboxDays),
            inboxKeep = p.optInt("inboxKeep", tidy.inboxKeep),
        )
        Settings.applyRemoteTidy(app, newTidy)
        if (p.has("adSkip")) Settings.applyRemoteAdSkip(app, p.optString("adSkip"))
        if (p.has("resumeRewind")) Settings.applyRemoteResumeRewind(app, p.optBoolean("resumeRewind", true))
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
        return newTidy != tidy
    }

    // ---------------------------------------------------------------- JSON

    private fun skippedChaptersOf(o: JSONObject): String? =
        o.optJSONArray("skipped_chapters")?.takeIf { it.length() > 0 }?.toString()

    private fun podcastJson(p: Podcast, rules: Boolean, p10: Boolean) = JSONObject()
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
        .apply {
            if (rules) {
                put("new_episode_action", p.newEpisodeAction ?: JSONObject.NULL)
                put("include_words", p.includeWords ?: JSONObject.NULL)
                put("exclude_words", p.excludeWords ?: JSONObject.NULL)
                put("min_minutes", p.minMinutes ?: JSONObject.NULL)
                put("max_minutes", p.maxMinutes ?: JSONObject.NULL)
                put("inbox_days", p.inboxDays ?: JSONObject.NULL)
                put("inbox_keep", p.inboxKeep ?: JSONObject.NULL)
            }
            if (p10) put("ad_trim", p.adTrim ?: JSONObject.NULL)
        }

    private fun episodeJson(e: Episode, feedUrl: String, p10: Boolean) = JSONObject()
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
        .apply {
            if (p10) {
                put("skipped_chapters", e.skippedChapters?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONObject.NULL)
                put("season", e.season ?: JSONObject.NULL)
                put("episode_no", e.episodeNo ?: JSONObject.NULL)
            }
        }

    private fun bookmarkJson(b: Bookmark) = JSONObject()
        .put("id", b.id)
        .put("episode_id", b.episodeId)
        .put("feed_url", b.feedUrl)
        .put("episode_title", b.episodeTitle)
        .put("podcast_title", b.podcastTitle)
        .put("image_url", b.imageUrl ?: JSONObject.NULL)
        .put("audio_url", b.audioUrl ?: JSONObject.NULL)
        .put("position_ms", b.positionMs)
        .put("start_ms", b.startMs)
        .put("end_ms", b.endMs)
        .put("note", b.note)
        .put("title", b.title ?: JSONObject.NULL)
        .put("created_at", b.createdAt)
        .put("deleted", b.deleted)
        .put("changed_at", b.changedAt)

    private companion object {
        const val TAG = "Earshot"
        const val MAX_TEXT = 20_000
    }
}

private fun JSONObject.optNullableString(name: String): String? = if (isNull(name)) null else optString(name).ifBlank { null }
private fun JSONObject.optNullableLong(name: String): Long? = if (isNull(name)) null else optLong(name)
private fun JSONObject.optNullableDouble(name: String): Double? = if (isNull(name)) null else optDouble(name)
private fun JSONObject.optNullableBoolean(name: String): Boolean? = if (isNull(name)) null else optBoolean(name)
