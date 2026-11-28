package com.earshot.app.data.transcript

import com.earshot.app.data.feed.Person
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerNamesTest {
    private val segs = listOf(
        Segment(0, 1, "Hi", "spk_1"),
        Segment(1, 2, "Hello", "spk_0"),
        Segment(2, 3, "Welcome", "spk_1"),
    )
    private fun p(name: String, role: String) = Person(name, role, null, null)

    @Test fun speakersInFirstSpokenOrder() {
        assertEquals(listOf("spk_1", "spk_0"), SpeakerNames.speakers(segs))
    }

    @Test fun guessHostsFirstWhenCountsMatch() {
        val people = listOf(p("Guest Gail", "guest"), p("Host Hal", "host"))
        assertEquals(mapOf("spk_1" to "Host Hal", "spk_0" to "Guest Gail"), SpeakerNames.guess(SpeakerNames.speakers(segs), people))
    }

    @Test fun noGuessWhenCountsDiffer() {
        assertEquals(emptyMap<String, String>(), SpeakerNames.guess(listOf("spk_0", "spk_1"), listOf(p("A", "host"))))
        // A producer (neither host nor guest) makes the list unreliable: no guess.
        assertEquals(emptyMap<String, String>(), SpeakerNames.guess(listOf("spk_0", "spk_1"), listOf(p("A", "host"), p("B", "producer"))))
    }

    @Test fun labelPrefersYourNameThenTheGuess() {
        assertEquals("Sam", SpeakerNames.label("spk_0", mapOf("spk_0" to "Sam"), mapOf("spk_0" to "Hal")))
        assertEquals("Hal?", SpeakerNames.label("spk_0", emptyMap(), mapOf("spk_0" to "Hal")))
        assertEquals("Speaker 0", SpeakerNames.label("spk_0", emptyMap(), emptyMap()))
    }
}
