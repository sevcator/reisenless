package com.topjohnwu.magisk.core.utils

import android.content.Context
import com.topjohnwu.magisk.StubApk
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.Udonge
import com.topjohnwu.magisk.core.isRunningAsStub
import com.topjohnwu.magisk.core.ktx.cachedFile
import com.topjohnwu.magisk.core.ktx.deviceProtectedContext
import com.topjohnwu.magisk.core.ktx.writeTo
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.jar.JarFile

class ShellInit : Shell.Initializer() {
    override fun onInit(context: Context, shell: Shell): Boolean {
        if (shell.isRoot) {
            Info.isRooted = true
            RootUtils.bindTask?.let { shell.execTask(it) }
            RootUtils.bindTask = null
        }
        shell.newJob().apply {
            add("export ASH_STANDALONE=1")

            val localBB: File
            if (isRunningAsStub) {
                if (!shell.isRoot)
                    return true
                localBB = context.deviceProtectedContext.cachedFile("busybox")
                localBB.parentFile?.mkdirs()
                localBB.delete()
                runBlocking {
                    JarFile(StubApk.current(context)).use { jar ->
                        val bb = jar.getJarEntry("lib/${Const.CPU_ABI}/libbusybox.so")
                            ?: error("missing packaged busybox")
                        jar.getInputStream(bb).writeTo(
                            localBB,
                            dispatcher = Dispatchers.Unconfined,
                        )
                    }
                }
                localBB.setExecutable(true)
            } else {
                // Android provides the exact extracted ABI directory here.
                // Some Android 15 policies allow execution but deny stat(), so
                // probing File.isFile incorrectly selects a broken fallback.
                localBB = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
            }

            if (shell.isRoot) {
                add("export MAGISKTMP=\$(${Const.MAIN_BIN} --path)")
                // Test if we can properly execute stuff in /data
                Info.noDataExec = !shell.newJob()
                    .add("$localBB sh -c '$localBB true'").exec().isSuccess
            }

            if (Info.noDataExec) {
                // Copy it out of /data to workaround Samsung bullshit
                add(
                    "if [ -x \$MAGISKTMP/${Const.INTERNAL_DIR}/${Const.BUSYBOX_NAME}/${Const.BUSYBOX_NAME} ]; then",
                    "  cp -af $localBB \$MAGISKTMP/${Const.INTERNAL_DIR}/${Const.BUSYBOX_NAME}/${Const.BUSYBOX_NAME}",
                    "  exec \$MAGISKTMP/${Const.INTERNAL_DIR}/${Const.BUSYBOX_NAME}/${Const.BUSYBOX_NAME} sh",
                    "else",
                    "  cp -af $localBB /dev/busybox",
                    "  exec /dev/busybox sh",
                    "fi"
                )
            } else {
                // Directly execute the file
                add("exec $localBB sh")
            }

            add(context.assets.open("app_functions.sh"))
            if (shell.isRoot) {
                add(context.assets.open("util_functions.sh"))
            }
        }.exec()

        Info.init(shell)

        // Cache Allow policy in DB so future launches skip the slow /data/app/ scan.
        // This runs from the root shell (UID 0) so SQLITE_CMD is permitted.
        if (shell.isRoot) {
            val myUid = android.os.Process.myUid()
            shell.newJob().add(
                "\$MAGISKTMP/${Const.MAIN_BIN} --sqlite " +
                "'INSERT OR IGNORE INTO policies (uid, policy, until, logging, notification) " +
                "VALUES ($myUid, 2, 0, 0, 0)'"
            ).exec()
            Udonge.syncBackgroundUpdates(shell)
            detectAndSaveRomKeywords(shell)
        }

        return true
    }

    private fun detectAndSaveRomKeywords(shell: Shell) {
        val directProperties = linkedMapOf(
            "ro.lineage.version" to listOf("lineage", "lineageos"),
            "ro.lineage.build.version" to listOf("lineage", "lineageos"),
            "ro.proton.version" to listOf("proton", "protonaosp"),
            "ro.protonaosp.version" to listOf("proton", "protonaosp"),
            "ro.calyxos.version" to listOf("calyx", "calyxos"),
            "ro.grapheneos.version" to listOf("graphene", "grapheneos"),
            "ro.pe.version" to listOf("pixelexperience"),
            "ro.crdroid.version" to listOf("crdroid"),
            "ro.evolutionx.version" to listOf("evolution", "evolutionx"),
            "ro.spark.version" to listOf("spark", "sparkos"),
            "ro.rising.version" to listOf("rising", "risingos"),
            "ro.bliss.version" to listOf("bliss", "blissroms"),
        )
        val identityProperties = listOf(
            "ro.modversion",
            "ro.build.display.id",
            "ro.build.flavor",
            "ro.build.description",
            "ro.build.fingerprint",
            "ro.product.system.name",
            "ro.product.vendor.name",
        )
        val allProperties = (directProperties.keys + identityProperties).distinct()
        val script = allProperties.joinToString("\n") { property ->
            "printf '__ROM_PROP__${property}=%s\\n' \"\$(getprop '$property')\""
        }
        val values = shell.newJob().add(script).exec().out.mapNotNull { line ->
            val tagged = line.removePrefix("__ROM_PROP__")
            if (tagged == line) return@mapNotNull null
            val separator = tagged.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            tagged.substring(0, separator) to tagged.substring(separator + 1)
        }.toMap()

        val detected = linkedSetOf<String>()
        directProperties.forEach { (property, keywords) ->
            if (!values[property].isNullOrBlank()) detected.addAll(keywords)
        }
        val identity = identityProperties.mapNotNull(values::get).joinToString(" ").lowercase()
        val identityKeywords = linkedMapOf(
            "lineage" to listOf("lineage", "lineageos"),
            "protonaosp" to listOf("proton", "protonaosp"),
            "calyx" to listOf("calyx", "calyxos"),
            "graphene" to listOf("graphene", "grapheneos"),
            "pixelexperience" to listOf("pixelexperience"),
            "crdroid" to listOf("crdroid"),
            "evolutionx" to listOf("evolution", "evolutionx"),
            "sparkos" to listOf("spark", "sparkos"),
            "risingos" to listOf("rising", "risingos"),
            "bliss" to listOf("bliss", "blissroms"),
        )
        identityKeywords.forEach { (signal, keywords) ->
            if (identity.contains(signal)) detected.addAll(keywords)
        }
        if (detected.isEmpty()) return
        val existing = Config.udongeRomKeywords
        val combined = (existing.lineSequence().filter { it.isNotBlank() } + detected.asSequence())
            .distinct().joinToString("\n")
        if (combined != existing) Udonge.setRomKeywords(combined, shell)
    }
}
