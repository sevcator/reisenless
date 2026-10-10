package com.topjohnwu.magisk.hideapps

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HideAppsRepositoryTest {
    @get:Rule val directory = TemporaryFolder()

    private fun context(): Context = mock(Context::class.java).also {
        `when`(it.filesDir).thenReturn(directory.root)
        `when`(it.packageName).thenReturn("com.example.manager")
    }

    @Test fun existingRepositoryObservesAnotherScreensSettings() {
        val context = context()
        val settings = HideAppsRepository(context)
        val editor = HideAppsRepository(context)

        editor.setEnabled(false)
        editor.setRule("com.example.caller", HideAppsRule(packages = setOf("com.example.target")))

        assertFalse(settings.config.enabled)
        assertEquals(setOf("com.example.target"), settings.config.scope["com.example.caller"]?.packages)
    }

    @Test fun settingsTogglePreservesTargetsSelectedByAnotherScreen() {
        val context = context()
        val settings = HideAppsRepository(context)
        val editor = HideAppsRepository(context)

        editor.setHidden("com.example.target", true)
        settings.setEnabled(false)

        val persisted = HideAppsRepository(context).config
        assertFalse(persisted.enabled)
        assertEquals(setOf("com.example.manager", "com.example.target"), persisted.hiddenPackages)
        assertEquals(persisted, editor.config)
    }

    @Test fun concurrentRepositoryEditsPreserveBothTargets() {
        val context = context()
        val first = HideAppsRepository(context)
        val second = HideAppsRepository(context)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first to "com.example.first", second to "com.example.second").map { (repo, target) ->
                executor.submit {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    repo.setHidden(target, true)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            results.forEach { it.get(5, TimeUnit.SECONDS) }

            assertEquals(setOf("com.example.manager", "com.example.first", "com.example.second"),
                HideAppsRepository(context).config.hiddenPackages)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}
