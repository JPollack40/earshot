package com.earshot.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeSearchTest {
    private val notes = "<p>This week we talk with <b>Dr. Jane Goodall</b> about chimpanzees &amp; conservation " +
        "in Gombe. Plus: what it&#8217;s like to spend 60 years in the field.</p><a href=\"https://example.com\">Link</a>"
    private val index = EpisodeSearch.index("Episode 412: Chimps", notes)

    private fun find(q: String) = EpisodeSearch.matches(index, EpisodeSearch.terms(q))

    @Test fun matchesTitleOrNotesAnyCase() {
        assertTrue(find("chimps"))
        assertTrue(find("GOODALL"))
        assertTrue(find("gombe conservation")) // all words, any order
    }

    @Test fun everyWordMustAppear() = assertFalse(find("goodall octopus"))

    @Test fun htmlTagsAreIgnored() {
        assertFalse(find("href"))
        assertFalse(find("example.com"))
    }

    @Test fun entitiesAreDecoded() {
        assertTrue(find("chimpanzees & conservation"))
        assertTrue(find("it's")) // &#8217; is a curly apostrophe; a typed straight one still matches
    }

    @Test fun quotedPhrases() {
        assertTrue(find("\"jane goodall\""))
        assertFalse(find("\"goodall jane\""))
    }

    @Test fun snippetShowsWhyItMatched() {
        val s = EpisodeSearch.snippet(notes, EpisodeSearch.terms("gombe"))!!
        assertTrue(s, s.contains("Gombe"))
        assertNull(EpisodeSearch.snippet(notes, EpisodeSearch.terms("412")))
    }

    @Test fun blankQueryHasNoTerms() = assertEquals(emptyList<String>(), EpisodeSearch.terms("   "))
}
