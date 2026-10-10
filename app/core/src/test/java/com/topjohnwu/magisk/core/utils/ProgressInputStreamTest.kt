package com.topjohnwu.magisk.core.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ProgressInputStreamTest {
    @Test fun singleByteReadsReachEofAndReportActualBytes() {
        val progress = mutableListOf<Long>()
        val stream = ProgressInputStream(ByteArrayInputStream(byteArrayOf(0, 127, -1)), progress::add)
        assertEquals(0, stream.read())
        assertEquals(127, stream.read())
        assertEquals(255, stream.read())
        assertEquals(-1, stream.read())
        stream.close()
        assertEquals(3L, progress.last())
    }

    @Test fun mixedReadsCountBytesOnceAndCloseReportsOnce() {
        val progress = mutableListOf<Long>()
        val stream = ProgressInputStream(ByteArrayInputStream("abcdef".toByteArray()), progress::add)
        assertEquals('a'.code, stream.read())
        assertEquals(2, stream.read(ByteArray(2)))
        assertEquals(3, stream.read(ByteArray(5), 1, 3))
        assertEquals(-1, stream.read())
        stream.close()
        val count = progress.size
        stream.close()
        assertEquals(6L, progress.last())
        assertEquals(count, progress.size)
    }
}
