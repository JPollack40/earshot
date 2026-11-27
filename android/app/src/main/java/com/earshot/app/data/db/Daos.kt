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
    "SELECT e.*, p.title AS podcastTitle, p.imageUrl AS podcastImageUrl " +
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
            "imageUrl = :imageUrl, lastRefreshedAt = :refreshedAt WHERE id = :id",
    )
    suspend fun updateMeta(id: Long, title: String, author: String, description: String, imageUrl: String?, refreshedAt: Long)

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

    @Query("UPDATE podcasts SET subscribed = :subscribed, $TOUCH WHERE id = :id")
    suspend fun setSubscribed(id: Long, subscribed: Boolean)

    @Query("UPDATE podcasts SET pinned = :pinned, $TOUCH WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

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
            "voiceBoost = :voiceBoost, skipBackMs = :skipBackMs, skipForwardMs = :skipForwardMs, changedAt = :changedAt " +
            "WHERE id = :id",
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
            "imageUrl = :imageUrl, chaptersUrl = :chaptersUrl, transcriptUrl = :transcriptUrl, transcriptType = :transcriptType " +
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
    )

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
