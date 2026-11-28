package com.earshot.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 16: Serena's app_config row. Anything missing or odd must leave features ON. */
class AppConfigTest {
    @Test
    fun emptyRowKeepsEverythingOn() {
        val c = AppConfig.fromJson(JSONObject("{}"))
        assertEquals(AppConfig(), c)
        assertTrue(c.aiOn && c.adFindingOn && c.transcribeOn && c.browseAiOn)
        assertNull(c.banner)
    }

    @Test
    fun onlyAnExplicitFalseSwitchesOff() {
        val c = AppConfig.fromJson(JSONObject("""{"ai_on":false,"ad_finding_on":null,"transcribe_on":"false","browse_ai_on":true}"""))
        assertFalse(c.aiOn)
        assertTrue(c.adFindingOn)
        assertTrue(c.transcribeOn) // a string isn't a switch
        assertTrue(c.browseAiOn)
    }

    @Test
    fun blankTextIsNone() {
        val c = AppConfig.fromJson(JSONObject("""{"banner":"  ","min_supported_version":null,"hold_version":" 1.1.0 ","paused_message":""}"""))
        assertNull(c.banner)
        assertNull(c.minSupportedVersion)
        assertEquals("1.1.0", c.holdVersion)
        assertEquals(AppConfig.DEFAULT_PAUSED, c.pausedMessage)
    }

    @Test
    fun devBuildsAreNeverWarned() {
        // Unit tests run the debug build type, where Updates.automatic is false.
        assertFalse(AppConfig(minSupportedVersion = "99.0.0").updateRequired)
    }
}
