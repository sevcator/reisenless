package com.topjohnwu.magisk.core.download

import java.io.InputStream

internal class DownloadTransfers {
    private val active = mutableMapOf<Int, InputStream?>()
    @Volatile var cancelled = false
        private set

    @Synchronized fun begin(id: Int): Boolean {
        if (cancelled || active.containsKey(id)) return false
        active[id] = null
        return true
    }

    fun attach(id: Int, stream: InputStream): Boolean {
        val accepted = synchronized(this) {
            if (cancelled || !active.containsKey(id)) false
            else { active[id] = stream; true }
        }
        if (!accepted) runCatching { stream.close() }
        return accepted
    }

    @Synchronized fun end(id: Int) { active.remove(id) }

    fun cancel() {
        val streams = synchronized(this) {
            if (cancelled) return
            cancelled = true
            active.values.filterNotNull().also { active.clear() }
        }
        streams.forEach { runCatching { it.close() } }
    }
}
