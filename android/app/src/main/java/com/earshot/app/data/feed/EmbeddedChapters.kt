package com.earshot.app.data.feed

import java.io.IOException
import java.nio.charset.Charset

/**
 * Random access to an audio file's bytes: a downloaded file, or HTTP range requests for a stream.
 * [read] may return fewer bytes than asked for only at the end of the file.
 */
interface ByteSource {
    /** Total length in bytes, or -1 when unknown. */
    val length: Long

    @Throws(IOException::class)
    fun read(offset: Long, size: Int): ByteArray
}

/**
 * Chapters stored inside the audio file (phase 10):
 *  - MP3: ID3v2 CHAP frames (title from TIT2, link from WXXX/WOAR).
 *  - M4A/M4B: a QuickTime chapter text track (what Apple's tools write), else Nero "chpl" chapters.
 * Chapter images inside the file aren't read (the episode artwork shows instead).
 */
object EmbeddedChapters {
    /** Most ID3 tags are well under this; very large ones are usually cover art we don't need. */
    private const val MAX_ID3 = 16 * 1024 * 1024
    private const val MAX_MOOV = 24 * 1024 * 1024

    fun read(src: ByteSource): List<Chapter> {
        val head = src.read(0, 12)
        if (head.size < 10) return emptyList()
        return when {
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> id3(src, head)
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> mp4(src)
            else -> emptyList()
        }
    }

    // ---------------------------------------------------------------- ID3v2

