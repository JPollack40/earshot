package com.earshot.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AdTrimTest {
    @Test
    fun extraLength() {
        assertEquals(0L, AdTrim.extraMs(0, 3_600_000)) // feed gives no length
        assertEquals(0L, AdTrim.extraMs(3_600_000, 3_605_000)) // 5 s: rounding
        assertEquals(90_000L, AdTrim.extraMs(3_600_000, 3_690_000)) // 90 s of ads
        assertEquals(0L, AdTrim.extraMs(1_800_000, 3_600_000)) // 30 min: the feed is wrong
        assertEquals(0L, AdTrim.extraMs(3_600_000, 3_500_000)) // shorter than stated
    }
}
