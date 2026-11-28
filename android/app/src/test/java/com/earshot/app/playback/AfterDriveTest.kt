package com.earshot.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AfterDriveTest {
    @Test fun nothingHappenedNoCard() {
        assertNull(AfterDrive.text(0, emptyList()))
    }

    @Test fun bookmarksAndFinished() {
        assertEquals("1 bookmark", AfterDrive.text(1, emptyList()))
        assertEquals("3 bookmarks · finished 1: Morbid 700", AfterDrive.text(3, listOf("Morbid 700")))
        assertEquals("Finished 2: A, B", AfterDrive.text(0, listOf("A", "B")))
    }
}
