package com.topjohnwu.magisk.ui.module

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ActionCommandTest {
    @get:Rule val folder = TemporaryFolder()

    private fun runAction(script: String): Pair<Int, String> {
        val directory = folder.newFolder("private modules", "module's name")
        File(directory, "action.sh").writeText(script)
        val bash = if (System.getProperty("os.name").orEmpty().startsWith("Windows"))
            "C:/Program Files/Git/bin/bash.exe" else "bash"
        val process = ProcessBuilder(bash, "-c", moduleActionCommand(directory.invariantSeparatorsPath))
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output.trim()
    }

    @Test fun runsTheActionInItsConfiguredModuleDirectory() {
        assertEquals(0 to "module's name", runAction("basename \"\$PWD\"\n"))
    }

    @Test fun failedActionPreservesItsExitStatus() {
        assertEquals(17 to "failed", runAction("echo failed\nexit 17\n"))
    }
}
