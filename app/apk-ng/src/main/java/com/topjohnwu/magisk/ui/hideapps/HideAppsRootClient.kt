package com.topjohnwu.magisk.ui.hideapps

import android.util.Base64
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
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
        if (config.enabled || config.scope.isNotEmpty()) {
            Udonge.setEnabled(true)
            if (!Config.zygisk) Config.zygisk = true
        }
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

    fun status(): HideAppsStatus {
        val active = Info.isZygiskEnabled && Shell.cmd(
            "test -f '$runtime/hideapps.dex' && test ! -f '$state/disabled'"
        ).exec().isSuccess
        val count = Shell.cmd(
            "grep -c -e '^R' -e '^G' " +
                "'$state/${HideAppsConstants.CONFIG_FILE}' 2>/dev/null"
        ).exec().out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        return HideAppsStatus(active, HideAppsConstants.RUNTIME_VERSION, count)
    }

    fun syncRomKeywordsHideApps(keywords: String): Boolean {
        val matches = keywords.lineSequence()
            .map(String::trim)
            .filter { it.length >= 3 && it.none(Char::isWhitespace) }
            .toList()
        if (matches.isEmpty()) return true

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
        repository.setHiddenAll(romPackages)
        return sync(repository.config, packageList(systemOnly = true))
    }

    private fun isPackageName(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))
}
