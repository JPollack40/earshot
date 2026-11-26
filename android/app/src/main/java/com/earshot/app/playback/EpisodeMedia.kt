package com.earshot.app.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.earshot.app.data.db.EpisodeWithPodcast
import com.earshot.app.data.db.Podcast
import java.io.File

/** Builds the MediaItems the player, the notification and Android Auto see. */
object EpisodeMedia {
    // Browse-tree ids for Android Auto. Episode ids are hex hashes, so they can't clash with these.
    const val ROOT = "root"
    const val QUEUE = "queue"
    const val INBOX = "inbox"
    const val LATEST = "latest"
    const val PODCASTS = "podcasts"
    const val PODCAST_PREFIX = "podcast:"

    /** A playable episode, from its download when there is one. */
    fun item(item: EpisodeWithPodcast): MediaItem {
        val e = item.episode
        val localFile = e.localPath?.let { File(it) }?.takeIf { it.exists() }
        val uri: Uri = localFile?.let { Uri.fromFile(it) } ?: Uri.parse(e.audioUrl)
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
                    .build(),
            )
            .build()
    }

    /** Where to start: the saved position, unless the episode is finished (or nearly). */
    fun startPosition(item: EpisodeWithPodcast): Long {
        val e = item.episode
        val nearEnd = e.durationMs > 0 && e.positionMs > e.durationMs - 5_000
        return if (e.played || nearEnd) 0L else e.positionMs
    }

    fun folder(id: String, title: String, mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .build(),
            )
            .build()

    fun podcastFolder(p: Podcast): MediaItem =
        MediaItem.Builder()
            .setMediaId(PODCAST_PREFIX + p.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(p.title)
                    .setArtist(p.author.ifBlank { null })
                    .setArtworkUri(p.imageUrl?.let { Uri.parse(it) })
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                    .build(),
            )
            .build()
}
