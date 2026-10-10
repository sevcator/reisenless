package com.topjohnwu.magisk.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BlockingBatchTest {
    @Test fun batchesPreserveOrderAndOnlyScheduleOnce() {
        val callbacks = mutableListOf<() -> Unit>()
        val output = mutableListOf<Int>()
        val batch = BlockingBatch<Int>(4, callbacks::add) { output.addAll(it) }
        (1..4).forEach(batch::put)
        assertEquals(1, callbacks.size)
        callbacks.removeAt(0).invoke()
        assertEquals(listOf(1, 2, 3, 4), output)
        batch.put(5)
        batch.drain()
        callbacks.removeAt(0).invoke()
        assertEquals(listOf(1, 2, 3, 4, 5), output)
    }

    @Test fun fullBatchAppliesBackpressureWithoutDroppingOutput() {
        val output = mutableListOf<Int>()
        val batch = BlockingBatch<Int>(1, {}) { output.addAll(it) }
        batch.put(1)
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val producer = Thread { started.countDown(); batch.put(2); finished.countDown() }
        producer.start()
        started.await()
        assertFalse(finished.await(50, TimeUnit.MILLISECONDS))
        batch.drain()
        check(finished.await(2, TimeUnit.SECONDS))
        producer.join()
        batch.drain()
        assertEquals(listOf(1, 2), output)
    }
}
