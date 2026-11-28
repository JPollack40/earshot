package com.earshot.app.data

import com.earshot.app.data.feed.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterRulesTest {
    @Test fun parsesWords() {
        assertEquals(listOf("sponsor", "house keeping", "patreon"), ChapterRules.parse(" Sponsor, House keeping;patreon , x, sponsor"))
        assertTrue(ChapterRules.parse("  ").isEmpty())
    }

    @Test fun matchesTitlesIgnoringCase() {
        val ch = listOf(
            Chapter(0, "Intro", null),
            Chapter(60_000, "A word from our SPONSORS", null),
            Chapter(120_000, "The interview", null),
            Chapter(900_000, "Patreon thank-yous", null),
        )
        assertEquals(setOf(60_000L, 900_000L), ChapterRules.matching(ch, ChapterRules.parse("sponsor, patreon")))
        assertTrue(ChapterRules.matching(ch, emptyList()).isEmpty())
    }
}
