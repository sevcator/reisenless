package com.topjohnwu.magisk.hideapps

import android.content.Context
import java.io.File

class HideAppsRepository(private val context: Context) {
    private val file = File(context.filesDir, "hide_apps.json")

    val config: HideAppsConfig
        get() = synchronized(storageLock) { load() }

    companion object {
        private val storageLock = Any()
    }

    fun setRule(packageName: String, rule: HideAppsRule?) {
        update { config ->
            val scope = config.scope.toMutableMap()
            if (rule == null) scope.remove(packageName) else scope[packageName] = rule
            config.copy(scope = scope)
        }
    }

    fun setEnabled(enabled: Boolean) {
        update { config ->
            config.copy(version = HideAppsConfig.CURRENT_VERSION, enabled = enabled)
        }
    }

    fun setHidden(packageName: String, hidden: Boolean) {
        update { config ->
            val packages = config.hiddenPackages.toMutableSet()
            if (hidden) packages.add(packageName) else packages.remove(packageName)
            config.copy(
                version = HideAppsConfig.CURRENT_VERSION,
                hiddenPackages = packages,
                scope = emptyMap(),
            )
        }
    }

    fun setHiddenAll(packageNames: Set<String>) {
        update { config ->
            config.copy(
                version = HideAppsConfig.CURRENT_VERSION,
                enabled = true,
                hiddenPackages = config.hiddenPackages + packageNames,
                scope = emptyMap(),
            )
        }
    }

    fun setViewerAllowed(packageName: String, allowed: Boolean) {
        update { config ->
            val packages = config.viewerWhitelist.toMutableSet()
            if (allowed) packages.add(packageName) else packages.remove(packageName)
            config.copy(
                version = HideAppsConfig.CURRENT_VERSION,
                viewerWhitelist = packages,
                scope = emptyMap(),
            )
        }
    }

    private fun defaultConfig() =
        HideAppsConfig(enabled = true, hiddenPackages = setOf(context.packageName))

    private fun load(): HideAppsConfig = runCatching {
        if (file.isFile) {
            HideAppsConfig.parse(file.readText())
        } else {
            defaultConfig()
        }
    }.getOrElse { defaultConfig() }

    private fun update(transform: (HideAppsConfig) -> HideAppsConfig) {
        synchronized(storageLock) {
            file.writeText(transform(load()).toJson())
        }
    }
}
