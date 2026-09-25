package com.topjohnwu.magisk.core.tasks

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class VbmetaPatchTest {
    @Test fun undersizedVbmetaIsSkippedWithoutChangingItsData() {
        val data = ByteArray(255) { it.toByte() }
        val original = data.copyOf()

        assertFalse(patchVbmetaData(data))
        assertArrayEquals(original, data)
    }

    @Test fun validVbmetaFlagsArePatched() {
        val data = ByteArray(256)

        assertTrue(patchVbmetaData(data))
        assertEquals(3, ByteBuffer.wrap(data).getInt(120))
    }
}
