package com.topjohnwu.magisk.ui.hideapps

import android.util.Base64
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
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
    private val globalLoaderMarker = "$state/hideapps-global-loader-v2"

    fun sync(
        config: HideAppsConfig,
        installedPackages: Set<String>,
        systemPackages: Set<String>,
    ): Boolean {
        if (config.enabled) {
            Udonge.setEnabled(true)
            if (!Config.zygisk) Config.zygisk = true
        }
        val hasGlobalLoader = Shell.cmd(
            "test \"\$(cat '$globalLoaderMarker' 2>/dev/null)\" = " +
                "\"\$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\""
        ).exec().isSuccess
        val text = config.toRuntimeConfig(
            AppContext.packageName,
            systemPackages,
            installedPackages,
            includeCompatibilityMarkers = !hasGlobalLoader,
        )
        val encoded = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        val temp = "$state/.${HideAppsConstants.CONFIG_FILE}.tmp"
        val target = "$state/${HideAppsConstants.CONFIG_FILE}"
        val result = Shell.cmd(
            "mkdir -p '$state' && printf '%s' '$encoded' | base64 -d > '$temp' && " +
                "chmod 600 '$temp' && mv -f '$temp' '$target'"
        ).exec()
        if (!result.isSuccess) return false

        return true
    }

    @SuppressLint("InlinedApi", "QueryPermissionsNeeded")
    fun syncCurrentConfig(): Boolean {
        val apps = AppContext.packageManager
            .getInstalledApplications(MATCH_UNINSTALLED_PACKAGES)
        return sync(
            HideAppsRepository(AppContext).config,
            apps.mapTo(mutableSetOf(), ApplicationInfo::packageName),
            apps.asSequence()
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
                .mapTo(mutableSetOf(), ApplicationInfo::packageName),
        )
    }

    @SuppressLint("InlinedApi", "QueryPermissionsNeeded")
    fun syncRomKeywordsHideApps(keywords: String): Boolean {
        val kwList = keywords.lineSequence()
            .map(String::trim)
            .filter { it.length >= 3 && it.none(Char::isWhitespace) }
            .toList()
        if (kwList.isEmpty()) return true
        val installed = AppContext.packageManager
            .getInstalledApplications(MATCH_UNINSTALLED_PACKAGES)
        val romPkgs = installed
            .filter { info -> kwList.any { kw -> info.packageName.contains(kw, ignoreCase = true) } }
            .mapTo(mutableSetOf(), ApplicationInfo::packageName)
        if (romPkgs.isEmpty()) return true
        HideAppsRepository(AppContext).setHiddenAll(romPkgs)
        return syncCurrentConfig()
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
}
