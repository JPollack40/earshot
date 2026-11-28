package com.earshot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    @Test fun blankShowsEverything() {
        assertEquals(SettingsSearch.GROUPS.keys, SettingsSearch.groups("  "))
    }

    @Test fun findsByRowWords() {
        assertEquals(setOf("Background"), SettingsSearch.groups("Battery"))
        assertEquals(setOf("Appearance"), SettingsSearch.groups("text size"))
        assertTrue("Downloads" in SettingsSearch.groups("wifi"))
    }

    @Test fun everyWordMustMatch() {
        assertEquals(setOf("Playback", "Time saved"), SettingsSearch.groups("smart speed"))
        assertTrue(SettingsSearch.groups("battery speed").isEmpty())
    }

    @Test fun groupNamesMatchToo() {
        assertTrue("About" in SettingsSearch.groups("about"))
    }
}
