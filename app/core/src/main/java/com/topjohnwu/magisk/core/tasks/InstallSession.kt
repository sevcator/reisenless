package com.topjohnwu.magisk.core.tasks

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Shared by launched-APK and selected-APK operations; held through cleanup. */
internal object InstallSession {
    private val active = AtomicBoolean(false)

    fun acquire(): Closeable? {
        if (!active.compareAndSet(false, true)) return null
        val closed = AtomicBoolean(false)
        return Closeable {
            if (closed.compareAndSet(false, true)) active.set(false)
        }
    }
}
