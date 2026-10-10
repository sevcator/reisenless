package com.topjohnwu.magisk.core.utils

import java.io.FilterInputStream
import java.io.InputStream

class ProgressInputStream(
    base: InputStream,
    val progressEmitter: (Long) -> Unit
) : FilterInputStream(base) {

    private var bytesRead = 0L
    private var lastUpdate = 0L
    private var updated = false
    private var closed = false

    private fun emitProgress() {
        val cur = System.nanoTime()
        if (!updated || cur - lastUpdate >= 1_000_000_000L) {
            updated = true
            lastUpdate = cur
            progressEmitter(bytesRead)
        }
    }

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) {
            bytesRead++
            emitProgress()
        }
        return b
    }

    override fun read(b: ByteArray): Int {
        return read(b, 0, b.size)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val sz = super.read(b, off, len)
        if (sz > 0) {
            bytesRead += sz
            emitProgress()
        }
        return sz
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            super.close()
        } finally {
            progressEmitter(bytesRead)
        }
    }
}
