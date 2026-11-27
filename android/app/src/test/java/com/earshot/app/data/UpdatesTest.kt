package com.earshot.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun newerVersions() {
        assertTrue(Updates.isNewer("1.2.10", "1.2.9"))
        assertTrue(Updates.isNewer("v1.0.0", "0.9.9"))
        assertTrue(Updates.isNewer("2.0.0", "1.99.99"))
    }

    @Test fun sameOrOlder() {
        assertFalse(Updates.isNewer("1.2.9", "1.2.9"))
        assertFalse(Updates.isNewer("1.2.8", "1.2.9"))
        assertFalse(Updates.isNewer("nonsense", "1.0.0"))
    }

    @Test fun devBuildsCountAsOldest() {
        assertTrue(Updates.isNewer("0.9.0", "0.1.0-dev"))
        assertTrue(Updates.isNewer("0.0.1", "0.0.0-ci.12"))
    }
}
