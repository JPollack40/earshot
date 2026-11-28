package com.earshot.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 16: reading the release's SHA-256 file (sha256sum's format). */
class SelfUpdateTest {
    private val hex = "98EA6E4F216F2FB4B69FFF9B3A44842C38686CA685F3F55DC48C5D3FB1107BE4"

    @Test fun readsSha256sumLine() {
        assertEquals(hex.lowercase(), SelfUpdate.parseSha("$hex  earshot-1.1.0.apk\n"))
        assertEquals(hex.lowercase(), SelfUpdate.parseSha(hex))
    }

    @Test fun rejectsAnythingElse() {
        assertNull(SelfUpdate.parseSha("not a checksum"))
        assertNull(SelfUpdate.parseSha(hex.drop(1) + "  earshot.apk"))
    }
}
