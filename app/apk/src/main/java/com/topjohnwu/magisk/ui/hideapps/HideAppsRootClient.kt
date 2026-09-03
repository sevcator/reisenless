package com.topjohnwu.magisk.ui.hideapps

import android.util.Base64
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Udonge
import com.topjohnwu.magisk.hideapps.HideAppsConfig
import com.topjohnwu.magisk.hideapps.HideAppsConstants
import com.topjohnwu.magisk.hideapps.HideAppsRepository
import com.topjohnwu.magisk.hideapps.HideAppsStatus
import com.topjohnwu.superuser.Shell

object HideAppsRootClient {
    private val root = "${Const.SECURE_DIR}/${Const.UDONGE_DIR}"
    private val state = "$root/state"
    private val runtime = "$root/runtime"

    fun sync(
        config: HideAppsConfig,
        systemPackages: Set<String>,
        restartPackage: String? = null,
    ): Boolean {
        val text = config.toRuntimeConfig(AppContext.packageName, systemPackages)
        val encoded = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        val temp = "$state/.${HideAppsConstants.CONFIG_FILE}.tmp"
        val target = "$state/${HideAppsConstants.CONFIG_FILE}"
        val result = Shell.cmd(
            "mkdir -p '$state' && printf '%s' '$encoded' | base64 -d > '$temp' && " +
                "chmod 600 '$temp' && mv -f '$temp' '$target'"
        ).exec()
        if (!result.isSuccess) return false

        restartPackage?.takeIf(::isPackageName)?.let { packageName ->
            Shell.cmd("am force-stop --user current '$packageName'").exec()
        }
        return true
    }

    /**
     * Publish the private app configuration on every manager start. The default
     * configuration hides the live (possibly randomized) manager package from
     * every non-exempt app, so protection does not depend on opening Settings.
     */
    fun syncCurrentConfig(): Boolean {
        return sync(
            HideAppsRepository(AppContext).config,
            packageList(systemOnly = true),
        )
    }

    fun status(): HideAppsStatus {
        val active = Shell.cmd(
            "test -f '$runtime/hideapps.dex' && " +
                "grep -q -e '^R' -e '^G' '$state/${HideAppsConstants.CONFIG_FILE}'"
        ).exec().isSuccess
        val count = Shell.cmd(
            "grep -c -e '^R' -e '^G' " +
                "'$state/${HideAppsConstants.CONFIG_FILE}' 2>/dev/null"
        ).exec().out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        return HideAppsStatus(active, HideAppsConstants.RUNTIME_VERSION, count)
    }

    fun syncRomKeywordsHideApps(keywords: String): Boolean {
        val enabled = keywords.isNotBlank()
        val matches = (if (enabled) keywords else Udonge.DEFAULT_ROM_KEYWORDS).lineSequence()
            .map(String::trim)
            .filter { it.length >= 3 && it.none(Char::isWhitespace) }
            .toList()

        fun packageList(systemOnly: Boolean = false): Set<String> {
            val option = if (systemOnly) " -s" else ""
            return Shell.cmd("cmd package list packages$option").exec().out
                .asSequence()
                .map { it.removePrefix("package:").trim() }
                .filter(::isPackageName)
                .toSet()
        }

        val installedPackages = packageList()
        val romPackages = installedPackages.filterTo(mutableSetOf()) { packageName ->
            matches.any { keyword -> packageName.contains(keyword, ignoreCase = true) }
        }
        if (romPackages.isEmpty()) return true

        val repository = HideAppsRepository(AppContext)
        if (enabled) {
            repository.setHiddenAll(romPackages)
        } else {
            romPackages.forEach { repository.setHidden(it, false) }
        }
        return sync(repository.config, packageList(systemOnly = true))
    }

    private fun isPackageName(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))

    private fun packageList(systemOnly: Boolean = false): Set<String> {
        val option = if (systemOnly) " -s" else ""
        return Shell.cmd("cmd package list packages$option").exec().out
            .asSequence()
            .map { it.removePrefix("package:").trim() }
            .filter(::isPackageName)
            .toSet()
    }
}
