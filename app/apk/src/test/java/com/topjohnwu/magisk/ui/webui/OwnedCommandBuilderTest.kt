package com.topjohnwu.magisk.ui.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class OwnedCommandBuilderTest {
    @Test fun cancellationEscalatesForSurvivingMembersAfterLeaderExits() {
        val directory = java.nio.file.Files.createTempDirectory("webui-group").toFile()
        try {
            val owner = File(directory, "owner").invariantSeparatorsPath
            val bash = if (System.getProperty("os.name").orEmpty().startsWith("Windows"))
                "C:/Program Files/Git/bin/bash.exe" else "bash"
            val process = ProcessBuilder(bash).start()
            val setup = """
                record=${WebUiCommandBuilder.shellQuote(owner)}
                printf '%s start boot\n' "${'$'}${'$'}" > "${'$'}record"
                cat() { echo boot; }
                tr() { echo "${'$'}record"; }
                ps() { printf 'PID PGID\n%s %s\n999999 %s\n' "${'$'}${'$'}" "${'$'}${'$'}" "${'$'}${'$'}"; }
                awk() {
                    case "${'$'}1" in
                        *'print ${'$'}20'*)
                            case "${'$'}2" in *999999*) echo child-start;; *)
                                [ ! -f "${'$'}record.gone" ] && echo start;; esac;;
                        *'print ${'$'}3'*) echo "${'$'}${'$'}";;
                        *) command awk "${'$'}@";;
                    esac
                }
                kill() { echo "${'$'}1"; [ "${'$'}1" != -TERM ] || touch "${'$'}record.gone"; return 0; }
                sleep() { :; }
            """.trimIndent()
            process.outputStream.bufferedWriter().use { it.write(setup + "\n" + OwnedCommandBuilder.cancel(owner)) }
            val output = process.inputStream.bufferedReader().readText().trim()
            assertEquals(0, process.waitFor())
            assertEquals("-STOP\n-TERM\n-CONT\n-KILL", output)
        } finally { directory.deleteRecursively() }
    }

    @Test fun execRunsInAChildAndStillReleasesOwnership() {
        val directory = java.nio.file.Files.createTempDirectory("webui-owner").toFile()
        try {
            val owner = File(directory, "owner")
            val bash = if (System.getProperty("os.name").orEmpty().startsWith("Windows"))
                "C:/Program Files/Git/bin/bash.exe" else "bash"
            val process = ProcessBuilder(bash).start()
            process.outputStream.bufferedWriter().use {
                it.write(OwnedCommandBuilder.body("exec echo completed", owner.invariantSeparatorsPath))
            }
            assertEquals("completed", process.inputStream.bufferedReader().readText().trim())
            assertEquals(0, process.waitFor())
            assertFalse("exec replaced the owner wrapper", owner.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancelledCommandNeverRunsEvenWhenQueuedBeforeDestroy() {
        val directory = java.nio.file.Files.createTempDirectory("webui-owner").toFile()
        try {
            val owner = File(directory, "owner")
            File(owner.path + ".cancel").writeText("")
            val bash = if (System.getProperty("os.name").orEmpty().startsWith("Windows"))
                "C:/Program Files/Git/bin/bash.exe" else "bash"

            val process = ProcessBuilder(bash).start()
            process.outputStream.bufferedWriter().use {
                it.write(OwnedCommandBuilder.body("echo SHOULD_NOT_RUN", owner.invariantSeparatorsPath))
            }
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(130, process.waitFor())
            assertEquals("", output)
        } finally { directory.deleteRecursively() }
    }
}
