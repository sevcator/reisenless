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
        val action = if (enabled) {
            "mkdir -p '$state' && : > '$state/background-updates'"
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

    @Volatile
    private var cachedAssetId: String? = null

    fun syncRuntime(context: Context, shell: Shell) {
        runCatching {
            val installedId = com.topjohnwu.superuser.ShellUtils.fastCmd("cat '$runtime/payload.id' 2>/dev/null").trim()

            var targetId = cachedAssetId
            if (targetId == null) {
                runCatching {
                    java.util.zip.ZipInputStream(context.assets.open(Const.UDONGE_ARCHIVE)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (entry.name == "payload.id") {
                                targetId = zis.bufferedReader().readLine()?.trim()
                                break
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
                if (targetId.isNullOrEmpty()) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    context.assets.open(Const.UDONGE_ARCHIVE).use { stream ->
                        while (stream.read(buffer).also { bytesRead = it } > 0) {
                            digest.update(buffer, 0, bytesRead)
                        }
                    }
                    targetId = digest.digest().joinToString("") { "%02x".format(it) }
                }
                cachedAssetId = targetId
            }

            if (installedId.isEmpty() || installedId != targetId) {
                val tempDir = java.io.File(context.cacheDir, "udonge_extract_${System.currentTimeMillis()}")
                tempDir.mkdirs()
                java.util.zip.ZipInputStream(context.assets.open(Const.UDONGE_ARCHIVE)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = java.io.File(tempDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { output ->
                                zis.copyTo(output)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                val tempExtractPath = tempDir.absolutePath
                val targetArchive = "${Const.DATABIN}/${Const.UDONGE_ARCHIVE}"
                val cmd = "mkdir -p '${Const.DATABIN}' '$root/runtime.new' '$state' && " +
                    "cp -af '$tempExtractPath/.' '$root/runtime.new/' && " +
                    "if [ -f '$root/runtime.new/service.sh' ] && [ -f '$root/runtime.new/hideapps.dex' ]; then " +
                    "printf '%s\\n' '$targetId' > '$root/runtime.new/payload.id' && " +
                    "rm -rf '$root/runtime.old' && " +
                    "[ ! -d '$root/runtime' ] || mv '$root/runtime' '$root/runtime.old' && " +
                    "mv '$root/runtime.new' '$root/runtime' && " +
                    "chmod -R 700 '$root' && " +
                    "chcon -R u:object_r:system_file:s0 '$root/runtime' 2>/dev/null; " +
                    "chcon u:object_r:udonge_lib_file:s0 '$root/runtime/tee/'*'/libTEESimulator.so' 2>/dev/null; " +
                    "fi; " +
                    "rm -rf '$tempExtractPath'"
                shell.newJob().add(cmd).exec()
                tempDir.deleteRecursively()

                val tempArchive = java.io.File(context.cacheDir, "udonge.tmp")
                context.assets.open(Const.UDONGE_ARCHIVE).use { input ->
                    tempArchive.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val tempArchiveStr = tempArchive.absolutePath
                shell.newJob().add("cp -f '$tempArchiveStr' '$targetArchive' && rm -f '$tempArchiveStr'").exec()
                tempArchive.delete()
            }
        }
    }

    fun syncState(context: Context, shell: Shell) {
        syncRuntime(context, shell)
        val enabled = Config.udongeEnabled
        val job = shell.newJob()
        if (enabled) {
            job.add("mkdir -p '$state' && : > '$state/enabled' && rm -f '$state/disabled'")
            val keyboxCmd = buildKeyboxUrlsCommand(Config.udongeKeyboxUrls)
            if (keyboxCmd.isNotEmpty()) job.add(keyboxCmd)
            val bgUpdatesCmd = if (Config.udongeBackgroundUpdates) {
                "mkdir -p '$state' && : > '$state/background-updates'"
            } else {
                "rm -f '$state/background-updates' '$state/.keybox-refresh'"
            }
            job.add(bgUpdatesCmd)
            if (Config.udongeRomHidingEnabled) {
                val romCmd = buildRomKeywordsCommand(Config.udongeRomKeywords)
                if (romCmd.isNotEmpty()) job.add(romCmd)
            }
        } else {
            job.add("mkdir -p '$state' && rm -f '$state/enabled' && : > '$state/disabled' && rm -f '$state/background-updates' '$state/.keybox-refresh'")
        }
        job.exec()
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

    private fun buildKeyboxUrlsCommand(value: String): String {
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
        return "mkdir -p '$state' && printf '%s' '$encoded' | " +
            "base64 -d > '$state/keybox_urls.conf'$refresh"
    }

    private fun writeKeyboxUrls(value: String, execute: (String) -> Boolean): Boolean {
        val command = buildKeyboxUrlsCommand(value)
        return execute(command)
    }

    fun scheduleBackgroundUpdates(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancel(Const.ID.BACKGROUND_UPDATE_JOB_ID)
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

    private fun buildRomKeywordsCommand(value: String): String {
        val normalized = value.lineSequence()
            .map(String::trim)
            .filter { it.length >= 3 && it.none { c -> c.isWhitespace() } }
            .distinct()
            .take(32)
            .joinToString("\n")
        val encoded = Base64.encodeToString(normalized.toByteArray(), Base64.NO_WRAP)
        return "mkdir -p '$state' && printf '%s' '$encoded' | " +
            "base64 -d > '$state/rom_keywords.conf'"
    }

    private fun setRomKeywords(value: String, execute: (String) -> Boolean): Boolean {
        val command = buildRomKeywordsCommand(value)
        val success = execute(command)
        if (success) {
            val normalized = value.lineSequence()
                .map(String::trim)
                .filter { it.length >= 3 && it.none { c -> c.isWhitespace() } }
                .distinct()
                .take(32)
                .joinToString("\n")
            Config.udongeRomKeywords = normalized
        }
        return success
    }

}
