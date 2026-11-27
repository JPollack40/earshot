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

    // Phase 14: ad breaks in generated chapters (as on Morbid's "Kathleen Peterson (Part 2)").
    private val chapters = listOf(
        0L to "Sponsor: Meta",
        30_000L to "Sponsor: Whole Foods",
        58_000L to "Sponsor: Lowe's",
        90_000L to "Introduction",
        927_000L to "Ad break",
        1_018_000L to "The staircase",
        3_000_000L to "Advertisement",
    )

    @Test fun adChaptersBecomeAds() {
        val found = AdSkip.fromChapters(chapters.shuffled())
        assertEquals(listOf(0L, 30_000L, 58_000L, 927_000L, 3_000_000L), found.map { it.startMs })
        assertEquals(1_018_000L, found[3].endMs)
        assertEquals(AdSkip.TO_END, found.last().endMs)
    }

    @Test fun sponsorChaptersAtTheStartAreSkippedTogether() {
        assertEquals(90_000L, AdSkip.skipTarget(AdSkip.fromChapters(chapters), 5_000))
    }

    @Test fun ordinaryChaptersAreNotAds() {
        assertEquals(false, AdSkip.isAdChapter("Adam's story"))
        assertEquals(false, AdSkip.isAdChapter("Advice for listeners"))
        assertEquals(false, AdSkip.isAdChapter("The sponsorship scandal"))
        assertEquals(true, AdSkip.isAdChapter("Ad break"))
        assertEquals(true, AdSkip.isAdChapter("Sponsor: Lowe's"))
    }
}
