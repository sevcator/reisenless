package com.topjohnwu.magisk.hideapps

import android.content.Context
import java.io.File

class HideAppsRepository(private val context: Context) {
    private val file = File(context.filesDir, "hide_apps.json")

    @Volatile
    var config: HideAppsConfig = load()
        private set

    @Synchronized
    fun setRule(packageName: String, rule: HideAppsRule?) {
        val scope = config.scope.toMutableMap()
        if (rule == null) scope.remove(packageName) else scope[packageName] = rule
        save(config.copy(scope = scope))
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        save(config.copy(
            version = HideAppsConfig.CURRENT_VERSION,
            enabled = enabled,
            scope = emptyMap(),
        ))
    }

    @Synchronized
    fun setHidden(packageName: String, hidden: Boolean) {
        val packages = config.hiddenPackages.toMutableSet()
        if (hidden) packages.add(packageName) else packages.remove(packageName)
        save(config.copy(
            version = HideAppsConfig.CURRENT_VERSION,
            hiddenPackages = packages,
            scope = emptyMap(),
        ))
    }

    @Synchronized
    fun setHiddenAll(packageNames: Set<String>) {
        save(config.copy(
            version = HideAppsConfig.CURRENT_VERSION,
            enabled = true,
            hiddenPackages = config.hiddenPackages + packageNames,
            scope = emptyMap(),
        ))
    }

    @Synchronized
    fun setViewerAllowed(packageName: String, allowed: Boolean) {
        val packages = config.viewerWhitelist.toMutableSet()
        if (allowed) packages.add(packageName) else packages.remove(packageName)
        save(config.copy(
            version = HideAppsConfig.CURRENT_VERSION,
            viewerWhitelist = packages,
            scope = emptyMap(),
        ))
    }

    private fun defaultConfig() =
        HideAppsConfig(enabled = true, hiddenPackages = setOf(context.packageName))

    @Synchronized
    private fun load(): HideAppsConfig = runCatching {
        if (file.isFile) {
            HideAppsConfig.parse(file.readText())
        } else {
            defaultConfig()
        }
    }.getOrElse { defaultConfig() }

    private fun save(updated: HideAppsConfig) {
        file.writeText(updated.toJson())
        config = updated
    }
}
