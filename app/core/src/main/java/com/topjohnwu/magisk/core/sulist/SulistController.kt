package com.topjohnwu.magisk.core.sulist

import android.content.Context
import android.content.pm.ApplicationInfo
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.model.su.SuPolicy
import com.topjohnwu.magisk.core.utils.ManagerCli

data class SulistEntry(
    val packageName: String,
    val processName: String,
)

object SulistController {

    private const val MIGRATION_KEY = "sulist_policy_import_v1"

    private fun executeSulist(
        action: String,
        vararg values: String,
    ): ManagerCli.Result = ManagerCli.execute("--sulist", action, *values)

    /** Returns null when the daemon could not report an authoritative state. */
    @Synchronized
    fun status(): Boolean? {
        val result = executeSulist("status")
        if (!result.isSuccess || result.output.size != 1) return null
        return when (result.output.single()) {
            "enabled=1" -> true
            "enabled=0" -> false
            else -> null
        }
    }

    /** Returns the authoritative post-command state, or null on transport failure. */
    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean? {
        val action = if (enabled) "enable" else "disable"
        val result = executeSulist(action)
        val actual = status() ?: return null
        if (result.code == 0 && actual == enabled) {
            Config.sulist = actual
        }
        return actual
    }

    @Synchronized
    fun list(): Set<SulistEntry>? {
        val result = executeSulist("ls")
        if (result.code != 0) return null
        val entries = linkedSetOf<SulistEntry>()
        for (line in result.output) {
            val separator = line.indexOf('|')
            if (separator <= 0 || separator == line.lastIndex) return null
            entries += SulistEntry(
                line.substring(0, separator),
                line.substring(separator + 1),
            )
        }
        return entries
    }

    @Synchronized
    fun add(packageName: String, processName: String? = null): Boolean {
        val values = processName?.let { arrayOf(packageName, it) } ?: arrayOf(packageName)
        return executeSulist("add", *values).code == 0
    }

    @Synchronized
    fun remove(
        packageName: String,
        processName: String? = null,
    ): Boolean {
        val values = processName?.let { arrayOf(packageName, it) } ?: arrayOf(packageName)
        return executeSulist("rm", *values).code == 0
    }

    /**
     * One-time upgrade migration. A fresh installation only has the manager's
     * policy, which is deliberately skipped and therefore starts with an empty
     * ordinary-app allowlist.
     */
    @Synchronized
    fun importExistingRootGrants(context: Context): Boolean {
        val markerQuery = "SELECT value FROM strings WHERE key='$MIGRATION_KEY'"
        val marker = ManagerCli.execute("--sqlite", markerQuery)
        if (marker.code != 0) return false
        if (marker.output.any { it == "value=1" }) return true

        val current = list() ?: return false
        val currentPackages = current.mapTo(hashSetOf(), SulistEntry::packageName)
        val grantsQuery = "SELECT uid FROM policies WHERE policy IN " +
            "(${SuPolicy.ALLOW},${SuPolicy.RESTRICT}) " +
            "AND (until=0 OR until>strftime('%s','now'))"
        val grants = ManagerCli.execute("--sqlite", grantsQuery)
        if (grants.code != 0) return false

        val packageManager = context.packageManager
        val packages = grants.output.asSequence()
            .mapNotNull { line -> line.substringAfter("uid=", "").toIntOrNull() }
            .filter { it != android.os.Process.myUid() }
            .flatMap { uid ->
                runCatching { packageManager.getPackagesForUid(uid) }
                    .getOrNull().orEmpty().asSequence()
            }
            .filter { it != context.packageName }
            .filter { packageName ->
                val info = runCatching {
                    packageManager.getApplicationInfo(packageName, 0)
                }.getOrNull() ?: return@filter false
                info.flags and ApplicationInfo.FLAG_SYSTEM == 0
            }
            .distinct()
            .sorted()
            .toList()

        for (packageName in packages) {
            if (packageName in currentPackages) continue
            if (!add(packageName)) {
                // A concurrent insert is harmless; verify it before retrying
                // the whole migration on the next manager start.
                val refreshed = list() ?: return false
                if (refreshed.none { it.packageName == packageName }) return false
            }
            currentPackages += packageName
        }

        val markComplete = "REPLACE INTO strings (key,value) " +
            "VALUES ('$MIGRATION_KEY','1')"
        return ManagerCli.execute("--sqlite", markComplete).code == 0
    }
}
