package com.topjohnwu.magisk.ui.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebUiUserAgentTest {
    @Test fun normalUserAgentRetainsItsIdentity() {
        val agent = "Mozilla/5.0 (Linux; Android 15; Pixel 9a) AppleWebKit/537.36"
        assertEquals(agent, WebUiUserAgent.normalize(agent))
    }

    @Test fun modelFromWindowsPropertyFileCannotInjectCarriageReturn() {
        assertEquals("Mozilla/5.0 (Linux; Android 15; Pixel 9a )",
            WebUiUserAgent.normalize("Mozilla/5.0 (Linux; Android 15; Pixel 9a\r)"))
    }

    @Test fun invalidHeaderCharactersAreRemovedWithoutMergingTokens() {
        val result = WebUiUserAgent.normalize("\u0000Model\r\nVersion\tDevice\u007f")
        assertEquals("Model  Version Device", result)
        assertFalse(result.any { it < ' ' || it == '\u007f' })
    }
}
