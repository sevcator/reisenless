package com.topjohnwu.magisk.core.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotCacheTest {
    @Test fun concurrentRequestsShareOneLoad() = runBlocking {
        var loads = 0
        val cache = SnapshotCache { ++loads }
        assertEquals(List(20) { 1 }, List(20) { async { cache.get() } }.awaitAll())
        assertEquals(1, loads)
        cache.invalidate()
        assertEquals(2, cache.get())
    }

    @Test fun invalidationDuringLoadDoesNotPublishStaleSnapshot() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var loads = 0
        val cache = SnapshotCache {
            val value = ++loads
            if (value == 1) { started.complete(Unit); finish.await() }
            value
        }
        val result = async { cache.get() }
        started.await()
        cache.invalidate()
        finish.complete(Unit)
        assertEquals(2, result.await())
        assertEquals(2, cache.get())
    }
}
