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
            "FROM podcasts p ORDER BY p.sortOrder, p.title COLLATE NOCASE",
    )
    fun observeAllWithCounts(): Flow<List<PodcastWithCount>>

    @Query("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE")
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
}

@Dao
interface EpisodeDao {
    @Query("$EPISODE_JOIN WHERE e.podcastId = :podcastId ORDER BY e.pubDate DESC")
    fun observeForPodcast(podcastId: Long): Flow<List<EpisodeWithPodcast>>

    @Query("$EPISODE_JOIN ORDER BY e.pubDate DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<EpisodeWithPodcast>>

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

    @Query("UPDATE episodes SET played = :played, positionMs = 0 WHERE id = :id")
    suspend fun setPlayed(id: String, played: Boolean)

    @Query("UPDATE episodes SET downloadState = :state, downloadProgress = :progress, localPath = :localPath WHERE id = :id")
    suspend fun setDownload(id: String, state: Int, progress: Int, localPath: String?)

    @Query("UPDATE episodes SET downloadProgress = :progress WHERE id = :id")
    suspend fun setDownloadProgress(id: String, progress: Int)
}
