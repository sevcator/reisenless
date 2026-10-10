package com.topjohnwu.magisk.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UdongeRuntimeCommandsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun runInstall(failSwap: Boolean = false, complete: Boolean = true): Pair<File, Int> {
        val root = folder.newFolder("root")
        File(root, "runtime").mkdir()
        File(root, "runtime/original").writeText("old runtime")
        File(root, "runtime.new").mkdir()
        File(root, "runtime.new/stale").writeText("stale staging")
        File(root, "state").mkdir()
        File(root, "state/keybox.xml").writeText("private state")
        val extracted = folder.newFolder("extracted")
        if (complete) {
            for (name in listOf("service.sh", "worker.sh", "hideapps.dex", "version", "payload.id"))
                File(extracted, name).writeText("new runtime")
            File(extracted, "worker.sh").writeText("worker_stop_legacy_hunters() { :; }\n")
            File(extracted, "tee/arm64-v8a").mkdirs()
            File(extracted, "tee/arm64-v8a/libTEESimulator.so").writeText("library")
        }
        val log = File(root, "operations").invariantSeparatorsPath
        val setup = "chcon() { printf '%s\\n' \"\$*\" >> '$log'; }; " +
            "chmod() { printf 'chmod %s\\n' \"\$*\" >> '$log'; }; " +
            if (failSwap) "mv() { case \"\$1\" in */runtime.new) return 1;; esac; command mv \"\$@\"; }; " else ""
        val bash = if (System.getProperty("os.name").orEmpty().startsWith("Windows"))
            "C:/Program Files/Git/bin/bash.exe" else "bash"
        val process = ProcessBuilder(bash, "-c", setup + UdongeRuntimeCommands.install(
            root.invariantSeparatorsPath, extracted.invariantSeparatorsPath, "new-id", "private_runtime_f"))
            .redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText()
        return root to process.waitFor()
    }

    @Test fun failedSwapRestoresTheOriginalRuntimeAndReportsFailure() {
        val (root, code) = runInstall(failSwap = true)
        assertTrue("swap failure was reported as success", code != 0)
        assertEquals("old runtime", File(root, "runtime/original").readText())
    }

    @Test fun incompletePayloadLeavesTheCurrentRuntimeIntact() {
        val (root, code) = runInstall(complete = false)
        assertTrue("incomplete payload was accepted", code != 0)
        assertEquals("old runtime", File(root, "runtime/original").readText())
    }

    @Test fun successfulUpdateClearsStagingAndUsesTheCurrentPolicyWithoutChangingStatePermissions() {
        val (root, code) = runInstall()
        assertEquals(0, code)
        assertFalse(File(root, "runtime/stale").exists())
        assertEquals("new-id\n", File(root, "runtime/payload.id").readText())
        val operations = File(root, "operations").readText()
        assertTrue(operations.contains("u:object_r:private_runtime_f:s0"))
        assertFalse(operations.contains("chmod -R 700 ${root.invariantSeparatorsPath}\n"))
        assertEquals("private state", File(root, "state/keybox.xml").readText())
    }
}
