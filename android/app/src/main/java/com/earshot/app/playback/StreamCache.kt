package com.earshot.app.playback

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import java.io.File

/**
 * Phase 14b: riding out dead zones when streaming.
 *
 * - [loadControl] buffers about 10 minutes ahead when streaming (Media3's default is 50 s), capped
 *   at [TARGET_BUFFER_BYTES] of memory so a high-bitrate or video episode can't take too much.
 *   Downloaded episodes keep Media3's local-playback defaults.
 * - [dataSourceFactory] keeps a disk cache of streamed audio ([MAX_BYTES], least recently used goes
 *   first), so a rewind, a resume, or a speed or chapter picked in the car plays from the phone
 *   instead of fetching again. Downloaded files are read directly, never copied into the cache.
 *
 * The cache lives in noBackupFilesDir (not cacheDir, which Android may empty while the cache is open,
 * and not filesDir, which Android's cloud backup would try to upload). Settings > Downloads >
 * Delete all downloads empties it too.
 */
@OptIn(UnstableApi::class)
object StreamCache {
    const val MAX_BYTES = 200L * 1024 * 1024
    private const val STREAM_BUFFER_MS = 10 * 60 * 1000
    private const val TARGET_BUFFER_BYTES = 32 * 1024 * 1024

    /**
     * Streamed copies not played for this long are dropped when playback starts. Some podcast hosts
     * stitch different ads in on each visit, so an old partial copy could mix two versions of a file.
     */
    private const val STALE_MS = 12L * 60 * 60 * 1000

    @Volatile
    private var cache: SimpleCache? = null

    private fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.noBackupFilesDir, "stream_cache"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    fun loadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMsForStreaming(
            /* minBufferMs = */ STREAM_BUFFER_MS,
            /* maxBufferMs = */ STREAM_BUFFER_MS,
            /* bufferForPlaybackMs = */ DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            /* bufferForPlaybackAfterRebufferMs = */ DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setTargetBufferBytes(TARGET_BUFFER_BYTES)
        .build()

    /** Streams (http/https) go through the cache; files on the phone are read as they are. */
    fun dataSourceFactory(context: Context): DataSource.Factory {
        val app = context.applicationContext
        val plain = DefaultDataSource.Factory(app)
        val cached = CacheDataSource.Factory()
            .setCache(cache(app))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(app))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory { StreamOrLocalDataSource(cached.createDataSource(), plain.createDataSource()) }
    }

    /** Space the cache takes on the phone, in bytes. Call off the main thread. */
    fun sizeBytes(context: Context): Long = runCatching { cache(context).cacheSpace }.getOrDefault(0L)

    /** Empties the cache (Delete all downloads). Call off the main thread. */
    fun clear(context: Context) {
        val c = cache(context)
        for (key in c.keys.toList()) runCatching { c.removeResource(key) }
        Log.i("Earshot", "Stream cache cleared")
    }

    /** Drops streamed copies not touched for [STALE_MS] (see there). Call off the main thread. */
    fun dropStale(context: Context, now: Long = System.currentTimeMillis()) {
        val c = cache(context)
        var dropped = 0
        for (key in c.keys.toList()) {
            val spans = c.getCachedSpans(key)
            val lastTouched = spans.maxOfOrNull { it.lastTouchTimestamp } ?: continue
            if (now - lastTouched > STALE_MS) {
                runCatching { c.removeResource(key) }
                dropped++
            }
        }
        if (dropped > 0) Log.i("Earshot", "Stream cache: dropped $dropped stale episode(s)")
    }
}

/** Picks the cached source for http(s) and the plain one for files on the phone, per opened stream. */
@OptIn(UnstableApi::class)
private class StreamOrLocalDataSource(
    private val cached: DataSource,
    private val plain: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        plain.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        val source = if (scheme == "http" || scheme == "https") cached else plain
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "read() before open()" }.read(buffer, offset, length)

    override fun getUri() = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
