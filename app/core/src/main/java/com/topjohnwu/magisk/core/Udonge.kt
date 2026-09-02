package com.topjohnwu.magisk.core

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Base64
import com.topjohnwu.magisk.core.di.ServiceLocator
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Udonge {

    private const val UPDATE_INTERVAL_MS = 60L * 60L * 1000L
    private const val UPDATE_FLEX_MS = 15L * 60L * 1000L
    private const val MAX_KEYBOX_BYTES = 256 * 1024

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
            "mkdir -p '$state' && rm -f '$state/disabled' '$state/unloaded' && " +
                ": > '$pendingReboot'"
        } else {
            "mkdir -p '$state' && : > '$state/disabled' && : > '$pendingReboot'"
        }
        val success = Shell.cmd(action).exec().isSuccess
        if (success) Config.udongeEnabled = enabled
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

    suspend fun runBackgroundUpdates(): Boolean = withContext(Dispatchers.IO) {
        if (!Config.udongeEnabled || !Config.udongeBackgroundUpdates) return@withContext true
        updateKeyboxes()
    }

    private suspend fun updateKeyboxes(): Boolean {
        val sources = Config.udongeKeyboxUrls.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("https://") }
            .distinct()
            .take(16)
        var selected: ByteArray? = null
        var bestCertificateCount = 0
        for (source in sources) {
            val candidate = runCatching {
                ServiceLocator.networkService.fetchFile(source).use { body ->
                    val length = body.contentLength()
                    require(length in -1L..MAX_KEYBOX_BYTES.toLong())
                    body.byteStream().use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_KEYBOX_BYTES)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
            }.getOrNull() ?: continue
            val text = candidate.toString(Charsets.UTF_8)
            if (!text.contains("<AndroidAttestation>") ||
                !text.contains("<NumberOfKeyboxes>") ||
                !text.contains("<Keybox") ||
                !text.contains("-----BEGIN CERTIFICATE-----") ||
                !text.contains("-----END CERTIFICATE-----") ||
                !Regex("-----BEGIN (EC |RSA )?PRIVATE KEY-----").containsMatchIn(text)
            ) continue
            val certificateCount = "-----BEGIN CERTIFICATE-----".toRegex()
                .findAll(text).count()
            if (certificateCount > bestCertificateCount) {
                selected = candidate
                bestCertificateCount = certificateCount
            }
        }
        val bytes = selected ?: return false
        val sourceFile = File(AppContext.cacheDir, "keybox-update.xml")
        return try {
            sourceFile.writeBytes(bytes)
            Shell.cmd(
                "mkdir -p '$state' && " +
                    "cp '${sourceFile.absolutePath}' '$state/.keybox.new' && " +
                    "chmod 600 '$state/.keybox.new' && " +
                    "mv -f '$state/.keybox.new' '$state/keybox.xml' && " +
                    "date +%s > '$state/.keybox-checked' && " +
                    "chmod 600 '$state/.keybox-checked' && " +
                    "rm -f '$state/.keybox-refresh'"
            ).exec().isSuccess
        } finally {
            sourceFile.delete()
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
