package com.earshot.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "podcasts",
    indices = [Index(value = ["feedUrl"], unique = true)],
)
data class Podcast(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedUrl: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    val imageUrl: String? = null,
    val lastRefreshedAt: Long = 0,
    val sortOrder: Int = 0,
    /**
     * False after unsubscribing while some of its episodes were still queued: the podcast is hidden
     * and no longer refreshed, but those episodes stay until played or removed (v3).
     */
    @ColumnInfo(defaultValue = "1") val subscribed: Boolean = true,
    // Per-podcast playback settings (v4). Null means "use the app-wide default".
    val speed: Float? = null,
    val smartSpeed: Boolean? = null,
    val voiceBoost: Boolean? = null,
    val skipBackMs: Long? = null,
    val skipForwardMs: Long? = null,
)

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = Podcast::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("podcastId"), Index("pubDate")],
)
data class Episode(
    /** Stable hash of feed URL + guid, so the same episode has the same id on every device (needed for sync). */
    @PrimaryKey val id: String,
    val podcastId: Long,
    val guid: String,
    val title: String,
    val description: String = "",
    val pubDate: Long = 0,
    val audioUrl: String,
    val mimeType: String? = null,
    val durationMs: Long = 0,
    val imageUrl: String? = null,
    // Podcasting 2.0 extras. Stored now, used in later phases.
    val chaptersUrl: String? = null,
    val transcriptUrl: String? = null,
    val transcriptType: String? = null,
    // Listening state
    val positionMs: Long = 0,
    val played: Boolean = false,
    val lastPlayedAt: Long = 0,
    // Downloads
    val downloadState: Int = DownloadState.NONE,
    val downloadProgress: Int = 0,
    val localPath: String? = null,
    /** When the download finished (ms). 0 = not downloaded, or downloaded before this was tracked. */
    @ColumnInfo(defaultValue = "0") val downloadedAt: Long = 0,
    // Triage (v3)
    /** New episode waiting in the Inbox to be queued or archived. */
    @ColumnInfo(defaultValue = "0") val inInbox: Boolean = false,
    /** Place in the queue (lower plays first); null when not queued. */
    val queuePosition: Long? = null,
    /** Listening time saved on this episode by speed and Smart Speed, in ms (v4). */
    @ColumnInfo(defaultValue = "0") val timeSavedMs: Long = 0,
)

object DownloadState {
    const val NONE = 0
    const val QUEUED = 1
    const val DOWNLOADING = 2
    const val DONE = 3
    const val FAILED = 4
}

data class EpisodeWithPodcast(
    @Embedded val episode: Episode,
    val podcastTitle: String,
    val podcastImageUrl: String?,
) {
    val artworkUrl: String? get() = episode.imageUrl ?: podcastImageUrl
}

data class PodcastWithCount(
    @Embedded val podcast: Podcast,
    val unplayedCount: Int,
)
