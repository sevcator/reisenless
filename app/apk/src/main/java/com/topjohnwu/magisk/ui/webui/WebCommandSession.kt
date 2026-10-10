package com.topjohnwu.magisk.ui.webui

import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.superuser.Shell
import java.io.File

internal class WebCommandSession {
    private val owners = mutableSetOf<File>()
    private var closed = false

    fun run(command: String): Shell.Result? {
        val owner = synchronized(owners) {
            if (closed) return null
            File.createTempFile("webui-command-", ".owner", AppContext.cacheDir).also { owners.add(it) }
        }
        try {
            val body = OwnedCommandBuilder.body(command, owner.path)
            return Shell.cmd("setsid sh -c ${WebUiCommandBuilder.shellQuote(body)}").exec()
        } finally {
            synchronized(owners) {
                owners.remove(owner)
                owner.delete()
                File(owner.path + ".cancel").delete()
            }
        }
    }

    fun close() {
        val pending = synchronized(owners) { closed = true; owners.toList() }
        Shell.EXECUTOR.execute {
            for (owner in pending) {
                val cancel = synchronized(owners) {
                    if (owner !in owners) false
                    else { File(owner.path + ".cancel").writeText(""); true }
                }
                if (!cancel) continue

                runCatching {
                    val process = ProcessBuilder("su", "-c", OwnedCommandBuilder.cancel(owner.path))
                        .redirectErrorStream(true).start()
                    process.outputStream.close()
                    var exited = false
                    repeat(100) {
                        if (!exited) {
                            exited = runCatching { process.exitValue(); true }.getOrDefault(false)
                            if (!exited) Thread.sleep(20)
                        }
                    }
                    if (!exited) process.destroy()
                    process.inputStream.close()
                }
            }
        }
    }
}
