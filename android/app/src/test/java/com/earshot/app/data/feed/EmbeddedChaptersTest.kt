package com.earshot.app.data.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class EmbeddedChaptersTest {
    private class Bytes(private val b: ByteArray) : ByteSource {
        override val length = b.size.toLong()
        override fun read(offset: Long, size: Int): ByteArray {
            if (offset >= b.size) return ByteArray(0)
            return b.copyOfRange(offset.toInt(), minOf(b.size, (offset + size).toInt()))
        }
    }

    private fun int32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun syncsafe(v: Int) = byteArrayOf(((v shr 21) and 0x7f).toByte(), ((v shr 14) and 0x7f).toByte(), ((v shr 7) and 0x7f).toByte(), (v and 0x7f).toByte())

    private fun frame(id: String, body: ByteArray, v4: Boolean) =
        id.toByteArray(Charsets.ISO_8859_1) + (if (v4) syncsafe(body.size) else int32(body.size.toLong())) + byteArrayOf(0, 0) + body

    private fun chap(id: String, start: Long, end: Long, title: String, url: String?, v4: Boolean): ByteArray {
        var body = id.toByteArray() + byteArrayOf(0) + int32(start) + int32(end) + int32(0xffffffffL) + int32(0xffffffffL)
        body += frame("TIT2", byteArrayOf(3) + title.toByteArray(Charsets.UTF_8), v4)
        if (url != null) body += frame("WXXX", byteArrayOf(0) + "link".toByteArray() + byteArrayOf(0) + url.toByteArray(), v4)
        return frame("CHAP", body, v4)
    }

    private fun id3(v4: Boolean, frames: ByteArray): ByteArray {
        val padded = frames + ByteArray(32)
        return "ID3".toByteArray() + byteArrayOf(if (v4) 4 else 3, 0, 0) + syncsafe(padded.size) + padded + ByteArray(100) { 0x55 }
    }

    @Test
    fun readsId3v23Chapters() {
        val tag = id3(
            false,
            chap("ch1", 60_000, 120_000, "Second", null, false) + chap("ch0", 0, 60_000, "Intro", "https://example.com/a", false),
        )
        val list = EmbeddedChapters.read(Bytes(tag))
        assertEquals(2, list.size)
        assertEquals("Intro", list[0].title)
        assertEquals(0L, list[0].startMs)
        assertEquals(60_000L, list[0].endMs)
        assertEquals("https://example.com/a", list[0].url)
        assertEquals("Second", list[1].title)
        assertNull(list[1].url)
    }

    @Test
    fun readsId3v24Chapters() {
        val tag = id3(true, chap("a", 1_500, 9_000, "Ünïcode title", null, true))
        val list = EmbeddedChapters.read(Bytes(tag))
        assertEquals(1, list.size)
        assertEquals("Ünïcode title", list[0].title)
        assertEquals(1_500L, list[0].startMs)
    }

    @Test
    fun noTagMeansNoChapters() {
        assertTrue(EmbeddedChapters.read(Bytes(ByteArray(200) { 0x11 })).isEmpty())
    }

    private fun box(type: String, body: ByteArray) = int32(8L + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body

    @Test
    fun readsNeroChaptersWithMoovAtTheEnd() {
        val out = ByteArrayOutputStream()
        fun entry(t100ns: Long, title: String) {
            out.write(int32(t100ns shr 32)); out.write(int32(t100ns and 0xffffffffL))
            val b = title.toByteArray(); out.write(b.size); out.write(b)
        }
        out.write(byteArrayOf(1, 0, 0, 0)); out.write(int32(0)); out.write(2)
        entry(0, "Start")
        entry(905_000_000L, "Part two") // 90.5 s
        val chpl = box("chpl", out.toByteArray())
        val file = box("ftyp", "M4A ".toByteArray() + int32(0)) + box("mdat", ByteArray(500)) + box("moov", box("udta", chpl))
        val list = EmbeddedChapters.read(Bytes(file))
        assertEquals(listOf("Start", "Part two"), list.map { it.title })
        assertEquals(90_500L, list[1].startMs)
    }

    @Test
    fun skipTargetJumpsPastSkippedChapters() {
        val ch = listOf(Chapter(0, "Intro", null), Chapter(10_000, "Ad", null), Chapter(20_000, "Ad 2", null), Chapter(30_000, "Talk", null))
        assertNull(SkippedChapters.skipTarget(ch, setOf(10_000L), 5_000, 100_000))
        assertEquals(20_000L, SkippedChapters.skipTarget(ch, setOf(10_000L), 10_100, 100_000))
        assertEquals(30_000L, SkippedChapters.skipTarget(ch, setOf(10_000L, 20_000L), 10_100, 100_000))
        // Last chapter skipped: jump to the end of the episode.
        assertEquals(100_000L, SkippedChapters.skipTarget(ch, setOf(30_000L), 31_000, 100_000))
        // Start times within a second still match.
        assertEquals(20_000L, SkippedChapters.skipTarget(ch, setOf(10_400L), 12_000, 100_000))
    }

    @Test
    fun badgesAndPeople() {
        assertEquals("S2 E14", episodeBadge(2, 14.0))
        assertEquals("E1.5", episodeBadge(null, 1.5))
        assertNull(episodeBadge(null, null))
        // (org.json is a stub in JVM unit tests, so only the summary is checked here.)
        val people = listOf(Person("Bo", "guest", null, "https://b.example"), Person("Ann", "host", null, null), Person("Cy", "host", null, null))
        assertEquals("Hosts: Ann, Cy · Guest: Bo", Persons.summary(people))
    }
}
