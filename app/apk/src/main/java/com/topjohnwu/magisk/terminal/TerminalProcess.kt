package com.topjohnwu.magisk.terminal

import android.os.Handler
import android.os.Looper
import com.topjohnwu.magisk.core.Const

private val busyboxPath = "${Const.DATABIN}/${Const.BUSYBOX_NAME}"

private val mainHandler = Handler(Looper.getMainLooper())

fun TerminalEmulator.appendOnMain(bytes: ByteArray, len: Int) {
    mainHandler.post {
        append(bytes, len)
        onScreenUpdate?.invoke()
    }
}

fun TerminalEmulator.appendLineOnMain(line: String) {
    val bytes = "$line\r\n".toByteArray(Charsets.UTF_8)
    appendOnMain(bytes, bytes.size)
}







fun runSuCommand(emulator: TerminalEmulator, command: String): Boolean {
    return try {
        val cols = emulator.mColumns
        val rows = emulator.mRows
        val wrappedCmd = "export TERM=xterm-256color; stty cols $cols rows $rows 2>/dev/null; $command"
        val escapedCmd = wrappedCmd.replace("'", "'\\''")
        val busyboxDir = "${Const.TMPDIR}/terminal-${android.os.Process.myPid()}"
        val busyboxAlias = "$busyboxDir/busybox"
        val shellCommand = "mkdir -p '${busyboxDir.replace("'", "'\\''")}' && " +
            "ln -sf '${busyboxPath.replace("'", "'\\''")}' '${busyboxAlias.replace("'", "'\\''")}' && " +
            "'${busyboxAlias.replace("'", "'\\''")}' script -q -c '$escapedCmd' /dev/null; " +
            "result=\$?; rm -f '${busyboxAlias.replace("'", "'\\''")}' && " +
            "rmdir '${busyboxDir.replace("'", "'\\''")}' 2>/dev/null; exit \$result"

        val process = ProcessBuilder(
            "su", "-c",
            shellCommand
        ).redirectErrorStream(true).start()

        process.outputStream.close()

        val buffer = ByteArray(4096)
        process.inputStream.use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                emulator.appendOnMain(buffer.copyOf(n), n)
            }
        }

        process.waitFor() == 0
    } catch (e: Exception) {
        emulator.appendLineOnMain("! error: ${e.message?.lowercase()}")
        false
    }
}
