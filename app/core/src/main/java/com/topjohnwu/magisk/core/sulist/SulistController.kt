package com.topjohnwu.magisk.core.sulist

import android.content.Context
import android.content.pm.ApplicationInfo
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.model.su.SuPolicy
import com.topjohnwu.superuser.Shell

data class SulistEntry(
    val packageName: String,
    val processName: String,
)

object SulistController {

    private const val MIGRATION_KEY = "sulist_policy_import_v1"

    private data class CommandResult(
        val code: Int,
        val output: List<String>,
    )

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun command(action: String, vararg values: String): String = buildString {
        append(Const.MAIN_BIN)
        append(" --sulist ")
        append(action)
        values.forEach { value ->
            append(' ')
            append(shellQuote(value))
        }
    }

    private fun execute(command: String, shell: Shell? = null): CommandResult {
        val result = if (shell == null) {
            Shell.cmd(command).exec()
        } else {
            shell.newJob().add(command).exec()
        }
        return CommandResult(result.code, result.out)
    }

    private fun executeSulist(
        action: String,
        vararg values: String,
        shell: Shell? = null,
    ): CommandResult = execute(command(action, *values), shell)

    /** Returns null when the daemon could not report an authoritative state. */
    fun status(shell: Shell? = null): Boolean? = when (
        executeSulist("status", shell = shell).code
    ) {
        0 -> true
        1 -> false
        else -> null
    }

    /** Returns the authoritative post-command state, or null on transport failure. */
    fun setEnabled(enabled: Boolean, shell: Shell? = null): Boolean? {
        val action = if (enabled) "enable" else "disable"
        val result = executeSulist(action, shell = shell)
        val actual = status(shell) ?: return null
        if (result.code == 0 && actual == enabled) {
            Config.sulist = actual
        }
        return actual
    }

    fun list(shell: Shell? = null): Set<SulistEntry>? {
        val result = executeSulist("ls", shell = shell)
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

    fun add(packageName: String, processName: String? = null, shell: Shell? = null): Boolean {
        val values = processName?.let { arrayOf(packageName, it) } ?: arrayOf(packageName)
        return executeSulist("add", *values, shell = shell).code == 0
    }

    fun remove(
        packageName: String,
        processName: String? = null,
        shell: Shell? = null,
    ): Boolean {
        val values = processName?.let { arrayOf(packageName, it) } ?: arrayOf(packageName)
        return executeSulist("rm", *values, shell = shell).code == 0
    }

    /**
     * One-time upgrade migration. A fresh installation only has the manager's
     * policy, which is deliberately skipped and therefore starts with an empty
     * ordinary-app allowlist.
     */
    fun importExistingRootGrants(context: Context, shell: Shell): Boolean {
        val markerQuery = "SELECT value FROM strings WHERE key='$MIGRATION_KEY'"
        val marker = execute("${Const.MAIN_BIN} --sqlite ${shellQuote(markerQuery)}", shell)
        if (marker.code != 0) return false
        if (marker.output.any { it == "value=1" }) return true

        val current = list(shell) ?: return false
        val currentPackages = current.mapTo(hashSetOf(), SulistEntry::packageName)
        val grantsQuery = "SELECT uid FROM policies WHERE policy IN " +
            "(${SuPolicy.ALLOW},${SuPolicy.RESTRICT}) " +
            "AND (until=0 OR until>strftime('%s','now'))"
        val grants = execute("${Const.MAIN_BIN} --sqlite ${shellQuote(grantsQuery)}", shell)
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
            if (!add(packageName, shell = shell)) {
                // A concurrent insert is harmless; verify it before retrying
                // the whole migration on the next manager start.
                val refreshed = list(shell) ?: return false
                if (refreshed.none { it.packageName == packageName }) return false
            }
            currentPackages += packageName
        }

        val markComplete = "REPLACE INTO strings (key,value) " +
            "VALUES ('$MIGRATION_KEY','1')"
        return execute("${Const.MAIN_BIN} --sqlite ${shellQuote(markComplete)}", shell).code == 0
    }
}
