package com.earshot.app.data

import android.content.Context
import android.util.Log
import com.earshot.app.data.db.DownloadState
import com.earshot.app.data.db.Episode
import com.earshot.app.data.feed.ByteSource
import com.earshot.app.data.feed.Chapter
import com.earshot.app.data.feed.ChaptersParser
import com.earshot.app.data.feed.EmbeddedChapters
import com.earshot.app.data.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Chapters for an episode (phase 10), shared by the player screen and the playback service (which
 * skips unticked chapters). Order: the feed's JSON chapters, else chapters embedded in the file
 * (ID3 CHAP in MP3, QuickTime or Nero chapters in M4A), else generated chapters (phase 11).
 * Results are kept in memory, and embedded ones also on disk, so a file is only read once.
 */
class ChapterLoader(private val context: Context) {
    private val memory = object : LinkedHashMap<String, List<Chapter>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Chapter>>?) = size > 30
    }
    private val lock = Mutex()
    private val dir get() = File(context.filesDir, "chapters").apply { mkdirs() }

    /** Extra chapter sources tried last (phase 11's generated chapters plug in here). */
    var fallback: (suspend (Episode) -> List<Chapter>)? = null

    fun cached(ep: Episode): List<Chapter>? = synchronized(memory) { memory[key(ep)] }

    private fun key(ep: Episode) = ep.id + "|" + (ep.chaptersUrl ?: "")

    /** Forgets what's cached for this episode (e.g. after chapters were generated). */
    fun invalidate(episodeId: String) {
        synchronized(memory) { memory.keys.removeAll { it.startsWith("$episodeId|") } }
    }

    suspend fun load(ep: Episode): List<Chapter> = withContext(Dispatchers.IO) {
        cached(ep)?.let { return@withContext it }
        lock.withLock {
            cached(ep)?.let { return@withLock it }
            val list = loadUncached(ep)
            synchronized(memory) { memory[key(ep)] = list }
            list
        }
    }

    private suspend fun loadUncached(ep: Episode): List<Chapter> {
        ep.chaptersUrl?.let { url ->
            try {
                val list = ChaptersParser.parse(Http.getText(url))
                if (list.isNotEmpty()) return list.also { Log.i(TAG, "Loaded ${it.size} chapters from $url") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't load chapters from $url", e)
            }
        }
        embedded(ep).takeIf { it.isNotEmpty() }?.let { return it }
        return try {
            fallback?.invoke(ep).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Fallback chapters failed for ${ep.id}", e)
            emptyList()
        }
    }

    /** Embedded chapters, cached on disk (an empty list is cached too, so the file isn't read again). */
    private fun embedded(ep: Episode): List<Chapter> {
        val cache = File(dir, "${ep.id}.json")
        if (cache.exists()) return runCatching { ChaptersParser.parse(cache.readText()) }.getOrDefault(emptyList())
        if (!mightHaveChapters(ep)) return emptyList()
        val local = ep.localPath?.let(::File)?.takeIf { ep.downloadState == DownloadState.DONE && it.isFile }
        return try {
            val list = if (local != null) {
                RandomAccessFile(local, "r").use { EmbeddedChapters.read(FileSource(it)) }
            } else {
                EmbeddedChapters.read(HttpRangeSource(ep.audioUrl))
            }
            cache.writeText(ChaptersParser.toJson(list))
            if (list.isNotEmpty()) Log.i(TAG, "Read ${list.size} embedded chapters from \"${ep.title}\"")
            list
        } catch (e: Exception) {
            // Network trouble or a server without range support: try again another time.
            Log.w(TAG, "Couldn't read embedded chapters of \"${ep.title}\"", e)
            emptyList()
        }
    }

    private fun mightHaveChapters(ep: Episode): Boolean {
        val type = ep.mimeType?.lowercase().orEmpty()
        val path = ep.audioUrl.substringBefore('?').lowercase()
        if (type.startsWith("video/")) return false
        return type.isEmpty() || "mpeg" in type || "mp3" in type || "mp4" in type || "m4a" in type || "aac" in type ||
            path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".m4b")
    }

    /** Deletes cached embedded chapters of episodes no longer in the library. */
    fun prune(keepIds: Set<String>) {
        dir.listFiles()?.forEach { if (it.nameWithoutExtension !in keepIds) it.delete() }
    }

    private class FileSource(private val f: RandomAccessFile) : ByteSource {
        override val length: Long = f.length()
        override fun read(offset: Long, size: Int): ByteArray {
            if (offset >= length || size <= 0) return ByteArray(0)
            val n = minOf(size.toLong(), length - offset).toInt()
            val b = ByteArray(n)
            f.seek(offset)
            f.readFully(b)
            return b
        }
    }

    /** Reads byte ranges over HTTP. Remembers where redirects end up, so each read is one request. */
    private class HttpRangeSource(url: String) : ByteSource {
        private var resolved: String = url
        override var length: Long = -1
            private set

        override fun read(offset: Long, size: Int): ByteArray {
            if (size <= 0) return ByteArray(0)
            if (length in 0..offset) return ByteArray(0)
            val conn = Http.open(resolved, headers = mapOf("Range" to "bytes=$offset-${offset + size - 1}", "Accept-Encoding" to "identity"))
            try {
                resolved = conn.url.toString()
                if (conn.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    conn.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()?.let { length = it }
                    return conn.inputStream.use { readUpTo(it, size) }
                }
                // 200: the server sent the whole file. Fine for the start; anything else needs ranges.
                length = conn.contentLengthLong
                if (offset > 0) throw IOException("No range support at ${URL(resolved).host}")
                return conn.inputStream.use { readUpTo(it, size) }
            } finally {
                conn.disconnect()
            }
        }
    }

    private companion object {
        const val TAG = "Earshot"

        /** InputStream.readNBytes needs API 33; minSdk is 26. */
        fun readUpTo(input: java.io.InputStream, size: Int): ByteArray {
            val b = ByteArray(size)
            var n = 0
            while (n < size) {
                val r = input.read(b, n, size - n)
                if (r < 0) break
                n += r
            }
            return if (n == size) b else b.copyOf(n)
        }
    }
}
