package com.topjohnwu.magisk.core

import android.util.Base64
import com.topjohnwu.superuser.Shell

object Udonge {
    private val root = "${Const.SECURE_DIR}/${Const.UDONGE_DIR}"
    private val state = "$root/state"
    private val runtime = "$root/runtime"

    fun setEnabled(enabled: Boolean): Boolean {
        val action = if (enabled) {
            "rm -f '$state/disabled' '$state/unloaded'"
        } else {
            "mkdir -p '$state' && : > '$state/disabled'"
        }
        val success = Shell.cmd(action).exec().isSuccess
        if (success) Config.udongeEnabled = enabled
        return success
    }

    fun setBackgroundUpdates(enabled: Boolean): Boolean {
        val keyboxUpdates = enabled && Config.udongeBackgroundKeyboxes
        val action = if (keyboxUpdates) {
            "mkdir -p '$state' && : > '$state/background-updates' && " +
                ": > '$state/.keybox-refresh' && " +
                "('$runtime/service.sh' </dev/null >/dev/null 2>&1 &)"
        } else {
            "rm -f '$state/background-updates' '$state/.keybox-refresh'"
        }
        val success = Shell.cmd(action).exec().isSuccess
        if (success) Config.udongeBackgroundUpdates = enabled
        return success
    }

    fun setBackgroundUpdateTargets(modules: Boolean, keyboxes: Boolean): Boolean {
        Config.udongeBackgroundModules = modules
        Config.udongeBackgroundKeyboxes = keyboxes
        return setBackgroundUpdates(Config.udongeBackgroundUpdates)
    }

    fun syncBackgroundUpdates(shell: Shell): Boolean {
        val action = if (
            Config.udongeBackgroundUpdates && Config.udongeBackgroundKeyboxes
        ) {
            "mkdir -p '$state' && : > '$state/background-updates'"
        } else {
            "rm -f '$state/background-updates' '$state/.keybox-refresh'"
        }
        return shell.newJob().add(action).exec().isSuccess
    }

    fun setKeyboxUrls(value: String): Boolean {
        val normalized = value.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("https://") && it.length <= 2048 }
            .distinct()
            .take(16)
            .joinToString("\n")
            .ifBlank { Config.DEFAULT_UDONGE_KEYBOX_URLS }
        val encoded = Base64.encodeToString(normalized.toByteArray(), Base64.NO_WRAP)
        val refresh = if (
            Config.udongeBackgroundUpdates && Config.udongeBackgroundKeyboxes
        ) {
            " && : > '$state/.keybox-refresh'"
        } else {
            " && rm -f '$state/.keybox-refresh'"
        }
        val command = "mkdir -p '$state' && printf '%s' '$encoded' | " +
            "base64 -d > '$state/keybox_urls.conf'$refresh"
        val success = Shell.cmd(command).exec().isSuccess
        if (success) Config.udongeKeyboxUrls = normalized
        return success
    }

    fun refreshKeyboxes(): Boolean {
        if (!Config.udongeBackgroundUpdates || !Config.udongeBackgroundKeyboxes) {
            return Shell.cmd("rm -f '$state/.keybox-refresh'").exec().isSuccess
        }
        return Shell.cmd(
            "mkdir -p '$state' && : > '$state/.keybox-refresh' && " +
                "('$runtime/service.sh' </dev/null >/dev/null 2>&1 &)"
        ).exec().isSuccess
    }

    fun setRomKeywords(value: String): Boolean = setRomKeywords(value) { command ->
        Shell.cmd(command).exec().isSuccess
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
