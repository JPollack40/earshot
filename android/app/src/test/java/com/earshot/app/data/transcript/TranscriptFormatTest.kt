package com.earshot.app.data.transcript

import com.earshot.app.data.EpisodeSearch
import com.earshot.app.data.db.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFormatTest {
    private val vtt = """
        WEBVTT

        NOTE made by hand

        1
        00:00:01.000 --> 00:00:03.500
        <v Alice>Welcome to the show.</v>

        2
        00:00:03.500 --> 00:00:09.000
        <v Alice>Today we talk about octopuses &amp; squid.

        00:01:00.000 --> 00:01:08.000
        <v Bob>They have three hearts.
    """.trimIndent()

    @Test
    fun parsesWebVtt() {
        val p = TranscriptFormat.parse(vtt, "text/vtt")
        assertTrue(p.timed)
        // The first two cues are short and by the same speaker, so they're joined.
        assertEquals(2, p.segments.size)
        assertEquals(1_000L, p.segments[0].startMs)
        assertEquals("Welcome to the show. Today we talk about octopuses & squid.", p.segments[0].text)
        assertEquals("Alice", p.segments[0].speaker)
        assertEquals(60_000L, p.segments[1].startMs)
        assertEquals("Bob", p.segments[1].speaker)
    }

    @Test
    fun parsesSrtWithHours() {
        val srt = "1\r\n01:02:03,450 --> 01:02:05,000\r\nHello there\r\n\r\n2\r\n01:02:30,000 --> 01:02:33,000\r\nSecond line\r\n"
        val p = TranscriptFormat.parse(srt, "application/x-subrip")
        assertEquals(2, p.segments.size)
        assertEquals(3_723_450L, p.segments[0].startMs)
        assertEquals("Second line", p.segments[1].text)
    }

    @Test
    fun htmlHasNoTimings() {
        val p = TranscriptFormat.parse("<p>First paragraph.</p><p>Second&nbsp;one.</p>", "text/html")
        assertFalse(p.timed)
        assertEquals(listOf("First paragraph.", "Second one."), p.segments.map { it.text })
    }

    @Test
    fun joinsWordLevelCues() {
        val words = (0 until 20).map { i -> Segment(i * 400L, i * 400L + 350, "w$i", "A") }
        val tidy = TranscriptFormat.tidy(words)
        assertEquals(1, tidy.size)
        assertEquals(0L, tidy[0].startMs)
        assertEquals(19 * 400L + 350, tidy[0].endMs)
    }

    @Test
    fun quoteCoversTheWindow() {
        val segs = listOf(Segment(0, 10_000, "a"), Segment(10_000, 50_000, "b"), Segment(50_000, 70_000, "c"), Segment(120_000, 130_000, "d"))
        assertEquals("b c", TranscriptFormat.quote(segs, true, 20_000, 65_000))
        assertNull(TranscriptFormat.quote(segs, true, 80_000, 100_000))
        assertNull(TranscriptFormat.quote(segs, false, 0, 100_000))
    }

    @Test
    fun indexAtFindsThePlayingSegment() {
        val segs = listOf(Segment(0, 1, "a"), Segment(5_000, 6_000, "b"), Segment(9_000, 9_500, "c"))
        assertEquals(-1, TranscriptFormat.indexAt(listOf(Segment(1_000, 2_000, "x")), 500))
        assertEquals(0, TranscriptFormat.indexAt(segs, 4_999))
        assertEquals(1, TranscriptFormat.indexAt(segs, 5_000))
        assertEquals(2, TranscriptFormat.indexAt(segs, 99_000))
    }

    @Test
    fun searchFindsWordsAndPhrasesAcrossSegments() {
        val segs = listOf(
            Segment(0, 1, "The octopus has"),
            Segment(1, 2, "three hearts and blue blood."),
            Segment(2, 3, "Nothing here."),
            Segment(3, 4, "Another octopus fact: it’s smart."),
        )
        assertEquals(listOf(0, 3), TranscriptFormat.matchingSegments(segs, EpisodeSearch.terms("octopus")))
        // Split across segments 0 and 1.
        assertEquals(listOf(0), TranscriptFormat.matchingSegments(segs, EpisodeSearch.terms("\"octopus has three\"")))
        assertEquals(listOf(3), TranscriptFormat.matchingSegments(segs, EpisodeSearch.terms("it's smart")))
        assertTrue(TranscriptFormat.matchingSegments(segs, EpisodeSearch.terms("squid")).isEmpty())
    }

    @Test
    fun speakerNames() {
        assertEquals("Speaker 2", TranscriptFormat.speakerName("spk_2"))
        assertEquals("Alice", TranscriptFormat.speakerName("Alice"))
    }

    @Test
    fun markdownMarksBookmarksInTheTranscript() {
        val info = MarkdownExport.EpisodeInfo("e1", "Octopus Hour", "Animal Show", "https://x/a.mp3")
        val t = MarkdownExport.TranscriptInfo(
            listOf(Segment(0, 30_000, "Intro.", "spk_1"), Segment(30_000, 90_000, "Hearts.", "spk_2")),
            timed = true,
            source = "gemini",
        )
        val b = Bookmark(id = "b1", episodeId = "e1", positionMs = 45_000, startMs = 0, endMs = 50_000, note = "Three hearts!", createdAt = 1)
        val full = MarkdownExport.fullTranscript(info, t, listOf(b))
        assertTrue(full.startsWith("# Octopus Hour\n"))
        assertTrue(full.contains("**Speaker 1:** [0:00] Intro."))
        val mark = full.indexOf("🔖 Bookmark 0:45**: Three hearts!")
        assertTrue(mark > full.indexOf("Intro.") && mark < full.indexOf("Hearts."))

        val list = MarkdownExport.episodeBookmarks(info, listOf(b), t)
        assertTrue(list.contains("### 0:45\n\n> Intro. Hearts.\n\nThree hearts!"))
        assertEquals("Octopus Hour bookmarks.md", MarkdownExport.fileName("Octopus Hour: bookmarks"))
    }
}
