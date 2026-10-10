package com.topjohnwu.magisk.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DownloadTransfersTest {
    private class BlockingStream : InputStream() {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var closes = 0
        override fun read(): Int { reading.countDown(); closed.await(); return -1 }
        override fun close() { closes++; closed.countDown() }
    }

    @Test fun cancellationClosesActiveTransferAndUnblocksRead() {
        val transfers = DownloadTransfers()
        val stream = BlockingStream()
        assertTrue(transfers.begin(1))
        assertTrue(transfers.attach(1, stream))
        val finished = CountDownLatch(1)
        val reader = Thread { stream.read(); finished.countDown() }
        reader.start()
        stream.reading.await()
        transfers.cancel()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        reader.join()
        transfers.cancel()
        assertEquals(1, stream.closes)
    }

    @Test fun streamArrivingAfterCancellationIsClosedAndRejected() {
        val transfers = DownloadTransfers()
        val stream = BlockingStream()
        transfers.begin(1)
        transfers.cancel()
        assertFalse(transfers.attach(1, stream))
        assertEquals(1, stream.closes)
        assertFalse(transfers.begin(2))
    }

    @Test fun duplicateRequestCannotReplaceActiveTransfer() {
        val transfers = DownloadTransfers()
        assertTrue(transfers.begin(1))
        assertFalse(transfers.begin(1))
        transfers.end(1)
        assertTrue(transfers.begin(1))
    }
}
