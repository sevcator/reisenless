package com.topjohnwu.magisk.core

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Base64
import com.topjohnwu.superuser.Shell

object Udonge {

    private const val UPDATE_INTERVAL_MS = 60L * 60L * 1000L
    private const val UPDATE_FLEX_MS = 15L * 60L * 1000L
    private val root = "${Const.SECURE_DIR}/${Const.UDONGE_DIR}"
    private val state = "$root/state"
    private val runtime = "$root/runtime"
    private val pendingReboot = "$state/pending-reboot"

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
        val keyboxUpdates = enabled && Config.udongeEnabled
        val action = if (keyboxUpdates) {
            "mkdir -p '$state' && : > '$state/background-updates' && " +
                ": > '$state/.keybox-refresh' && " +
                "if [ ! -f '$pendingReboot' ] && [ -f '$runtime/service.sh' ]; then " +
                "'$runtime/service.sh' </dev/null >/dev/null 2>&1 & fi"
        } else {
            "rm -f '$state/background-updates' '$state/.keybox-refresh'"
        }
        val success = Shell.cmd(action).exec().isSuccess
        if (success) {
            Config.udongeBackgroundUpdates = enabled
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

    fun scheduleBackgroundUpdates(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val enabled = Config.udongeEnabled && Config.udongeBackgroundUpdates
        if (!enabled) {
            scheduler.cancel(Const.ID.BACKGROUND_UPDATE_JOB_ID)
            return
        }
        val service = ComponentName(context, BackgroundUpdateJobService::class.java)
        val builder = JobInfo.Builder(Const.ID.BACKGROUND_UPDATE_JOB_ID, service)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
        if (Build.VERSION.SDK_INT >= 24) {
            builder.setPeriodic(UPDATE_INTERVAL_MS, UPDATE_FLEX_MS)
        } else {
            builder.setPeriodic(UPDATE_INTERVAL_MS)
        }
        val job = builder.build()
        scheduler.schedule(job)
    }

    fun runBackgroundUpdates(): Boolean {
        if (!Config.udongeEnabled || !Config.udongeBackgroundUpdates) {
            return Shell.cmd("rm -f '$state/.keybox-refresh'").exec().isSuccess
        }
        return Shell.cmd(
            "mkdir -p '$state' && : > '$state/.keybox-refresh' && " +
                "if [ ! -f '$pendingReboot' ] && [ -f '$runtime/service.sh' ]; then " +
                "'$runtime/service.sh' </dev/null >/dev/null 2>&1; fi"
        ).exec().isSuccess
    }

}
