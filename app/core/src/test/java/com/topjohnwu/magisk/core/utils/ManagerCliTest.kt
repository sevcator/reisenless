package com.topjohnwu.magisk.core.utils

import org.junit.Assert.*
import org.junit.Test

class ManagerCliTest {
    @Test fun stableCodesDoNotDependOnEnglishStderr() {
        val statuses = mapOf(0 to ManagerCli.Status.SUCCESS,
            10 to ManagerCli.Status.ACCESS_DENIED,
            11 to ManagerCli.Status.DAEMON_UNAVAILABLE,
            12 to ManagerCli.Status.PROTOCOL_MISMATCH,
            13 to ManagerCli.Status.DATABASE_FAILURE,
            1 to ManagerCli.Status.COMMAND_FAILED)
        for ((code, status) in statuses) assertEquals(status, ManagerCli.statusForCode(code))
    }

    @Test fun failedEmptyQueryThrowsTypedException() {
        for (status in ManagerCli.Status.entries.filter { it != ManagerCli.Status.SUCCESS }) {
            val result = ManagerCli.Result(-1, emptyList(), listOf("failure"), status)
            val error = assertThrows(ManagerCliException::class.java) { result.requireSuccess() }
            assertEquals(status, error.status)
            assertFalse(result.isSuccess)
        }
    }
}
