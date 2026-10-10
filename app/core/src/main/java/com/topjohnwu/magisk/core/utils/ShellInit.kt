package com.topjohnwu.magisk.core.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.Udonge
import com.topjohnwu.superuser.Shell
import java.io.File
import java.util.zip.ZipFile

class ShellInit : Shell.Initializer() {
    override fun onInit(context: Context, shell: Shell): Boolean {
        if (shell.isRoot) {
            Info.isRooted = true
            RootUtils.bindTask?.let { shell.execTask(it) }
            RootUtils.bindTask = null
        }
        shell.newJob().apply {
            add("export ASH_STANDALONE=1")

            val localBB = File(
                context.applicationInfo.nativeLibraryDir,
                "lib${com.topjohnwu.magisk.core.BuildConfig.BUSYBOX_LIB_NAME}.so",
            ).absolutePath

            if (shell.isRoot) {

                add("export ROOT_TMP=\$(${Const.MAIN_BIN} --path)")

                Info.noDataExec = !shell.newJob()
                    .add("(exec -a busybox '$localBB' true)").exec().isSuccess
            }

            if (Info.noDataExec) {

                add(
                    "if [ -x \$ROOT_TMP/${Const.INTERNAL_DIR}/${Const.BUSYBOX_NAME}/${Const.BUSYBOX_NAME} ]; then",
                    "  cp -af $localBB \$ROOT_TMP/${Const.INTERNAL_DIR}/${Const.BUSYBOX_NAME}/${Const.BUSYBOX_NAME}",
                    "  exec -a busybox \$ROOT_TMP/${Const.INTERNAL_DIR}/${Const.BUSYBOX_NAME}/${Const.BUSYBOX_NAME} sh",
                    "else",
                    "  cp -af $localBB /dev/busybox",
                    "  exec -a busybox /dev/busybox sh",
                    "fi"
                )
            } else {

                add("exec -a busybox '$localBB' sh")
            }

            add(context.assets.open("app_functions.sh"))
            if (shell.isRoot) {
                add(context.assets.open("util_functions.sh"))
            }
        }.exec()

        Info.init(shell)

        if (shell.isRoot) {
            Udonge.syncState(context, shell)
            cleanupObsoleteManagers(context, shell)
        }

        return true
    }

    companion object {
        @Volatile
        private var cleanedUpObsoleteManagers = false
    }

    private fun cleanupObsoleteManagers(context: Context, shell: Shell) {
        if (cleanedUpObsoleteManagers || !Info.env.isCurrentBuild) return
        val completed = shell.newJob().add(
            "[ \"\$(sed -n '1p' '${Const.SECURE_DIR}/.upgrade-cleanup.complete')\" = '${BuildConfig.APP_VERSION_NAME}' ] && " +
                "[ \"\$(sed -n '2p' '${Const.SECURE_DIR}/.upgrade-cleanup.complete')\" = \"\$(cat /proc/sys/kernel/random/boot_id)\" ]"
        ).exec().isSuccess
        if (!completed) return
        runCatching {
            val pm = context.packageManager
            val currentPkg = context.packageName
            val currentInfo = runCatching { pm.getPackageInfo(currentPkg, 0) }.getOrNull() ?: return
            val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                currentInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                currentInfo.versionCode.toLong()
            }
            val currentInstallTime = currentInfo.firstInstallTime
            var success = true

            val installed = pm.getInstalledApplications(0)
            for (app in installed) {
                val pkg = app.packageName
                if (pkg != currentPkg && app.uid != context.applicationInfo.uid &&
                    pkg.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) &&
                    isMagiskManager(app)) {
                    val otherInfo = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: continue
                    val otherVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        otherInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        otherInfo.versionCode.toLong()
                    }
                    val otherInstallTime = otherInfo.firstInstallTime

                    val isObsolete = otherVersionCode < currentVersionCode ||
                        (otherVersionCode == currentVersionCode && otherInstallTime < currentInstallTime)

                    if (isObsolete) {
                        val result = shell.newJob().add("pm uninstall '$pkg'").exec()
                        success = result.isSuccess && result.out.any { it.trim() == "Success" } && success
                    }
                }
            }
            cleanedUpObsoleteManagers = success
        }
    }

    private fun isMagiskManager(app: ApplicationInfo): Boolean = runCatching {
        ZipFile(app.sourceDir).use { archive ->
            val util = archive.getEntry("assets/util_functions.sh") ?: return false
            val patch = archive.getEntry("assets/boot_patch.sh") ?: return false
            if (util.size !in 1..262144 || patch.size !in 1..262144) return false
            val functions = archive.getInputStream(util).bufferedReader().use { it.readText() }
            val patcher = archive.getInputStream(patch).bufferedReader().use { it.readText() }
            functions.lineSequence().any { it.matches(Regex("MAGISK_VER_CODE=[0-9]+")) } &&
                patcher.contains("ramdisk.cpio")
        }
    }.getOrDefault(false)

}