    private fun syncsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7f) shl 21) or ((b[at + 1].toInt() and 0x7f) shl 14) or
            ((b[at + 2].toInt() and 0x7f) shl 7) or (b[at + 3].toInt() and 0x7f)

    private fun int32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
            ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

    private fun id3(src: ByteSource, head: ByteArray): List<Chapter> {
        val major = head[3].toInt()
        if (major != 3 && major != 4) return emptyList() // v2.2 has no chapters
        val flags = head[5].toInt()
        val size = syncsafe(head, 6)
        if (size <= 0 || size > MAX_ID3) return emptyList()
        var tag = src.read(10, size)
        // Whole-tag unsynchronisation (v2.3): FF 00 -> FF.
        if (major == 3 && flags and 0x80 != 0) tag = unsync(tag)
        var pos = 0
        if (flags and 0x40 != 0) { // extended header
            pos = if (major == 4) syncsafe(tag, 0) else int32(tag, 0) + 4
        }
        return id3Frames(tag, pos, tag.size, major).sortedBy { it.startMs }
    }

    private fun unsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xff.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun id3Frames(tag: ByteArray, from: Int, to: Int, major: Int): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var pos = from
        while (pos + 10 <= to) {
            val id = String(tag, pos, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000' || !id.all { it.isLetterOrDigit() }) break // padding
            val size = if (major == 4) syncsafe(tag, pos + 4) else int32(tag, pos + 4)
            val start = pos + 10
            val end = start + size
            if (size < 0 || end > to) break
            if (id == "CHAP") chap(tag, start, end, major)?.let { chapters += it }
            pos = end
        }
        return chapters
    }

    private fun chap(tag: ByteArray, from: Int, to: Int, major: Int): Chapter? {
        var p = from
        while (p < to && tag[p] != 0.toByte()) p++ // element id
        p++
        if (p + 16 > to) return null
        val startMs = int32(tag, p).toLong() and 0xffffffffL
        val endMs = int32(tag, p + 4).toLong() and 0xffffffffL
        p += 16
        var title: String? = null
        var url: String? = null
        while (p + 10 <= to) {
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = if (major == 4) syncsafe(tag, p + 4) else int32(tag, p + 4)
            val start = p + 10
            val end = start + size
            if (size < 0 || end > to) break
            when (id) {
                "TIT2" -> title = text(tag, start, end)
                "WXXX" -> url = wxxx(tag, start, end)
                "WOAR", "WOAF", "WCOM" -> if (url == null) url = String(tag, start, end - start, Charsets.ISO_8859_1).trimEnd('\u0000').trim()
            }
            p = end
        }
        return Chapter(
            startMs = startMs,
            title = title?.ifBlank { null } ?: "Chapter",
            imageUrl = null,
            url = url?.takeIf { it.startsWith("http") },
            endMs = endMs.takeIf { it in (startMs + 1) until 0xffffffffL },
        )
    }

    private fun charset(enc: Int): Charset = when (enc) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun text(b: ByteArray, from: Int, to: Int): String {
        if (to <= from) return ""
        val enc = b[from].toInt()
        // Several values can be separated by NULs; the first is the title.
        return String(b, from + 1, to - from - 1, charset(enc)).split('\u0000').first().trim()
    }

    /** WXXX: encoding, a description ended by NUL (two NULs in UTF-16), then the URL in Latin-1. */
    private fun wxxx(b: ByteArray, from: Int, to: Int): String? {
        if (to <= from) return null
        val enc = b[from].toInt()
        var p = from + 1
        if (enc == 1 || enc == 2) {
            while (p + 1 < to && !(b[p] == 0.toByte() && b[p + 1] == 0.toByte())) p += 2
            p += 2
        } else {
            while (p < to && b[p] != 0.toByte()) p++
            p++
        }
        if (p >= to) return null
        return String(b, p, to - p, Charsets.ISO_8859_1).trimEnd('\u0000').trim().ifBlank { null }
    }

    // ---------------------------------------------------------------- MP4

    private class Box(val type: String, val start: Int, val end: Int)

    /** Child boxes of the box body b[from, to). */
    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = int32(b, p).toLong() and 0xffffffffL
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (p + 16 > to) break
                size = ((int32(b, p + 8).toLong() and 0xffffffffL) shl 32) or (int32(b, p + 12).toLong() and 0xffffffffL)
                header = 16
            } else if (size == 0L) {
                size = (to - p).toLong()
            }
            if (size < header || p + size > to) break
            out += Box(type, p + header, (p + size).toInt())
            p = (p + size).toInt()
        }
        return out
    }

    private fun child(b: ByteArray, parent: Box, type: String): Box? = boxes(b, parent.start, parent.end).firstOrNull { it.type == type }

    private fun mp4(src: ByteSource): List<Chapter> {
        // Walk the top-level boxes with small reads until moov turns up (it can be at the end).
        var offset = 0L
        var moov: ByteArray? = null
        for (i in 0 until 64) {
            val h = src.read(offset, 16)
            if (h.size < 8) break
            var size = int32(h, 0).toLong() and 0xffffffffL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L && h.size >= 16) {
                size = ((int32(h, 8).toLong() and 0xffffffffL) shl 32) or (int32(h, 12).toLong() and 0xffffffffL)
                header = 16
            } else if (size == 0L) {
                if (src.length < 0) break
                size = src.length - offset
            }
            if (size < header) break
            if (type == "moov") {
                if (size > MAX_MOOV) return emptyList()
                moov = src.read(offset + header, (size - header).toInt())
                break
            }
            offset += size
        }
        val m = moov ?: return emptyList()
        val root = Box("moov", 0, m.size)
        return quickTimeChapters(m, root, src).ifEmpty { neroChapters(m, root) }
    }

    /** Nero chapters: moov/udta/chpl. Times are in 100 ns units. */
    private fun neroChapters(m: ByteArray, moov: Box): List<Chapter> {
        val udta = child(m, moov, "udta") ?: return emptyList()
        val chpl = child(m, udta, "chpl") ?: return emptyList()
        var p = chpl.start
        val version = m[p].toInt()
        p += 4
        if (version != 0) p += 4
        if (p >= chpl.end) return emptyList()
        val count = m[p].toInt() and 0xff
        p++
        val out = mutableListOf<Chapter>()
        for (i in 0 until count) {
            if (p + 9 > chpl.end) break
            val t = ((int32(m, p).toLong() and 0xffffffffL) shl 32) or (int32(m, p + 4).toLong() and 0xffffffffL)
            val len = m[p + 8].toInt() and 0xff
            p += 9
            if (p + len > chpl.end) break
            val title = String(m, p, len, Charsets.UTF_8).trim()
            p += len
            out += Chapter(startMs = t / 10_000, title = title.ifBlank { "Chapter ${i + 1}" }, imageUrl = null)
        }
        return out.sortedBy { it.startMs }
    }

    /**
     * QuickTime chapters: a track whose tref/chap names a text track; each text sample is a chapter
     * title (2-byte length, then UTF-8 or UTF-16 with a BOM), timed by that track's stts.
     */
    private fun quickTimeChapters(m: ByteArray, moov: Box, src: ByteSource): List<Chapter> {
        val traks = boxes(m, moov.start, moov.end).filter { it.type == "trak" }
        val chapterIds = mutableSetOf<Int>()
        for (t in traks) {
            val tref = child(m, t, "tref") ?: continue
            val chap = child(m, tref, "chap") ?: continue
            var p = chap.start
            while (p + 4 <= chap.end) {
                chapterIds += int32(m, p)
                p += 4
            }
        }
        if (chapterIds.isEmpty()) return emptyList()
        val trak = traks.firstOrNull { t ->
            val tkhd = child(m, t, "tkhd") ?: return@firstOrNull false
            val version = m[tkhd.start].toInt()
            val idAt = tkhd.start + 4 + if (version == 1) 16 else 8
            idAt + 4 <= tkhd.end && int32(m, idAt) in chapterIds
        } ?: return emptyList()
        val mdia = child(m, trak, "mdia") ?: return emptyList()
        val mdhd = child(m, mdia, "mdhd") ?: return emptyList()
        val timescale = int32(m, mdhd.start + 4 + if (m[mdhd.start].toInt() == 1) 16 else 8).toLong()
        if (timescale <= 0) return emptyList()
        val stbl = child(m, mdia, "minf")?.let { child(m, it, "stbl") } ?: return emptyList()

        // Sample start times (stts).
        val stts = child(m, stbl, "stts") ?: return emptyList()
        val starts = mutableListOf<Long>()
        run {
            val n = int32(m, stts.start + 4)
            var t = 0L
            var p = stts.start + 8
            for (i in 0 until n) {
                if (p + 8 > stts.end) break
                val count = int32(m, p)
                val delta = int32(m, p + 4).toLong() and 0xffffffffL
                for (k in 0 until count.coerceAtMost(10_000)) {
                    starts += t
                    t += delta
                }
                p += 8
            }
        }
        // Sample sizes (stsz).
        val stsz = child(m, stbl, "stsz") ?: return emptyList()
        val fixed = int32(m, stsz.start + 4)
        val sampleCount = int32(m, stsz.start + 8)
        val sizes = List(sampleCount.coerceAtMost(10_000)) { i -> if (fixed != 0) fixed else int32(m, stsz.start + 12 + 4 * i) }
        // Chunk offsets (stco or co64).
        val chunkOffsets = mutableListOf<Long>()
        child(m, stbl, "stco")?.let { b ->
            val n = int32(m, b.start + 4)
            for (i in 0 until n) chunkOffsets += int32(m, b.start + 8 + 4 * i).toLong() and 0xffffffffL
        } ?: child(m, stbl, "co64")?.let { b ->
            val n = int32(m, b.start + 4)
            for (i in 0 until n) {
                val at = b.start + 8 + 8 * i
                chunkOffsets += ((int32(m, at).toLong() and 0xffffffffL) shl 32) or (int32(m, at + 4).toLong() and 0xffffffffL)
            }
        } ?: return emptyList()
        // Samples per chunk (stsc): runs of (firstChunk, samplesPerChunk).
        val stsc = child(m, stbl, "stsc") ?: return emptyList()
        val runs = mutableListOf<Pair<Int, Int>>()
        run {
            val n = int32(m, stsc.start + 4)
            for (i in 0 until n) {
                val at = stsc.start + 8 + 12 * i
                if (at + 12 > stsc.end) break
                runs += int32(m, at) to int32(m, at + 4)
            }
        }
        val offsets = mutableListOf<Long>()
        var sample = 0
        for (c in chunkOffsets.indices) {
            val chunkNo = c + 1
            val perChunk = runs.lastOrNull { it.first <= chunkNo }?.second ?: 1
            var off = chunkOffsets[c]
            for (k in 0 until perChunk) {
                if (sample >= sizes.size) break
                offsets += off
                off += sizes[sample]
                sample++
            }
        }
        val out = mutableListOf<Chapter>()
        for (i in 0 until minOf(offsets.size, starts.size, 500)) {
            val bytes = src.read(offsets[i], sizes[i].coerceIn(0, 4096))
            if (bytes.size < 2) continue
            val len = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
            val n = len.coerceAtMost(bytes.size - 2)
            val title = if (n >= 2 && ((bytes[2] == 0xfe.toByte() && bytes[3] == 0xff.toByte()) || (bytes[2] == 0xff.toByte() && bytes[3] == 0xfe.toByte()))) {
                String(bytes, 2, n, Charsets.UTF_16)
            } else {
                String(bytes, 2, n, Charsets.UTF_8)
            }.trim()
            out += Chapter(startMs = starts[i] * 1000 / timescale, title = title.ifBlank { "Chapter ${i + 1}" }, imageUrl = null)
        }
        return out.sortedBy { it.startMs }
    }
}
