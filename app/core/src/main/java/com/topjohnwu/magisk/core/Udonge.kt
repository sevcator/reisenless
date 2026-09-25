package com.topjohnwu.magisk.core

import android.app.job.JobScheduler
import android.content.Context
import android.util.Base64
import com.topjohnwu.superuser.Shell

object Udonge {

    const val DEFAULT_ROM_KEYWORDS =
        "lineage\n" +
        "crdroid\n" +
        "aospa\n" +
        "paranoid\n" +
        "pixelexperience\n" +
        "evolution\n" +
        "omnirom\n" +
        "protonaosp\n" +
        "havoc\n" +
        "resurrection\n" +
        "cyanogenmod\n" +
        "blissrom\n" +
        "arrowos\n" +
        "pixelos\n" +
        "risingos\n" +
        "derpfest\n" +
        "projectelixir\n" +
        "voltageos\n" +
        "superioros\n" +
        "sparkos\n" +
        "cherishos\n" +
        "ancientos\n" +
        "corvus\n" +
        "calyxos\n" +
        "grapheneos\n" +
        "yaap\n" +
        "aicp\n" +
        "slimrom\n" +
        "carbonrom\n" +
        "liquidremix"
    private val root = "${Const.SECURE_DIR}/${Const.UDONGE_DIR}"
    private val state = "$root/state"
    private val runtime = "$root/runtime"
    private val pendingReboot = "$state/pending-reboot"

    @Volatile
    private var cachedVersion: String = ""

    fun version(): String {
        if (cachedVersion.isNotEmpty()) return cachedVersion
        val ver = runCatching {
            val cmd = "cat '$runtime/version' 2>/dev/null || cat '$state/.version' 2>/dev/null"
            com.topjohnwu.superuser.ShellUtils.fastCmd(cmd).trim()
        }.getOrNull().orEmpty()
        cachedVersion = if (ver.isNotEmpty()) ver else Info.env.versionString.ifEmpty { BuildConfig.APP_VERSION_NAME }
        return cachedVersion
    }

    fun setEnabled(enabled: Boolean): Boolean {
        val action = if (enabled) {
            "mkdir -p '$state' && " +
                "if [ ! -f '$state/enabled' ] || [ -f '$state/disabled' ] || " +
                "[ -f '$state/unloaded' ]; then " +
                "rm -f '$state/disabled' '$state/unloaded' && : > '$state/enabled' && " +
                ": > '$pendingReboot'; " +
                "fi"
        } else {
            "mkdir -p '$state' && rm -f '$state/enabled' && : > '$state/disabled' && " +
                ": > '$pendingReboot' && " +
                "rm -f '$state/background-updates' '$state/.keybox-refresh' && " +
                "if [ -x '$runtime/stop.sh' ]; then " +
                "'$runtime/stop.sh' </dev/null >/dev/null 2>&1; fi"
        }
        val success = Shell.cmd(action).exec().isSuccess
        if (success) {
            Config.udongeEnabled = enabled
            scheduleBackgroundUpdates(AppContext)
        }
        return success
    }

    fun setBackgroundUpdates(enabled: Boolean): Boolean {
        if (enabled) return false
        val action = "rm -f '$state/background-updates' '$state/.keybox-refresh'"
        val success = Shell.cmd(action).exec().isSuccess
        if (success) {
            Config.udongeBackgroundUpdates = false
            scheduleBackgroundUpdates(AppContext)
        }
        return success
    }

    fun syncBackgroundUpdates(shell: Shell): Boolean {
        val action = if (
            Config.udongeEnabled && Config.udongeBackgroundUpdates
        ) {
            "mkdir -p '$state' && : > '$state/background-updates'"
        } else {
            "rm -f '$state/background-updates' '$state/.keybox-refresh'"
        }
        return shell.newJob().add(action).exec().isSuccess
    }

