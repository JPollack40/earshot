package com.earshot.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

private const val EPISODE_JOIN =
    "SELECT e.*, p.title AS podcastTitle, p.imageUrl AS podcastImageUrl " +
        "FROM episodes e INNER JOIN podcasts p ON p.id = e.podcastId"

@Dao
interface PodcastDao {
    @Query(
        "SELECT p.*, (SELECT COUNT(*) FROM episodes e WHERE e.podcastId = p.id AND e.played = 0) AS unplayedCount " +
            "FROM podcasts p WHERE p.subscribed = 1 ORDER BY p.sortOrder, p.title COLLATE NOCASE",
    )
    fun observeAllWithCounts(): Flow<List<PodcastWithCount>>

    @Query("SELECT * FROM podcasts WHERE subscribed = 1 ORDER BY title COLLATE NOCASE")
    suspend fun getAll(): List<Podcast>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun observe(id: Long): Flow<Podcast?>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun get(id: Long): Podcast?

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun findByFeedUrl(feedUrl: String): Podcast?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(podcast: Podcast): Long

    @Update
    suspend fun update(podcast: Podcast)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE podcasts SET subscribed = :subscribed WHERE id = :id")
    suspend fun setSubscribed(id: Long, subscribed: Boolean)

    /** Unsubscribed podcasts are kept only while one of their episodes is queued. */
    @Query(
        "DELETE FROM podcasts WHERE subscribed = 0 AND NOT EXISTS " +
            "(SELECT 1 FROM episodes e WHERE e.podcastId = podcasts.id AND e.queuePosition IS NOT NULL)",
    )
    suspend fun deleteUnusedUnsubscribed(): Int
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

    @Query("UPDATE episodes SET inInbox = :inInbox WHERE id = :id")
    suspend fun setInInbox(id: String, inInbox: Boolean)

    @Query("UPDATE episodes SET inInbox = 0 WHERE inInbox = 1")
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

    @Query("UPDATE episodes SET queuePosition = :position WHERE id = :id")
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

    @Query("UPDATE episodes SET positionMs = :positionMs, lastPlayedAt = :at WHERE id = :id")
    suspend fun savePosition(id: String, positionMs: Long, at: Long)

    @Query("UPDATE episodes SET durationMs = :durationMs WHERE id = :id")
    suspend fun setDuration(id: String, durationMs: Long)

    /** Marking played also takes the episode out of the inbox and the queue. */
    @Query(
        "UPDATE episodes SET played = :played, positionMs = 0, " +
            "inInbox = CASE WHEN :played THEN 0 ELSE inInbox END, " +
            "queuePosition = CASE WHEN :played THEN NULL ELSE queuePosition END WHERE id = :id",
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
}
