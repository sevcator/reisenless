package com.topjohnwu.magisk.core.tasks

import org.junit.Assert.*
import org.junit.Test

class InstallSessionTest {
    @Test fun exclusiveUntilCleanupCompletesAndStaleCloseCannotReleaseNextSession() {
        val first = requireNotNull(InstallSession.acquire())
        try { assertNull(InstallSession.acquire()) } finally { first.close() }
        requireNotNull(InstallSession.acquire()).use {
            first.close()
            assertNull(InstallSession.acquire())
        }
        requireNotNull(InstallSession.acquire()).close()
    }
}
