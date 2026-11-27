package com.earshot.app.data

import com.earshot.app.data.VoiceMatch.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMatchTest {
    @Test
    fun resumeWords() {
        assertEquals(Intent.Resume, VoiceMatch.parse(""))
        assertEquals(Intent.Resume, VoiceMatch.parse("podcasts"))
        assertEquals(Intent.Resume, VoiceMatch.parse("play podcasts on Earshot"))
        assertEquals(Intent.Resume, VoiceMatch.parse("Earshot"))
    }

    @Test
    fun latestOfAShow() {
        assertEquals(Intent.Latest("morbid"), VoiceMatch.parse("the latest Morbid"))
        assertEquals(Intent.Latest("radiolab"), VoiceMatch.parse("newest episode of Radiolab"))
        assertEquals(Intent.Latest("behind the bastards"), VoiceMatch.parse("the latest Behind the Bastards podcast on Earshot"))
        assertEquals(Intent.Latest("radiolab"), VoiceMatch.parse("Radiolab's latest episode"))
        assertEquals(Intent.Any("morbid"), VoiceMatch.parse("Morbid on Earshot"))
    }

    @Test
    fun looseTitles() {
        val shows = listOf("Morbid", "Behind the Bastards", "Radiolab", "The Daily", "99% Invisible")
        assertEquals("Morbid", VoiceMatch.best("morbid", shows) { it })
        assertEquals("Radiolab", VoiceMatch.best("radio lab", shows) { it })
        assertEquals("Behind the Bastards", VoiceMatch.best("behind the bastard", shows) { it })
        assertEquals("99% Invisible", VoiceMatch.best("99 invisible", shows) { it })
        assertEquals("The Daily", VoiceMatch.best("daily", shows) { it })
        assertNull(VoiceMatch.best("cooking with gas", shows) { it })
        assertTrue(VoiceMatch.score("more bid", "Morbid") >= 0.6)
    }
}
