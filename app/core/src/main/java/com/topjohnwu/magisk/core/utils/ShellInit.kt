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
                val jar = JarFile(StubApk.current(context))
                val bb = jar.getJarEntry("lib/${Const.CPU_ABI}/libbusybox.so")
                localBB = context.deviceProtectedContext.cachedFile("busybox")
                localBB.delete()
                runBlocking {
                    jar.getInputStream(bb).writeTo(localBB, dispatcher = Dispatchers.Unconfined)
                }
                localBB.setExecutable(true)
            } else {
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
            detectAndSaveRomKeywords(shell)
        }

        return true
    }

    private fun detectAndSaveRomKeywords(shell: Shell) {
        val propKeywords = linkedMapOf(
            "ro.lineage.version" to "lineage",
            "ro.proton.version" to "proton",
            "ro.calyxos.version" to "calyx",
            "ro.grapheneos.version" to "grapheneos",
            "ro.pe.version" to "pixelexperience",
            "ro.crdroid.version" to "crdroid",
            "ro.evolutionx.version" to "evo",
            "ro.spark.version" to "spark",
            "ro.rising.version" to "rising",
            "ro.bliss.version" to "bliss",
        )
        val script = propKeywords.keys.joinToString("\n") { "getprop '$it'" }
        val output = shell.newJob().add(script).exec().out
        val detected = propKeywords.values.toList()
            .zip(output)
            .filter { (_, value) -> value.isNotBlank() }
            .map { (keyword, _) -> keyword }
        if (detected.isEmpty()) return
        val existing = Config.udongeRomKeywords
        val combined = (existing.lineSequence().filter { it.isNotBlank() } + detected.asSequence())
            .distinct().joinToString("\n")
        if (combined != existing) Udonge.setRomKeywords(combined)
    }
}
