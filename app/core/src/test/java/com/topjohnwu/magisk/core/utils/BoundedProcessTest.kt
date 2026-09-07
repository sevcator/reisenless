package com.topjohnwu.magisk.core.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BoundedProcessTest {
    private class FakeProcess(
        val stdout: InputStream = ByteArrayInputStream("ok\n".toByteArray()),
        val stderr: InputStream = ByteArrayInputStream(ByteArray(0)),
        var running: Boolean = false,
        val code: Int = 0,
    ) : Process() {
        var destroyed = false
        val stopped = CountDownLatch(1)
        override fun getInputStream() = stdout
        override fun getErrorStream() = stderr
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int = code
        override fun exitValue(): Int = if (running) throw IllegalThreadStateException() else code
        override fun destroy() { destroyed = true; running = false; stopped.countDown() }
    }

    @Test fun capturesExitCodeAndSeparateStreams() {
        val result = BoundedProcess.capture(FakeProcess(code = 10), 1000)
        assertEquals(10, result.code)
        assertEquals(listOf("ok"), result.output)
        assertTrue(result.errors.isEmpty())
    }

    @Test fun readerFailureIsNotSuccessfulEmptyOutput() {
        val process = FakeProcess(stdout = object : InputStream() {
            override fun read(): Int = throw IOException("broken pipe")
        })
        assertThrows(IOException::class.java) { BoundedProcess.capture(process, 1000) }
    }

    @Test fun outputLimitFailsEvenWhenExitCodeIsZero() {
        val process = FakeProcess(stdout = ByteArrayInputStream(ByteArray(33)))
        assertThrows(IOException::class.java) { BoundedProcess.capture(process, 1000, 32) }
    }

    @Test fun timeoutDestroysProcess() {
        val process = FakeProcess(running = true)
        assertThrows(IOException::class.java) { BoundedProcess.capture(process, 50) }
        assertTrue(process.destroyed)
    }

    @Test fun inheritedPipeCannotKeepCallerWaitingAfterParentExit() {
        val closed = CountDownLatch(1)
        val process = FakeProcess(stdout = object : InputStream() {
            override fun read(): Int { closed.await(); return -1 }
            override fun close() { closed.countDown() }
        })
        val start = System.nanoTime()
        assertThrows(IOException::class.java) { BoundedProcess.capture(process, 50) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000)
        assertTrue(closed.await(1, TimeUnit.SECONDS))
    }

    @Test fun interruptDoesNotLeakTheProcess() {
        val process = FakeProcess(running = true)
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                assertThrows(InterruptedException::class.java) { BoundedProcess.capture(process, 10000) }
            } catch (e: Throwable) { failure.set(e) }
        }
        worker.start()
        worker.interrupt()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertNull(failure.get())
        assertTrue(process.stopped.await(1, TimeUnit.SECONDS))
        assertTrue(process.destroyed)
    }
}
