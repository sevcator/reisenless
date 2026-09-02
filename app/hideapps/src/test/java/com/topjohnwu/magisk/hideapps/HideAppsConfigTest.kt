package com.topjohnwu.magisk.hideapps

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HideAppsConfigTest {
    private val manager = "org.example.private.manager"
    private val ordinary = "org.example.ordinary"
    private val viewer = "org.example.trusted.viewer"

    @Test
    fun ordinaryAppCannotResolveManager() {
        val config = HideAppsConfig(
            enabled = true,
            hiddenPackages = setOf(manager),
            viewerWhitelist = setOf(viewer),
        )

        assertTrue(config.shouldHide(ordinary, manager, isSystemApp = false))
        assertFalse(config.shouldHide(viewer, manager, isSystemApp = false))
        assertFalse(config.shouldHide(manager, manager, isSystemApp = false))
    }

    @Test
    fun liveManagerIdentityIsAlwaysPublishedAsHiddenAndExempt() {
        val runtime = HideAppsConfig(
            enabled = true,
            viewerWhitelist = setOf(viewer),
        ).toRuntimeConfig(manager, systemPackages = emptySet())

        val global = runtime.lineSequence().first { it.startsWith("G\t") }.split('\t')
        val hidden = global[2].split(',').toSet()
        val exempt = global[3].split(',').toSet()

        assertTrue(manager in hidden)
        assertTrue(manager in exempt)
        assertTrue(viewer in exempt)
        assertFalse(ordinary in exempt)
    }
}
