package com.topjohnwu.magisk.ui.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class OwnedCommandDeviceTest {
    @Test fun cancellationStopsExecAndTermResistantDescendants() {
        val serial = System.getenv("REISENLESS_TEST_DEVICE")
        assumeTrue(!serial.isNullOrBlank())
        val adb = File(System.getenv("ANDROID_HOME"), "platform-tools/adb.exe").path
        fun run(vararg args: String): String {
            val process = ProcessBuilder(adb, "-s", serial, *args).redirectErrorStream(true).start()
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
            return output.trim()
        }
        fun root(command: String) = run("shell", "su -c ${WebUiCommandBuilder.shellQuote(command)}")
        for (command in listOf("exec sleep 30", "trap '' TERM; while :; do sleep 1; done")) {
            val remote = "/data/local/tmp/reisenless-webui-regression-${System.nanoTime()}"
            val local = java.nio.file.Files.createTempDirectory("webui-adb").toFile()
            val owner = "$remote/owner"
            val wrapper = File(local, "wrapper.sh")
            val cancel = File(local, "cancel.sh")
            wrapper.writeText("setsid sh -c ${WebUiCommandBuilder.shellQuote(OwnedCommandBuilder.body(command, owner))}\n")
            cancel.writeText(OwnedCommandBuilder.cancel(owner) + "\n")
            run("shell", "mkdir -p $remote")
            try {
                run("push", wrapper.path, "$remote/wrapper.sh")
                run("push", cancel.path, "$remote/cancel.sh")
                root("sh $remote/wrapper.sh </dev/null >/dev/null 2>&1 &")
                var metadata = ""
                repeat(20) {
                    if (metadata.isBlank()) {
                        Thread.sleep(50)
                        metadata = root("cat $owner 2>/dev/null || true")
                    }
                }
                val pid = metadata.substringBefore(' ')
                assertTrue("owner was not published: $metadata", pid.toIntOrNull() != null)
                val members = root("ps -A -o PID,PGID | awk -v group=$pid '\$2 == group {print \$1}'")
                assertTrue("no owned group", members.isNotBlank())
                root(": > $owner.cancel; sh $remote/cancel.sh")
                val surviving = root("for member in ${members.replace('\n', ' ')}; do " +
                    "state=\$(awk '{sub(/^.*\\) /, \"\"); print \$1}' /proc/\$member/stat 2>/dev/null); " +
                    "case \$state in ''|Z) ;; *) echo \$member:\$state ;; esac; done")
                assertEquals("owned descendants survived: $surviving", "", surviving)
            } finally {
                root("sh $remote/cancel.sh; rm -rf $remote")
                local.deleteRecursively()
            }
        }
    }
}
