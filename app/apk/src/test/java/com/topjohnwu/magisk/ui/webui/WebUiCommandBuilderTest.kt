package com.topjohnwu.magisk.ui.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiCommandBuilderTest {
    @Test
    fun rejectsModulePathTraversalIds() {
        assertTrue(WebUiCommandBuilder.isValidModuleId("module.name-1"))
        assertFalse(WebUiCommandBuilder.isValidModuleId("."))
        assertFalse(WebUiCommandBuilder.isValidModuleId(".."))
        assertFalse(WebUiCommandBuilder.isValidModuleId("../other"))
    }

    @Test
    fun onlyTreatsTheExactHttpsAssetOriginAsInternal() {
        assertTrue(WebUiCommandBuilder.isInternalUrl("https", "appassets.androidplatform.net", -1))
        assertTrue(WebUiCommandBuilder.isInternalUrl("HTTPS", "APPASSETS.ANDROIDPLATFORM.NET", 443))
        assertFalse(WebUiCommandBuilder.isInternalUrl("http", "appassets.androidplatform.net", -1))
        assertFalse(WebUiCommandBuilder.isInternalUrl("https", "appassets.androidplatform.net", 444))
        assertFalse(WebUiCommandBuilder.isInternalUrl("https", "evil.example", -1))
    }

    @Test
    fun quotesSpawnArgumentsAsSingleShellArguments() {
        assertEquals("tool 'path with spaces' '$(touch /tmp/nope)'", WebUiCommandBuilder.appendArguments(
            "tool",
            listOf("path with spaces", "$(touch /tmp/nope)"),
        ))
    }

    @Test
    fun safelyQuotesWorkingDirectoryAndEnvironmentValues() {
        assertEquals(
            "cd '/path with spaces' && export SAFE='a'\\''b' && run",
            WebUiCommandBuilder.withOptions("run", "/path with spaces", mapOf("SAFE" to "a'b", "bad;key" to "x")),
        )
    }
}
