package com.topjohnwu.magisk.core.utils

import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/** One deadline covers both the child and its pipes, including inherited pipes. */
internal object BoundedProcess {
    data class Output(val code: Int, val output: List<String>, val errors: List<String>)

    fun capture(process: Process, timeoutMs: Long, maxBytes: Int = 1024 * 1024): Output {
        require(timeoutMs > 0 && maxBytes > 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        fun remaining(): Long = (deadline - System.nanoTime()).also {
            if (it <= 0) throw TimeoutException("command or output pipe timed out")
        }
        fun reader(input: InputStream): FutureTask<ByteArray> = FutureTask {
            input.use {
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (count > maxBytes - bytes.size()) throw IOException("command output exceeds limit")
                    bytes.write(buffer, 0, count)
                }
                bytes.toByteArray()
            }
        }.also { task -> thread(name = "command-output", isDaemon = true) { task.run() } }

        try {
            process.outputStream.close()
            val stdout = reader(process.inputStream)
            val stderr = reader(process.errorStream)
            var code: Int
            while (true) {
                // Propagate reader failures even if the child is still running.
                if (stdout.isDone) stdout.get()
                if (stderr.isDone) stderr.get()
                remaining()
                try {
                    code = process.exitValue()
                    break
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(10)
                }
            }
            fun FutureTask<ByteArray>.lines(): List<String> {
                val text = get(remaining(), TimeUnit.NANOSECONDS).toString(Charsets.UTF_8)
                if (text.isEmpty()) return emptyList()
                return text.removeSuffix("\n").removeSuffix("\r").split(Regex("\r?\n"))
            }
            return Output(code, stdout.lines(), stderr.lines())
        } catch (e: ExecutionException) {
            throw IOException("unable to read command output", e.cause)
        } catch (e: TimeoutException) {
            throw IOException("command or output pipe timed out", e)
        } finally {
            // Android's pre-26 Process.destroy() kills the child. Newer APIs also
            // provide explicit forced termination. Never wait indefinitely here.
            // A stream close can contend with a blocked read in the JVM. Do not
            // turn cleanup into a second unbounded wait on the calling thread.
            val cleanup = thread(name = "command-cleanup", isDaemon = true) {
                runCatching {
                    try { process.exitValue() } catch (_: IllegalThreadStateException) {
                        if (Build.VERSION.SDK_INT >= 26) process.destroyForcibly() else process.destroy()
                    }
                }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
            }
            try {
                cleanup.join(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
