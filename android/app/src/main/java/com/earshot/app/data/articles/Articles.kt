package com.earshot.app.data.articles

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Phase 23: Articles in my queue. A web article shared to Earshot becomes an episode of the "Articles"
 * show, read aloud by the phone's own text-to-speech voice (free, works offline once the page is
 * fetched), with a timed transcript so bookmarks, search and Smart Speed work as for any episode.
 *
 * The Articles show and its episodes live on this phone only: the audio is made here, so sync leaves
 * them out (the web player couldn't play them).
 */
object Articles {
    const val FEED_URL = "earshot:articles"
    const val TITLE = "Articles"
    private const val ID_PREFIX = "article-"

    /** Feeds made on this phone, never fetched or synced. */
    fun isLocalFeed(feedUrl: String) = feedUrl.startsWith("earshot:")

    fun isArticle(episodeId: String) = episodeId.startsWith(ID_PREFIX)

    /** The same page shared twice is the same episode. */
    fun episodeId(url: String): String {
        val key = url.trim().substringBefore('#').trimEnd('/')
        val hex = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return ID_PREFIX + hex.take(20)
    }

    /** The first web link in shared text (apps often share "Title https://..."). */
    fun firstUrl(text: String?): String? =
        text?.let { Regex("""https?://[^\s<>"]+""").find(it)?.value?.trimEnd('.', ',', ')', ';', '!', '?') }
}

/** What an article page gave us. */
data class Article(
    val title: String,
    val site: String,
    val imageUrl: String?,
    val paragraphs: List<String>,
) {
    val words: Int get() = paragraphs.sumOf { p -> p.split(Regex("\\s+")).count { it.isNotBlank() } }
}

/**
 * Finds an article's readable text in its HTML: the <article> (else <main>, else <body>), without
 * scripts, menus, footers and the like, as paragraphs and headings. Simple rules, no library: good on
 * ordinary news and blog pages, and it says so when a page gives too little (paywalls, apps).
 */
object ArticleText {
    private val DROP = listOf("script", "style", "noscript", "nav", "header", "footer", "aside", "form", "figure", "svg", "button", "iframe", "template")

    fun extract(html: String, url: String): Article {
        val title = meta(html, "og:title") ?: tagText(html, "title") ?: url
        val site = meta(html, "og:site_name") ?: hostOf(url)
        val image = meta(html, "og:image")?.takeIf { it.startsWith("http") }

        var body = html.replace(Regex("(?is)<!--.*?-->"), " ")
        for (tag in DROP) body = body.replace(Regex("(?is)<$tag\\b[^>]*>.*?</$tag\\s*>"), " ")
        val scope = section(body, "article") ?: section(body, "main") ?: section(body, "body") ?: body

        val out = mutableListOf<String>()
        val block = Regex("(?is)<(p|h[1-4]|blockquote|li)\\b[^>]*>(.*?)</\\1\\s*>")
        for (m in block.findAll(scope)) {
            val tag = m.groupValues[1].lowercase()
            val text = clean(m.groupValues[2])
            if (text.isBlank()) continue
            val heading = tag.startsWith("h")
            // Short bits that aren't headings are usually captions, bylines, share buttons.
            if (!heading && text.length < 40) continue
            if (heading && text.length > 200) continue
            if (out.lastOrNull() == text) continue
            out += if (heading && text.last().isLetterOrDigit()) "$text." else text
        }
        // A heading equal to the title would be read twice.
        val cleanTitle = clean(title)
        if (out.firstOrNull()?.trimEnd('.') == cleanTitle) out.removeAt(0)
        return Article(cleanTitle.ifBlank { url }, site, image, out)
    }

    /** Text in [paragraphs] split into pieces of at most [max] characters for the voice, at sentence ends. */
    fun pieces(paragraphs: List<String>, max: Int): List<String> {
        val out = mutableListOf<String>()
        for (p in paragraphs) {
            if (p.length <= max) { out += p; continue }
            var cur = StringBuilder()
            for (s in p.split(Regex("(?<=[.!?])\\s+"))) {
                if (cur.isNotEmpty() && cur.length + 1 + s.length > max) { out += cur.toString(); cur = StringBuilder() }
                if (s.length > max) {
                    // One huge "sentence": cut at spaces.
                    for (w in s.split(' ')) {
                        if (cur.isNotEmpty() && cur.length + 1 + w.length > max) { out += cur.toString(); cur = StringBuilder() }
                        if (cur.isNotEmpty()) cur.append(' ')
                        cur.append(w.take(max))
                    }
                } else {
                    if (cur.isNotEmpty()) cur.append(' ')
                    cur.append(s)
                }
            }
            if (cur.isNotEmpty()) out += cur.toString()
        }
        return out
    }

    private fun section(html: String, tag: String): String? =
        Regex("(?is)<$tag\\b[^>]*>(.*)</$tag\\s*>").find(html)?.groupValues?.get(1)

    private fun tagText(html: String, tag: String): String? =
        Regex("(?is)<$tag\\b[^>]*>(.*?)</$tag\\s*>").find(html)?.groupValues?.get(1)?.let(::clean)?.ifBlank { null }

    private fun meta(html: String, property: String): String? {
        for (m in Regex("(?is)<meta\\b[^>]*>").findAll(html)) {
            val tag = m.value
            val name = Regex("(?i)(?:property|name)\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1) ?: continue
            if (!name.equals(property, ignoreCase = true)) continue
            return Regex("(?is)content\\s*=\\s*\"([^\"]*)\"|content\\s*=\\s*'([^']*)'").find(tag)
                ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }?.let(::decode)?.trim()?.ifBlank { null }
        }
        return null
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').removePrefix("www.")

    fun clean(fragment: String): String =
        decode(fragment.replace(Regex("(?i)<br\\s*/?>"), " ").replace(Regex("<[^>]+>"), " "))
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    fun decode(s: String): String {
        val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
            "rsquo" to "\u2019", "lsquo" to "\u2018", "rdquo" to "\u201D", "ldquo" to "\u201C", "mdash" to "\u2014",
            "ndash" to "\u2013", "hellip" to "\u2026")
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
            val v = m.groupValues[1]
            when {
                v.startsWith("#x") || v.startsWith("#X") -> v.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                v.startsWith("#") -> v.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> named[v.lowercase()] ?: m.value
            }
        }
    }
}

