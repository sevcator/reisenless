package com.topjohnwu.magisk.ui.flash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FlashZipCommandsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun runInstall(displayName: String, installerExit: Int = 0): Pair<Int, String> {
        val directory = folder.newFolder("installer's directory")
        val archive = File(folder.root, "user's module.zip").apply { writeText("fixture") }
        File(directory, "update-binary").writeText(
            "printf '%s\\n' \"\$3\"\nexit $installerExit\n")
        val bash = if (System.getProperty("os.name").orEmpty().startsWith("Windows"))
            "C:/Program Files/Git/bin/bash.exe" else "bash"
        val command = FlashZipCommands.install(directory.invariantSeparatorsPath,
            archive.invariantSeparatorsPath, displayName)
        val process = ProcessBuilder(bash, "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test fun apostrophesAndSpacesReachTheInstallerUnchanged() {
        val (code, output) = runInstall("user's module.zip")
        assertEquals(output, 0, code)
        assertEquals("- Installing user's module.zip\n${folder.root.invariantSeparatorsPath}/user's module.zip\n", output)
    }

    @Test fun providerDisplayNameIsPrintedWithoutExecutingItsContents() {
        val marker = File(folder.root, "injected").invariantSeparatorsPath
        val name = "module'; touch '$marker'; #.zip"
        val (code, output) = runInstall(name)
        assertFalse("display name executed as shell code", File(marker).exists())
        assertEquals(output, 0, code)
        assertEquals("- Installing $name\n${folder.root.invariantSeparatorsPath}/user's module.zip\n", output)
    }

    @Test fun installerFailureIsReturnedToTheCaller() {
        val (code, output) = runInstall("module.zip", 7)
        assertEquals(output, 7, code)
    }
}
