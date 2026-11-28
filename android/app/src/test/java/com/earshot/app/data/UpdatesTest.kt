package com.earshot.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- Phase 16: betas and version codes ----

    @Test fun betasOrderBelowTheirStable() {
        assertTrue(Updates.isNewer("1.2.0", "1.2.0-beta.3"))
        assertTrue(Updates.isNewer("1.2.0-beta.3", "1.2.0-beta.2"))
        assertTrue(Updates.isNewer("1.2.0-beta.1", "1.1.9"))
        assertFalse(Updates.isNewer("1.2.0-beta.9", "1.2.0"))
        assertTrue(Updates.isNewer("1.1.0-beta.1", "0.1.0-dev"))
    }

    @Test fun versionCodes() {
        assertEquals(1_010_099L, Updates.order("1.1.0"))
        assertEquals(1_010_003L, Updates.order("v1.1.0-beta.3"))
        assertEquals(2_000_099L, Updates.order("2.0.0"))
        assertNull(Updates.order("1.1.0-beta.99"))
        assertNull(Updates.order("1.1.0-beta.0"))
        assertNull(Updates.order("1.100.0"))
        assertNull(Updates.order("1.1.0-dev"))
        // Always above every code of the old scheme (1.0.0 was 10000).
        assertTrue(Updates.order("1.0.1-beta.1")!! > 10_000)
    }

    private fun release(tag: String, pre: Boolean = false, draft: Boolean = false) =
        JSONObject().put("tag_name", tag).put("prerelease", pre).put("draft", draft)

    @Test fun pickRespectsChannelAndHold() {
        val all = listOf(release("v1.2.0-beta.2", pre = true), release("v1.1.0"), release("v1.2.0-beta.1", pre = true), release("v1.3.0-beta.1", pre = true, draft = true))
        assertEquals("v1.1.0", Updates.pick(all, holdOrder = null, beta = false)?.optString("tag_name"))
        assertEquals("v1.2.0-beta.2", Updates.pick(all, holdOrder = null, beta = true)?.optString("tag_name"))
        assertEquals("v1.2.0-beta.1", Updates.pick(all, holdOrder = Updates.order("1.2.0-beta.1"), beta = true)?.optString("tag_name"))
        assertNull(Updates.pick(all, holdOrder = Updates.order("1.0.0"), beta = false))
    }
}
