package com.topjohnwu.magisk.terminal

import android.os.Handler
import android.os.Looper
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.utils.BlockingBatch
import java.util.concurrent.CountDownLatch

private val busyboxPath = "${Const.DATABIN}/${Const.BUSYBOX_NAME}"

private val mainHandler = Handler(Looper.getMainLooper())

fun TerminalEmulator.appendOnMain(bytes: ByteArray, len: Int) {
    val output = outputBatch()
    if (Looper.myLooper() == Looper.getMainLooper()) output.drain()
    output.put(bytes.copyOf(len))
}

private fun TerminalEmulator.outputBatch(): BlockingBatch<ByteArray> = synchronized(this) {
    pendingOutput ?: BlockingBatch<ByteArray>(64,
        { drain -> mainHandler.postDelayed({ drain() }, 16) },
        { chunks ->
            chunks.forEach { append(it, it.size) }
            onScreenUpdate?.invoke()
        }).also { pendingOutput = it }
}

fun TerminalEmulator.flushOnMain() {
    if (Looper.myLooper() == Looper.getMainLooper()) outputBatch().drain()
    else {
        val done = CountDownLatch(1)
        mainHandler.post { try { outputBatch().drain() } finally { done.countDown() } }
        done.await()
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
                emulator.appendOnMain(buffer, n)
            }
        }

        process.waitFor() == 0
    } catch (e: Exception) {
        emulator.appendLineOnMain("! error: ${e.message?.lowercase()}")
        false
    } finally {
        emulator.flushOnMain()
    }
}