    fun syncState(context: Context, shell: Shell) {
        val enabled = Config.udongeEnabled
        val enabledCommand = if (enabled) {
            "mkdir -p '$state' && : > '$state/enabled' && rm -f '$state/disabled'"
        } else {
            "mkdir -p '$state' && rm -f '$state/enabled' && : > '$state/disabled'"
        }
        shell.newJob().add(enabledCommand).exec()
        if (enabled) syncKeyboxUrls(shell)
        syncBackgroundUpdates(shell)
        if (enabled && Config.udongeRomHidingEnabled) {
            setRomKeywords(Config.udongeRomKeywords, shell)
        } else if (enabled) {
            setRomKeywords("", shell)
        }
    }

    fun setKeyboxUrls(value: String): Boolean {
        val normalized = value.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("https://") && it.length <= 2048 }
            .distinct()
            .take(16)
            .joinToString("\n")
            .ifBlank { Config.DEFAULT_UDONGE_KEYBOX_URLS }
        val success = writeKeyboxUrls(normalized) { command ->
            Shell.cmd(command).exec().isSuccess
        }
        if (success) Config.udongeKeyboxUrls = normalized
        return success
    }

    fun syncKeyboxUrls(shell: Shell): Boolean {
        return writeKeyboxUrls(Config.udongeKeyboxUrls) { command ->
            shell.newJob().add(command).exec().isSuccess
        }
    }

    fun refreshKeyboxes(): Boolean {
        if (!Config.udongeEnabled || !Config.udongeBackgroundUpdates) {
            return Shell.cmd("rm -f '$state/.keybox-refresh'").exec().isSuccess
        }
        return Shell.cmd(
            "mkdir -p '$state' && : > '$state/.keybox-refresh' && " +
                "if [ ! -f '$pendingReboot' ] && [ -f '$runtime/service.sh' ]; then " +
                "'$runtime/service.sh' </dev/null >/dev/null 2>&1 & fi"
        ).exec().isSuccess
    }

    private fun writeKeyboxUrls(value: String, execute: (String) -> Boolean): Boolean {
        val normalized = value.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("https://") && it.length <= 2048 }
            .distinct()
            .take(16)
            .joinToString("\n")
            .ifBlank { Config.DEFAULT_UDONGE_KEYBOX_URLS }
        val encoded = Base64.encodeToString(normalized.toByteArray(), Base64.NO_WRAP)
        val refresh = if (
            Config.udongeEnabled && Config.udongeBackgroundUpdates
        ) {
            " && : > '$state/.keybox-refresh'"
        } else {
            " && rm -f '$state/.keybox-refresh'"
        }
        val command = "mkdir -p '$state' && printf '%s' '$encoded' | " +
            "base64 -d > '$state/keybox_urls.conf'$refresh"
        return execute(command)
    }

    fun scheduleBackgroundUpdates(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        // Eirin is intentionally UI-only for now. Cancel any persisted hourly
        // job left by older builds so it cannot keep waking the app in idle.
        scheduler.cancel(Const.ID.BACKGROUND_UPDATE_JOB_ID)
        if (Config.udongeBackgroundUpdates) {
            Config.udongeBackgroundUpdates = false
        }
    }

    fun setRomKeywords(value: String): Boolean = setRomKeywords(value) { command ->
        Shell.cmd(command).exec().isSuccess
    }

    fun setRomHidingEnabled(enabled: Boolean): Boolean {
        val success = setRomKeywords(if (enabled) DEFAULT_ROM_KEYWORDS else "")
        if (success) Config.udongeRomHidingEnabled = enabled
        return success
    }

    fun setRomKeywords(value: String, shell: Shell): Boolean = setRomKeywords(value) { command ->
        shell.newJob().add(command).exec().isSuccess
    }

    private fun setRomKeywords(value: String, execute: (String) -> Boolean): Boolean {
        val normalized = value.lineSequence()
            .map(String::trim)
            .filter { it.length >= 3 && it.none { c -> c.isWhitespace() } }
            .distinct()
            .take(32)
            .joinToString("\n")
        val encoded = Base64.encodeToString(normalized.toByteArray(), Base64.NO_WRAP)
        val command = "mkdir -p '$state' && printf '%s' '$encoded' | " +
            "base64 -d > '$state/rom_keywords.conf'"
        val success = execute(command)
        if (success) Config.udongeRomKeywords = normalized
        return success
    }

}
