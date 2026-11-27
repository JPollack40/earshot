package com.earshot.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdSkipTest {
    private val ads = listOf(
        AdSpan(60_000, 120_000, "Sponsor A"),
        AdSpan(122_000, 150_000, "Sponsor B"),
        AdSpan(1_800_000, 1_860_000, "Mid-roll"),
    )

    @Test fun outsideAdsNothingHappens() {
        assertNull(AdSkip.skipTarget(ads, 30_000))
        assertNull(AdSkip.skipTarget(ads, 600_000))
    }

    @Test fun backToBackAdsAreSkippedTogether() {
        assertEquals(150_000L, AdSkip.skipTarget(ads, 61_000))
    }

    @Test fun singleAd() {
        assertEquals(1_860_000L, AdSkip.skipTarget(ads, 1_800_500))
    }

    @Test fun nearTheEndOfAnAdItIsNotPlayingAnyMore() {
        assertNull(AdSkip.current(ads, 1_859_000))
        assertNull(AdSkip.skipTarget(ads, 1_859_000))
    }

    @Test fun anAdAlreadySkippedPlaysWhenGoneBackTo() {
        assertNull(AdSkip.skipTarget(ads, 1_810_000, alreadySkipped = setOf(1_800_000L)))
        assertEquals(1_860_000L, AdSkip.skipTarget(ads, 1_810_000, alreadySkipped = setOf(60_000L)))
    }

    @Test fun currentFindsTheAd() {
        assertEquals("Sponsor B", AdSkip.current(ads, 130_000)?.label)
    }
}