/**
 * Joins the voice's WAV pieces into one file. Every piece must have the same format (the same voice
 * makes them, so it does). Returns the joined bytes' header + data, and each piece's length in ms.
 */
object Wav {
    data class Pcm(val channels: Int, val sampleRate: Int, val bitsPerSample: Int, val data: ByteArray) {
        val bytesPerMs: Double get() = sampleRate * channels * (bitsPerSample / 8) / 1000.0
        val durationMs: Long get() = if (bytesPerMs > 0) (data.size / bytesPerMs).toLong() else 0
        fun sameFormat(o: Pcm) = channels == o.channels && sampleRate == o.sampleRate && bitsPerSample == o.bitsPerSample
    }

    /** Reads a RIFF/WAVE file's format and PCM data (skips any other chunks). */
    fun read(bytes: ByteArray): Pcm {
        require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Not a WAV file" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var data: ByteArray? = null
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = b.getInt(pos + 4)
            val start = pos + 8
            val len = if (size < 0 || start + size > bytes.size) bytes.size - start else size
            when (id) {
                "fmt " -> {
                    channels = b.getShort(start + 2).toInt()
                    rate = b.getInt(start + 4)
                    bits = b.getShort(start + 14).toInt()
                }
                "data" -> data = bytes.copyOfRange(start, start + len)
            }
            pos = start + len + (len and 1)
        }
        require(channels > 0 && rate > 0 && bits > 0 && data != null) { "WAV file without format or data" }
        return Pcm(channels, rate, bits, data)
    }

    /** The 44-byte header of a plain PCM WAV file holding [dataBytes] bytes. */
    fun header(channels: Int, sampleRate: Int, bitsPerSample: Int, dataBytes: Long): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val blockAlign = channels * (bitsPerSample / 8)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(sampleRate)
            .putInt(sampleRate * blockAlign).putShort(blockAlign.toShort()).putShort(bitsPerSample.toShort())
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        return h.array()
    }

    /**
     * Writes pieces one after another into [file] (so a long article never sits in memory whole),
     * then the header. Every piece must have the first one's format (the same voice makes them all).
     */
    class Writer(private val file: java.io.File) : java.io.Closeable {
        private val raf = java.io.RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
        private var format: Pcm? = null
        private var bytes = 0L

        /** Adds a piece; returns its length in ms. */
        fun add(p: Pcm): Long {
            val f = format ?: p.also { format = it }
            require(p.sameFormat(f)) { "The voice changed format between pieces" }
            raf.write(p.data)
            bytes += p.data.size
            return p.durationMs
        }

        /** Writes the header; returns the whole length in ms. */
        fun finish(): Long {
            val f = requireNotNull(format) { "Nothing was read aloud" }
            raf.seek(0)
            raf.write(header(f.channels, f.sampleRate, f.bitsPerSample, bytes))
            return if (f.bytesPerMs > 0) (bytes / f.bytesPerMs).toLong() else 0
        }

        override fun close() = raf.close()
    }
}
