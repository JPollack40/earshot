package com.earshot.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Current time in ms, in SQL, for stamping a user change ([Episode.changedAt], [Podcast.changedAt]). */
private const val NOW_MS = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"

/** Marks a row as changed by the user, so the next sync uploads it. */
private const val TOUCH = "dirty = 1, changedAt = $NOW_MS"

private const val EPISODE_JOIN =
    "SELECT e.*, p.title AS podcastTitle, p.imageUrl AS podcastImageUrl, p.adTrim AS podcastAdTrim " +
        "FROM episodes e INNER JOIN podcasts p ON p.id = e.podcastId"

@Dao
interface PodcastDao {
    @Query(
        "SELECT p.*, (SELECT COUNT(*) FROM episodes e WHERE e.podcastId = p.id AND e.played = 0) AS unplayedCount " +
            "FROM podcasts p WHERE p.subscribed = 1 ORDER BY p.pinned DESC, p.title COLLATE NOCASE",
    )
    fun observeAllWithCounts(): Flow<List<PodcastWithCount>>

    @Query("SELECT * FROM podcasts WHERE subscribed = 1 ORDER BY pinned DESC, title COLLATE NOCASE")
    suspend fun getAll(): List<Podcast>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun observe(id: Long): Flow<Podcast?>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun get(id: Long): Podcast?

    @Query("SELECT * FROM podcasts")
    suspend fun getAllIncludingHidden(): List<Podcast>

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun findByFeedUrl(feedUrl: String): Podcast?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(podcast: Podcast): Long

    @Update
    suspend fun update(podcast: Podcast)

    /** Feed-provided fields only, so a refresh never overwrites settings changed meanwhile. */
    @Query(
        "UPDATE podcasts SET title = :title, author = :author, description = :description, " +
            "imageUrl = :imageUrl, lastRefreshedAt = :refreshedAt, fundingUrl = :fundingUrl, fundingText = :fundingText, " +
            "personsJson = :personsJson, complete = :complete WHERE id = :id",
    )
    suspend fun updateMeta(
        id: Long,
        title: String,
        author: String,
        description: String,
        imageUrl: String?,
        refreshedAt: Long,
        fundingUrl: String?,
        fundingText: String?,
        personsJson: String?,
        complete: Boolean,
    )

    @Query("UPDATE podcasts SET adTrim = :adTrim, $TOUCH WHERE id = :id")
    suspend fun setAdTrim(id: Long, adTrim: String?)

    @Query("UPDATE podcasts SET adTrim = :adTrim WHERE id = :id")
    suspend fun applyRemoteAdTrim(id: Long, adTrim: String?)

    @Query(
        "UPDATE podcasts SET speed = :speed, smartSpeed = :smartSpeed, voiceBoost = :voiceBoost, " +
            "skipBackMs = :skipBackMs, skipForwardMs = :skipForwardMs, $TOUCH WHERE id = :id",
    )
    suspend fun updatePlayback(
        id: Long,
        speed: Float?,
        smartSpeed: Boolean?,
        voiceBoost: Boolean?,
        skipBackMs: Long?,
        skipForwardMs: Long?,
    )

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT id FROM podcasts WHERE subscribed = 0")
    suspend fun unsubscribedIds(): List<Long>

    // Only a real change counts (tidying a hidden podcast mustn't overwrite a newer change made elsewhere).
    @Query(
        "UPDATE podcasts SET dirty = CASE WHEN subscribed IS NOT :subscribed THEN 1 ELSE dirty END, " +
            "changedAt = CASE WHEN subscribed IS NOT :subscribed THEN $NOW_MS ELSE changedAt END, " +
            "subscribed = :subscribed WHERE id = :id",
    )
    suspend fun setSubscribed(id: Long, subscribed: Boolean)

