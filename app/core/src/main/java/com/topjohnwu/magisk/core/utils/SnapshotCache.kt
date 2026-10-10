package com.topjohnwu.magisk.core.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

class SnapshotCache<T : Any>(private val load: suspend () -> T) {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private var cached: Pair<Long, T>? = null

    fun invalidate() { generation.incrementAndGet() }

    suspend fun get(): T = mutex.withLock {
        while (true) {
            val version = generation.get()
            cached?.takeIf { it.first == version }?.let { return@withLock it.second }
            val value = load()
            if (version == generation.get()) {
                cached = version to value
                return@withLock value
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}
