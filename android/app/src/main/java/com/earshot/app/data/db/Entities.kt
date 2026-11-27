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
    /** Pinned podcasts sort first in the Podcasts list (v5). */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    // Sync (v5): a user change sets dirty and changedAt (ms); a sync uploads dirty rows and clears dirty.
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = true,
    @ColumnInfo(defaultValue = "0") val changedAt: Long = System.currentTimeMillis(),
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
    // Sync (v5), as on Podcast.
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
    @ColumnInfo(defaultValue = "0") val changedAt: Long = 0,
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

/**
 * A saved transcript for one episode (v6). [segmentsJson] is the shared format (see
 * data/transcript/TranscriptFormat.kt): [{"s":startMs,"e":endMs,"t":"text","sp":"speaker"}].
 * [plain] is all the text, lower-cased, for search. No foreign key: a transcript outlives its
 * episode leaving the library, so bookmarks still show their quotes.
 */
@Entity(tableName = "transcripts")
data class Transcript(
    @PrimaryKey val episodeId: String,
    /** "feed" (the podcast's own transcript) or "gemini" (transcribed on request). */
    val source: String,
    /** False when the feed's transcript has no timings (one block of text). */
    @ColumnInfo(defaultValue = "1") val timed: Boolean = true,
    val segmentsJson: String,
    val plain: String,
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
    @ColumnInfo(defaultValue = "0") val changedAt: Long = 0,
)

/**
 * A bookmark (v6): a moment in an episode, the stretch of transcript it quotes ([startMs] to [endMs])
 * and an optional note. Episode details are copied in so it survives the episode leaving the library.
 * [deleted] marks a deletion that still has to reach the other devices.
 */
@Entity(tableName = "bookmarks", indices = [Index("episodeId")])
data class Bookmark(
    @PrimaryKey val id: String,
    val episodeId: String,
    val feedUrl: String = "",
    val episodeTitle: String = "",
    val podcastTitle: String = "",
    val imageUrl: String? = null,
    val audioUrl: String? = null,
    val positionMs: Long,
    val startMs: Long,
    val endMs: Long,
    val note: String = "",
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true,
    @ColumnInfo(defaultValue = "0") val changedAt: Long = System.currentTimeMillis(),
)
