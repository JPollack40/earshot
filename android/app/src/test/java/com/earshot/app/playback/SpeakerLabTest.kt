package com.earshot.app.playback

import com.earshot.app.data.transcript.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerLabTest {
    private val segs = listOf(
        Segment(0, 4_000, "Hello and welcome.", "spk_1"),
        Segment(4_500, 9_000, "Thanks for having me.", "spk_2"),
        Segment(20_000, 25_000, "Back after the break.", "spk_1"),
    )

    @Test fun speakerAtFollowsTheTranscript() {
        assertEquals("spk_1", SpeakerLab.speakerAt(segs, 0))
        assertEquals("spk_1", SpeakerLab.speakerAt(segs, 4_200)) // short gap: still the last speaker
        assertEquals("spk_2", SpeakerLab.speakerAt(segs, 6_000))
        assertEquals("spk_2", SpeakerLab.speakerAt(segs, 11_500))
        assertNull(SpeakerLab.speakerAt(segs, 15_000)) // long gap
        assertEquals("spk_1", SpeakerLab.speakerAt(segs, 30_000 - 2_000))
        assertNull(SpeakerLab.speakerAt(emptyList(), 1_000))
    }

    @Test fun raisingOneTurnsTheOthersDown() {
        val (v, others) = SpeakerLab.volumes(mapOf("spk_2" to SpeakerLab.Adjust(levelDb = 6)))
        assertEquals(1f, v.getValue("spk_2"), 0.001f)
        assertEquals(0.501f, others, 0.01f)
    }

    @Test fun loweringOneLeavesTheOthersAtFull() {
        val (v, others) = SpeakerLab.volumes(mapOf("spk_1" to SpeakerLab.Adjust(levelDb = -6)))
        assertEquals(0.501f, v.getValue("spk_1"), 0.01f)
        assertEquals(1f, others, 0.001f)
    }

    @Test fun jsonRoundTrip() {
        val m = mapOf("spk_1" to SpeakerLab.Adjust(3, 1.25f), "spk_2" to SpeakerLab.Adjust(-6, 1f))
        assertEquals(m, SpeakerLab.parse(SpeakerLab.toJson(m)))
    }
}
