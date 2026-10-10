package com.topjohnwu.magisk.core.download

import android.app.Notification
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class DownloadProcessorTest {
    @get:Rule val directory = TemporaryFolder()

    private class Destination : OutputStream() {
        var closed = false
        override fun write(value: Int) {}
        override fun close() { closed = true }
    }

    private fun processor(cache: File = directory.root): DownloadProcessor {
        val context = mock(Context::class.java)
        `when`(context.cacheDir).thenReturn(cache)
        return DownloadProcessor(object : DownloadNotifier {
            override val context = context
            override fun notifyUpdate(id: Int, editor: (Notification.Builder) -> Unit) {}
        })
    }

    @Test fun interruptedModuleDownloadClosesDestination() {
        val source = object : InputStream() {
            override fun read(): Int = throw IOException("connection interrupted")
        }
        val destination = Destination()
        val processor = processor()

        val failure = assertThrows(IOException::class.java) {
            runBlocking { processor.handleModule(source, destination) }
        }

        assertEquals("connection interrupted", failure.message)
        assertTrue("download failed before taking ownership of its output", destination.closed)
        assertTrue(directory.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun malformedModuleArchiveClosesDestination() {
        val source = ByteArrayInputStream("not a zip".toByteArray())
        val destination = Destination()
        val processor = processor()

        assertThrows(IOException::class.java) {
            runBlocking { processor.handleModule(source, destination) }
        }

        assertTrue("invalid archive left its output open", destination.closed)
        assertTrue(directory.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun unavailableModuleCacheClosesDestination() {
        val source = ByteArrayInputStream(byteArrayOf())
        val destination = Destination()
        val processor = processor(File(directory.root, "missing/cache"))

        assertThrows(IOException::class.java) {
            runBlocking { processor.handleModule(source, destination) }
        }

        assertTrue("cache failure left its output open", destination.closed)
    }
}
