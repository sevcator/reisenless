package com.topjohnwu.magisk.core.utils

import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class MediaStoreUtilsTest {
    @Test
    fun providerRejectionBecomesFileFailure() {
        val rejected = IllegalArgumentException("Primary directory not allowed")
        val failure = assertThrows(IOException::class.java) {
            MediaStoreUtils.storageOperation { throw rejected }
        }
        assertSame(rejected, failure.cause)
    }

    @Test
    fun revokedStorageAccessBecomesFileFailure() {
        val denied = SecurityException("Permission revoked")
        val failure = assertThrows(IOException::class.java) {
            MediaStoreUtils.storageOperation { throw denied }
        }
        assertSame(denied, failure.cause)
    }

    @Test
    fun existingIoFailureIsPreserved() {
        val original = IOException("Disk full")
        assertSame(original, assertThrows(IOException::class.java) {
            MediaStoreUtils.storageOperation { throw original }
        })
    }

    @Test
    fun invalidGeneratedNamesFailBeforeAccessingStorage() {
        assertThrows(IOException::class.java) {
            MediaStoreUtils.getPatchOutputFile("abcd.img", "../ezqC")
        }
        assertThrows(IOException::class.java) {
            MediaStoreUtils.getPatchOutputFile("../abcd.img", "ezqC")
        }
    }
}
