package com.earshot.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeRewindTest {
    @Test fun pauses() {
        assertEquals(0L, ResumeRewind.amountMs(300, interrupted = false))
        assertEquals(2_000L, ResumeRewind.amountMs(20_000, interrupted = false))
        assertEquals(5_000L, ResumeRewind.amountMs(5 * 60_000, interrupted = false))
        assertEquals(10_000L, ResumeRewind.amountMs(2 * 60 * 60_000L, interrupted = false))
    }

    @Test fun interruptions() {
        assertEquals(3_000L, ResumeRewind.amountMs(4_000, interrupted = true))
        assertEquals(5_000L, ResumeRewind.amountMs(10 * 60_000, interrupted = true))
        assertEquals(0L, ResumeRewind.amountMs(100, interrupted = true))
    }

    @Test fun loadsAgain() {
        assertEquals(0L, ResumeRewind.startFor(0, 60 * 60_000))
        assertEquals(0L, ResumeRewind.startFor(1_000, 60 * 60_000))
        assertEquals(50_000L, ResumeRewind.startFor(60_000, 2 * 60 * 60_000L))
        assertEquals(60_000L, ResumeRewind.startFor(60_000, 500))
    }
}
