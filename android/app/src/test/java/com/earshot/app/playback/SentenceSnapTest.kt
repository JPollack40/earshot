package com.earshot.app.playback

import com.earshot.app.data.transcript.Segment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSnapTest {
    private val segs = listOf(
        Segment(0, 4_000, "Welcome back to the show."),
        Segment(4_000, 9_000, "Today we're talking about"),
        Segment(9_000, 14_000, "octopuses and how they think."),
        Segment(14_000, 20_000, "Are they smart? Very."),
        Segment(20_000, 31_000, "Let's start with the arms"),
        Segment(31_000, 40_000, "which have their own neurons."),
    )
    private val starts = SentenceSnap.starts(segs)

    @Test fun sentenceStartsFollowEndPunctuation() {
        assertArrayEquals(longArrayOf(0, 4_000, 14_000, 20_000), starts)
    }

    @Test fun skipBackLandsOnTheSentenceAtOrBefore() {
        // From 33 s back 15 s = 18 s: the sentence that started at 14 s.
        assertEquals(14_000L, SentenceSnap.snapBack(starts, 18_000, 33_000))
        // Exactly on a start stays there.
        assertEquals(20_000L, SentenceSnap.snapBack(starts, 20_000, 35_000))
    }

    @Test fun neverMoreThanFiveSecondsExtra() {
        // 11 s: the last start (4 s) is 7 s earlier, too far, so the plain 11 s.
        assertEquals(11_000L, SentenceSnap.snapBack(starts, 11_000, 26_000))
        assertEquals(0L, SentenceSnap.snapBack(starts, -3_000, 10_000))
        assertEquals(18_000L, SentenceSnap.snapBack(LongArray(0), 18_000, 33_000))
    }

    @Test fun noPunctuationUsesEverySegment() {
        val bare = (0 until 20).map { Segment(it * 3_000L, it * 3_000L + 3_000, "words words") }
        assertEquals(20, SentenceSnap.starts(bare).size)
    }

    @Test fun sayAgainReplaysTheSentenceJustHeard() {
        assertEquals(20_000L, SentenceSnap.againFrom(starts, 28_000))
        // Just after a new sentence began (20.5 s): the one before (14 s).
        assertEquals(14_000L, SentenceSnap.againFrom(starts, 20_500))
        // Without a transcript: 10 s back.
        assertEquals(18_000L, SentenceSnap.againFrom(LongArray(0), 28_000))
    }
}