    @Query("UPDATE podcasts SET pinned = :pinned, $TOUCH WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    /** Automation rules (phase 7); null = not set. */
    @Query(
        "UPDATE podcasts SET newEpisodeAction = :action, includeWords = :includeWords, excludeWords = :excludeWords, " +
            "minMinutes = :minMinutes, maxMinutes = :maxMinutes, inboxDays = :inboxDays, inboxKeep = :inboxKeep, $TOUCH " +
            "WHERE id = :id",
    )
    suspend fun updateRules(
        id: Long,
        action: String?,
        includeWords: String?,
        excludeWords: String?,
        minMinutes: Int?,
        maxMinutes: Int?,
        inboxDays: Int?,
        inboxKeep: Int?,
    )

    // ---- Sync ----

    @Query("SELECT * FROM podcasts WHERE dirty = 1")
    suspend fun dirty(): List<Podcast>

    @Query("SELECT COUNT(*) FROM podcasts WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    /** Clears dirty only if the row hasn't changed again since it was read for upload. */
    @Query("UPDATE podcasts SET dirty = 0 WHERE id = :id AND changedAt = :changedAt")
    suspend fun markClean(id: Long, changedAt: Long)

    /** Applies a change from another device (not marked dirty). */
    @Query(
        "UPDATE podcasts SET subscribed = :subscribed, pinned = :pinned, speed = :speed, smartSpeed = :smartSpeed, " +
            "voiceBoost = :voiceBoost, skipBackMs = :skipBackMs, skipForwardMs = :skipForwardMs, " +
            "newEpisodeAction = :action, includeWords = :includeWords, excludeWords = :excludeWords, " +
            "minMinutes = :minMinutes, maxMinutes = :maxMinutes, inboxDays = :inboxDays, inboxKeep = :inboxKeep, " +
            "changedAt = :changedAt WHERE id = :id",
    )
    suspend fun applyRemote(
        id: Long,
        subscribed: Boolean,
        pinned: Boolean,
        speed: Float?,
        smartSpeed: Boolean?,
        voiceBoost: Boolean?,
        skipBackMs: Long?,
        skipForwardMs: Long?,
        action: String?,
        includeWords: String?,
        excludeWords: String?,
        minMinutes: Int?,
        maxMinutes: Int?,
        inboxDays: Int?,
        inboxKeep: Int?,
        changedAt: Long,
    )

    /** Unsubscribed podcasts are kept only while one of their episodes is queued. */
    @Query(
        "DELETE FROM podcasts WHERE subscribed = 0 AND NOT EXISTS " +
            "(SELECT 1 FROM episodes e WHERE e.podcastId = podcasts.id AND e.queuePosition IS NOT NULL)",
    )
    suspend fun deleteUnusedUnsubscribed(): Int

    /** Downloads belonging to podcasts that [deleteUnusedUnsubscribed] is about to remove. */
    @Query(
        "SELECT e.* FROM episodes e INNER JOIN podcasts p ON p.id = e.podcastId " +
            "WHERE p.subscribed = 0 AND e.downloadState != 0 AND NOT EXISTS " +
            "(SELECT 1 FROM episodes q WHERE q.podcastId = p.id AND q.queuePosition IS NOT NULL)",
    )
    suspend fun downloadsOfUnusedUnsubscribed(): List<Episode>

    /** Subscribed podcasts whose title matches [pattern] (a LIKE pattern, escaped with a backslash), for Android Auto search. */
    @Query(
        "SELECT * FROM podcasts WHERE subscribed = 1 AND title LIKE :pattern ESCAPE '\\' " +
            "ORDER BY pinned DESC, title COLLATE NOCASE",
    )
    suspend fun search(pattern: String): List<Podcast>
}

@Dao
interface EpisodeDao {
    @Query("$EPISODE_JOIN WHERE e.podcastId = :podcastId ORDER BY e.pubDate DESC")
    fun observeForPodcast(podcastId: Long): Flow<List<EpisodeWithPodcast>>

    @Query("$EPISODE_JOIN WHERE p.subscribed = 1 ORDER BY e.pubDate DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<EpisodeWithPodcast>>

    @Query("$EPISODE_JOIN WHERE e.podcastId = :podcastId ORDER BY e.pubDate DESC LIMIT :limit")
    suspend fun getForPodcast(podcastId: Long, limit: Int): List<EpisodeWithPodcast>

    @Query("$EPISODE_JOIN WHERE p.subscribed = 1 ORDER BY e.pubDate DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<EpisodeWithPodcast>

    /** Phase 13 "Continue listening": started (over 30 s in), unfinished, not queued; last played first. */
    @Query("$EPISODE_JOIN WHERE e.played = 0 AND e.positionMs > 30000 AND e.queuePosition IS NULL ORDER BY e.lastPlayedAt DESC LIMIT :limit")
    fun observeContinue(limit: Int): Flow<List<EpisodeWithPodcast>>

    @Query("$EPISODE_JOIN WHERE e.played = 0 AND e.positionMs > 30000 AND e.queuePosition IS NULL ORDER BY e.lastPlayedAt DESC LIMIT :limit")
    suspend fun getContinue(limit: Int): List<EpisodeWithPodcast>

    /** Episodes of subscribed podcasts whose title, or podcast's title, matches [pattern] (LIKE, escaped with a backslash). */
    @Query(
        "$EPISODE_JOIN WHERE p.subscribed = 1 AND (e.title LIKE :pattern ESCAPE '\\' OR p.title LIKE :pattern ESCAPE '\\') " +
            "ORDER BY e.pubDate DESC LIMIT :limit",
    )
    suspend fun search(pattern: String, limit: Int): List<EpisodeWithPodcast>

    // ---- Inbox ----

    @Query("$EPISODE_JOIN WHERE e.inInbox = 1 ORDER BY e.pubDate DESC")
    fun observeInbox(): Flow<List<EpisodeWithPodcast>>

    @Query("$EPISODE_JOIN WHERE e.inInbox = 1 ORDER BY e.pubDate DESC")
    suspend fun getInbox(): List<EpisodeWithPodcast>

    @Query("UPDATE episodes SET inInbox = :inInbox, dirty = CASE WHEN inInbox IS NOT :inInbox THEN 1 ELSE dirty END, " +
            "changedAt = CASE WHEN inInbox IS NOT :inInbox THEN $NOW_MS ELSE changedAt END WHERE id = :id")
    suspend fun setInInbox(id: String, inInbox: Boolean)

    @Query("UPDATE episodes SET inInbox = 0, $TOUCH WHERE inInbox = 1")
    suspend fun archiveInbox(): Int

    /** New Episodes entries, for the tidy-up rules. */
    @Query("SELECT id, podcastId, pubDate FROM episodes WHERE inInbox = 1")
    suspend fun inboxEntries(): List<com.earshot.app.data.Automation.InboxEntry>

    // ---- Smart playlists ----

    /** What playlist filters look at, for every episode of a subscribed podcast. Re-emits on any change. */
    @Query(
        "SELECT e.id AS id, p.feedUrl AS feedUrl, e.title AS title, e.pubDate AS pubDate, e.durationMs AS durationMs, " +
            "e.positionMs AS positionMs, e.played AS played, e.downloadState AS downloadState " +
            "FROM episodes e INNER JOIN podcasts p ON p.id = e.podcastId WHERE p.subscribed = 1",
    )
    fun observePlaylistRows(): Flow<List<PlaylistRow>>

    @Query(
        "SELECT e.id AS id, p.feedUrl AS feedUrl, e.title AS title, e.pubDate AS pubDate, e.durationMs AS durationMs, " +
            "e.positionMs AS positionMs, e.played AS played, e.downloadState AS downloadState " +
            "FROM episodes e INNER JOIN podcasts p ON p.id = e.podcastId WHERE p.subscribed = 1",
    )
    suspend fun getPlaylistRows(): List<PlaylistRow>

    /** Episodes by id (at most 500 at a time), in no particular order. */
    @Query("$EPISODE_JOIN WHERE e.id IN (:ids)")
    suspend fun getManyWithPodcast(ids: List<String>): List<EpisodeWithPodcast>

    @Query("$EPISODE_JOIN WHERE e.id IN (:ids)")
    fun observeManyWithPodcast(ids: List<String>): Flow<List<EpisodeWithPodcast>>

    // ---- Queue ----

    @Query("$EPISODE_JOIN WHERE e.queuePosition IS NOT NULL ORDER BY e.queuePosition")
    fun observeQueue(): Flow<List<EpisodeWithPodcast>>

    @Query("$EPISODE_JOIN WHERE e.queuePosition IS NOT NULL ORDER BY e.queuePosition")
    suspend fun getQueue(): List<EpisodeWithPodcast>

    @Query("$EPISODE_JOIN WHERE e.queuePosition IS NOT NULL ORDER BY e.queuePosition LIMIT 1")
    suspend fun queueHead(): EpisodeWithPodcast?

    @Query("SELECT id FROM episodes WHERE queuePosition IS NOT NULL ORDER BY queuePosition")
    suspend fun queueIds(): List<String>

    @Query("UPDATE episodes SET dirty = CASE WHEN queuePosition IS NOT :position THEN 1 ELSE dirty END, " +
            "changedAt = CASE WHEN queuePosition IS NOT :position THEN $NOW_MS ELSE changedAt END, queuePosition = :position WHERE id = :id")
    suspend fun setQueuePosition(id: String, position: Long?)

    /** Episodes of a podcast that are neither queued nor [keepId]; used when unsubscribing. */
    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId AND queuePosition IS NULL AND id != :keepId")
    suspend fun unqueuedForPodcast(podcastId: Long, keepId: String): List<Episode>

    @Query("DELETE FROM episodes WHERE podcastId = :podcastId AND queuePosition IS NULL AND id != :keepId")
    suspend fun deleteUnqueuedForPodcast(podcastId: Long, keepId: String)

    @Query("$EPISODE_JOIN WHERE e.id = :id")
    fun observeWithPodcast(id: String): Flow<EpisodeWithPodcast?>

    @Query("$EPISODE_JOIN WHERE e.id = :id")
    suspend fun getWithPodcast(id: String): EpisodeWithPodcast?

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun get(id: String): Episode?

    @Query("SELECT id FROM episodes WHERE podcastId = :podcastId")
    suspend fun idsForPodcast(podcastId: Long): List<String>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId AND downloadState != 0")
    suspend fun downloadsForPodcast(podcastId: Long): List<Episode>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(episodes: List<Episode>)

    /** Refreshes feed-provided fields without touching listening or download state. */
    @Query(
        "UPDATE episodes SET title = :title, description = :description, pubDate = :pubDate, " +
            "audioUrl = :audioUrl, mimeType = :mimeType, durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END, " +
            "imageUrl = :imageUrl, chaptersUrl = :chaptersUrl, transcriptUrl = :transcriptUrl, transcriptType = :transcriptType, " +
            "season = :season, episodeNo = :episodeNo, personsJson = :personsJson, feedDurationMs = :durationMs " +
            "WHERE id = :id",
    )
    suspend fun updateMeta(
        id: String,
        title: String,
        description: String,
        pubDate: Long,
        audioUrl: String,
        mimeType: String?,
        durationMs: Long,
        imageUrl: String?,
        chaptersUrl: String?,
        transcriptUrl: String?,
        transcriptType: String?,
        season: Int?,
        episodeNo: Double?,
        personsJson: String?,
    )

    @Query("UPDATE episodes SET skippedChapters = :skipped, $TOUCH WHERE id = :id")
    suspend fun setSkippedChapters(id: String, skipped: String?)

    @Query("UPDATE episodes SET skippedChapters = :skipped WHERE id = :id")
    suspend fun applyRemoteSkippedChapters(id: String, skipped: String?)

    @Query("UPDATE episodes SET positionMs = :positionMs, lastPlayedAt = :at, $TOUCH WHERE id = :id")
    suspend fun savePosition(id: String, positionMs: Long, at: Long)

    @Query("UPDATE episodes SET timeSavedMs = timeSavedMs + :ms WHERE id = :id")
    suspend fun addTimeSaved(id: String, ms: Long)

    @Query("UPDATE episodes SET durationMs = :durationMs WHERE id = :id")
    suspend fun setDuration(id: String, durationMs: Long)

    /** Marking played also takes the episode out of the inbox and the queue. */
    @Query(
        "UPDATE episodes SET played = :played, positionMs = 0, " +
            "inInbox = CASE WHEN :played THEN 0 ELSE inInbox END, " +
            "queuePosition = CASE WHEN :played THEN NULL ELSE queuePosition END, $TOUCH WHERE id = :id",
    )
    suspend fun setPlayed(id: String, played: Boolean)

    /**
     * Marks many episodes played at once (phase 7b, "mark older/newer played"). Callers leave queued
     * episodes out, so the queue isn't touched. Keep [ids] to 500 or fewer (SQLite's variable limit).
     */
    @Query("UPDATE episodes SET played = 1, positionMs = 0, inInbox = 0, $TOUCH WHERE id IN (:ids) AND played = 0")
    suspend fun markPlayed(ids: List<String>): Int

    @Query("UPDATE episodes SET downloadState = :state, downloadProgress = :progress, localPath = :localPath WHERE id = :id")
    suspend fun setDownload(id: String, state: Int, progress: Int, localPath: String?)

    @Query("UPDATE episodes SET downloadProgress = :progress WHERE id = :id")
    suspend fun setDownloadProgress(id: String, progress: Int)

    @Query(
        "UPDATE episodes SET downloadState = ${DownloadState.DONE}, downloadProgress = 100, " +
            "localPath = :localPath, downloadedAt = :at WHERE id = :id",
    )
    suspend fun markDownloaded(id: String, localPath: String, at: Long)

    @Query(
        "UPDATE episodes SET downloadState = ${DownloadState.NONE}, downloadProgress = 0, " +
            "localPath = NULL, downloadedAt = 0 WHERE id = :id",
    )
    suspend fun clearDownload(id: String)

    @Query("UPDATE episodes SET downloadedAt = :at WHERE id = :id")
    suspend fun setDownloadedAt(id: String, at: Long)

    @Query("SELECT * FROM episodes WHERE downloadState = ${DownloadState.DONE}")
    suspend fun completedDownloads(): List<Episode>

    // ---- Sync ----

    @Query("SELECT * FROM episodes WHERE dirty = 1 LIMIT :limit")
    suspend fun dirty(limit: Int): List<Episode>

    @Query("SELECT COUNT(*) FROM episodes WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    /** After subscribing to a previewed podcast: upload its recent episodes for the web player's Latest tab. */
    @Query(
        "UPDATE episodes SET dirty = 1, changedAt = CASE WHEN changedAt = 0 THEN $NOW_MS ELSE changedAt END " +
            "WHERE podcastId = :podcastId AND pubDate > :since",
    )
    suspend fun markRecentForUpload(podcastId: Long, since: Long)

    @Query("UPDATE episodes SET dirty = 0 WHERE id = :id AND changedAt = :changedAt")
    suspend fun markClean(id: String, changedAt: Long)

    /** Applies listening state from another device (not marked dirty). */
    @Query(
        "UPDATE episodes SET positionMs = :positionMs, played = :played, lastPlayedAt = :lastPlayedAt, " +
            "inInbox = :inInbox, queuePosition = :queuePosition, changedAt = :changedAt WHERE id = :id",
    )
    suspend fun applyRemote(
        id: String,
        positionMs: Long,
        played: Boolean,
        lastPlayedAt: Long,
        inInbox: Boolean,
        queuePosition: Long?,
        changedAt: Long,
    )
}

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcripts WHERE episodeId = :episodeId")
    suspend fun get(episodeId: String): Transcript?

    @Query("SELECT * FROM transcripts WHERE episodeId = :episodeId")
    fun observe(episodeId: String): Flow<Transcript?>

    @Query("SELECT episodeId FROM transcripts")
    fun observeIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: Transcript)

    /**
     * Transcripts containing every one of up to 6 terms (lower-cased; unused terms are ""), for search.
     * Only the episode id and segments are needed, newest first.
     */
    @Query(
        "SELECT * FROM transcripts WHERE plain LIKE '%' || :a || '%' AND plain LIKE '%' || :b || '%' " +
            "AND plain LIKE '%' || :c || '%' AND plain LIKE '%' || :d || '%' AND plain LIKE '%' || :e || '%' " +
            "AND plain LIKE '%' || :f || '%' ORDER BY changedAt DESC LIMIT :limit",
    )
    suspend fun search(a: String, b: String, c: String, d: String, e: String, f: String, limit: Int): List<Transcript>

    /** Episodes that are queued, new or played and have a feed transcript not saved yet (newest first). */
    @Query(
        "SELECT e.* FROM episodes e WHERE e.transcriptUrl IS NOT NULL AND " +
            "(e.queuePosition IS NOT NULL OR e.inInbox = 1 OR e.played = 1 OR e.positionMs > 0) " +
            "AND NOT EXISTS (SELECT 1 FROM transcripts t WHERE t.episodeId = e.id) " +
            "ORDER BY e.lastPlayedAt DESC, e.pubDate DESC LIMIT :limit",
    )
    suspend fun missingFeedTranscripts(limit: Int): List<Episode>

    @Query("SELECT * FROM transcripts WHERE dirty = 1 LIMIT :limit")
    suspend fun dirty(limit: Int): List<Transcript>

    @Query("SELECT COUNT(*) FROM transcripts WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    @Query("UPDATE transcripts SET dirty = 0 WHERE episodeId = :episodeId AND changedAt = :changedAt")
    suspend fun markClean(episodeId: String, changedAt: Long)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE deleted = 0 ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE deleted = 0 ORDER BY createdAt DESC")
    suspend fun getAll(): List<Bookmark>

    @Query("SELECT * FROM bookmarks WHERE episodeId = :episodeId AND deleted = 0 ORDER BY positionMs")
    fun observeForEpisode(episodeId: String): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE episodeId = :episodeId AND deleted = 0 ORDER BY positionMs")
    suspend fun getForEpisode(episodeId: String): List<Bookmark>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun get(id: String): Bookmark?

    /**
     * Phase 14: bookmarks with no title yet whose episode has a timed transcript on this phone, so a
     * title can be made for them (newest first).
     */
    @Query(
        "SELECT b.* FROM bookmarks b JOIN transcripts t ON t.episodeId = b.episodeId " +
            "WHERE b.deleted = 0 AND (b.title IS NULL OR b.title = '') AND t.timed = 1 " +
            "ORDER BY b.createdAt DESC LIMIT :limit",
    )
    suspend fun untitledWithTranscript(limit: Int): List<Bookmark>

    /** Phase 14: how many bookmarks of [episodeId] have no title yet. */
    @Query("SELECT COUNT(*) FROM bookmarks WHERE episodeId = :episodeId AND deleted = 0 AND (title IS NULL OR title = '')")
    suspend fun untitledCount(episodeId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(b: Bookmark)

    @Query("SELECT * FROM bookmarks WHERE dirty = 1 LIMIT :limit")
    suspend fun dirty(limit: Int): List<Bookmark>

    @Query("SELECT COUNT(*) FROM bookmarks WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    @Query("UPDATE bookmarks SET dirty = 0 WHERE id = :id AND changedAt = :changedAt")
    suspend fun markClean(id: String, changedAt: Long)

    /** Deletions that have reached the server and are older than [before] can go for good. */
    @Query("DELETE FROM bookmarks WHERE deleted = 1 AND dirty = 0 AND changedAt < :before")
    suspend fun purgeDeleted(before: Long)
}

/** One row for playlist filtering (see [EpisodeDao.observePlaylistRows]). */
data class PlaylistRow(
    val id: String,
    val feedUrl: String,
    val title: String,
    val pubDate: Long,
    val durationMs: Long,
    val positionMs: Long,
    val played: Boolean,
    val downloadState: Int,
)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists WHERE deleted = 0 ORDER BY position, name COLLATE NOCASE")
    fun observeAll(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE deleted = 0 ORDER BY position, name COLLATE NOCASE")
    suspend fun getAll(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: String): Playlist?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: String): Flow<Playlist?>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlists WHERE deleted = 0")
    suspend fun maxPosition(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: Playlist)

    @Query("SELECT * FROM playlists WHERE dirty = 1 LIMIT :limit")
    suspend fun dirty(limit: Int): List<Playlist>

    @Query("SELECT COUNT(*) FROM playlists WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    @Query("UPDATE playlists SET dirty = 0 WHERE id = :id AND changedAt = :changedAt")
    suspend fun markClean(id: String, changedAt: Long)

    @Query("DELETE FROM playlists WHERE deleted = 1 AND dirty = 0 AND changedAt < :before")
    suspend fun purgeDeleted(before: Long)
}

@Dao
interface ListenDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(l: Listen)

    @Query("SELECT * FROM listens WHERE id = :id")
    suspend fun get(id: String): Listen?

    @Query("SELECT * FROM listens WHERE dirty = 1 LIMIT :limit")
    suspend fun dirty(limit: Int): List<Listen>

    @Query("SELECT COUNT(*) FROM listens WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    @Query("UPDATE listens SET dirty = 0 WHERE id = :id AND changedAt = :changedAt")
    suspend fun markClean(id: String, changedAt: Long)

    @Query("SELECT COUNT(*) FROM listens")
    suspend fun count(): Int

    /** All listening history (phone, car and web), for the Stats page. */
    @Query("SELECT * FROM listens ORDER BY startedAt")
    fun observeAll(): Flow<List<Listen>>

    /** Known episode lengths, for "finished" on the Stats page. */
    @Query("SELECT id, durationMs FROM episodes WHERE durationMs > 0")
    suspend fun episodeLengths(): List<EpisodeLength>

    /**
     * Stretches still marked as in progress (changedAt = 0) when the player was stopped without
     * finishing them (e.g. the app was killed): finish them now so they get uploaded.
     */
    @Query("UPDATE listens SET dirty = 1, changedAt = :now WHERE changedAt = 0")
    suspend fun finishOrphans(now: Long): Int
}

@Dao
interface EpisodeAiDao {
    @Query("SELECT * FROM episode_ai WHERE episodeId = :episodeId AND kind = :kind")
    suspend fun get(episodeId: String, kind: String): EpisodeAi?

    @Query("SELECT * FROM episode_ai WHERE episodeId = :episodeId")
    fun observe(episodeId: String): Flow<List<EpisodeAi>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: EpisodeAi)
}

/** An episode's id and length (Stats page). */
data class EpisodeLength(val id: String, val durationMs: Long)
