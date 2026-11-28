package com.earshot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class Phase17FormatTest {
    @Test fun fineSpeedSnapsToTwentiethsWithinRange() {
        assertEquals(1.25f, fineSpeed(1.26f), 0.0001f)
        assertEquals(1.35f, fineSpeed(1.349f), 0.0001f)
        assertEquals(0.5f, fineSpeed(0.1f), 0.0001f)
        assertEquals(3.0f, fineSpeed(4.0f), 0.0001f)
        assertEquals("1.25×", formatSpeed(fineSpeed(1.25f)))
        assertEquals("1.3×", formatSpeed(fineSpeed(1.3f)))
    }
}
