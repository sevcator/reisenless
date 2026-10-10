package com.topjohnwu.magisk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UdongeCommandsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun enable(vararg markers: String): File {
        val root = folder.newFolder()
        File(root, "state").mkdir()
        markers.forEach { File(root, "state/$it").createNewFile() }
        File(root, "runtime").mkdir()
        File(root, "runtime/keybox_heal.sh").apply {
            writeText("#!/bin/sh\nprintf '%s' \"\$1\" > '${root.invariantSeparatorsPath}/started'\n")
            setExecutable(true)
        }
        val bash = if (System.getProperty("os.name").orEmpty().startsWith("Windows"))
            "C:/Program Files/Git/bin/bash.exe" else "bash"
        val process = ProcessBuilder(bash, "-c", UdongeCommands.enableBackground(root.invariantSeparatorsPath))
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        assertTrue(File(root, "state/background-updates").isFile)
        return root
    }

    @Test fun reenablingBackgroundUpdatesStartsTheStoppedHunter() {
        val root = enable("enabled")
        repeat(100) { if (!File(root, "started").exists()) Thread.sleep(10) }
        assertTrue("hunter did not restart", File(root, "started").isFile)
        assertEquals("hunt_daemon", File(root, "started").readText())
    }

    @Test fun disabledOrPendingRebootProfilesDoNotStartWork() {
        for (markers in listOf(emptyArray(), arrayOf("enabled", "disabled"), arrayOf("enabled", "pending-reboot"))) {
            assertFalse(File(enable(*markers), "started").exists())
        }
    }
}
