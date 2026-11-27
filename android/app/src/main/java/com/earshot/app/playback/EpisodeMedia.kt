package com.earshot.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.earshot.app.data.db.DownloadState
import com.earshot.app.data.db.EpisodeWithPodcast
import com.earshot.app.data.db.Podcast
import java.io.File
import java.util.Calendar

/** Builds the MediaItems the player, the notification and Android Auto see. */
@OptIn(UnstableApi::class)
object EpisodeMedia {
    // Browse-tree ids for Android Auto. Episode ids are hex hashes, so they can't clash with these.
    const val ROOT = "root"
    const val QUEUE = "queue"
    const val INBOX = "inbox"
    const val LATEST = "latest"
    const val PODCASTS = "podcasts"
    const val PODCAST_PREFIX = "podcast:"

    /**
     * A playable episode, from its download when there is one. Android Auto shows its progress
     * (unplayed, partly played with a bar, played) and whether it's downloaded. [group] puts it
     * under a heading in the list (items with the same heading must be next to each other).
     */
    fun item(item: EpisodeWithPodcast, group: String? = null): MediaItem {
        val e = item.episode
        val localFile = e.localPath?.let { File(it) }?.takeIf { it.exists() }
        val uri: Uri = localFile?.let { Uri.fromFile(it) } ?: Uri.parse(e.audioUrl)

        val extras = Bundle()
        val status = when {
            e.played -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
            e.positionMs > 0 -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
            else -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
        }
        extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, status)
        if (status == MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED && e.durationMs > 0) {
            val done = (e.positionMs.toDouble() / e.durationMs).coerceIn(0.0, 1.0)
            extras.putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, done)
        }
        val download = when {
            localFile != null -> MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED
            e.downloadState == DownloadState.QUEUED || e.downloadState == DownloadState.DOWNLOADING ->
                MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADING
            else -> MediaConstants.EXTRAS_VALUE_STATUS_NOT_DOWNLOADED
        }
        extras.putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, download)
        if (group != null) extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)

        return MediaItem.Builder()
            .setMediaId(e.id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(e.title)
                    .setArtist(item.podcastTitle)
                    .setAlbumTitle(item.podcastTitle)
                    .setArtworkUri(item.artworkUrl?.let { Uri.parse(it) })
                    .setDurationMs(e.durationMs.takeIf { it > 0 })
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    /** Episodes newest first, under date headings: Today, Yesterday, This week, This month, Older. */
    fun itemsByDate(items: List<EpisodeWithPodcast>, now: Long = System.currentTimeMillis()): List<MediaItem> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val today = cal.timeInMillis
        val yesterday = today - 86_400_000L
        val week = today - 6 * 86_400_000L
        val month = today - 29 * 86_400_000L
        return items.map {
            val t = it.episode.pubDate
            val group = when {
                t >= today -> "Today"
                t >= yesterday -> "Yesterday"
                t >= week -> "This week"
                t >= month -> "This month"
                else -> "Older"
            }
            item(it, group)
        }
    }

    /** Where to start: the saved position, unless the episode is finished (or nearly). */
    fun startPosition(item: EpisodeWithPodcast): Long {
        val e = item.episode
        val nearEnd = e.durationMs > 0 && e.positionMs > e.durationMs - 5_000
        return if (e.played || nearEnd) 0L else e.positionMs
    }

    /**
     * A folder. [icon] is shown when the folder is a tab at the top of Android Auto. [childStyle]
     * sets how its contents are shown: as a list or as a grid of artwork.
     */
    fun folder(
        id: String,
        title: String,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        icon: Uri? = null,
        childStyle: Int? = null,
    ): MediaItem {
        val extras = Bundle()
        if (childStyle != null) {
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, childStyle)
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, childStyle)
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtworkUri(icon)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    fun podcastFolder(p: Podcast): MediaItem {
        // A podcast's episodes are a list; the podcast itself is a tile in the Podcasts grid.
        val extras = Bundle()
        extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        return MediaItem.Builder()
            .setMediaId(PODCAST_PREFIX + p.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(p.title)
                    .setArtist(p.author.ifBlank { null })
                    .setArtworkUri(p.imageUrl?.let { Uri.parse(it) })
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }
}
