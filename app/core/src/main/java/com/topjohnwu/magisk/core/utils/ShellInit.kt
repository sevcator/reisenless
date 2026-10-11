package com.topjohnwu.magisk.core.utils

import android.content.Context
import android.widget.Toast
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.Udonge
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.superuser.Shell
import java.io.File

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
            cleanupObsoleteInstallations(context, shell)
        }

        return true
    }

    companion object {
        @Volatile
        private var cleanedUpObsoleteManagers = false

        @Volatile
        private var reportedPendingCleanup = false
    }

    private fun cleanupObsoleteInstallations(context: Context, shell: Shell) {
        if (cleanedUpObsoleteManagers || !Info.env.isCurrentBuild ||
            Info.env.versionCode != BuildConfig.APP_VERSION_CODE) return
        val completed = shell.newJob().add(
            "[ \"\$(sed -n '1p' '${Const.SECURE_DIR}/.upgrade-cleanup.complete')\" = '${BuildConfig.APP_VERSION_NAME}' ] && " +
                "[ \"\$(sed -n '2p' '${Const.SECURE_DIR}/.upgrade-cleanup.complete')\" = \"\$(cat /proc/sys/kernel/random/boot_id)\" ] && " +
                "[ \"\$(sed -n '3p' '${Const.SECURE_DIR}/.upgrade-cleanup.complete')\" = '${BuildConfig.APP_VERSION_CODE}' ]"
        ).exec().isSuccess
        if (!completed) return
        cleanedUpObsoleteManagers = runCatching {
            shell.newJob().add(
                "record_upgrade_cleanup '${BuildConfig.APP_VERSION_NAME}' '${BuildConfig.APP_VERSION_CODE}' " +
                    "\"\$(cat /proc/sys/kernel/random/boot_id)\" '${context.packageName}'"
            ).exec().isSuccess
        }.getOrDefault(false)
        if (!cleanedUpObsoleteManagers && !reportedPendingCleanup) {
            val reason = mutableListOf<String>()
            shell.newJob().add(
                "head -n 1 '${Const.SECURE_DIR}/.upgrade-frameworks.pending' 2>/dev/null"
            ).to(reason).exec()
            reason.firstOrNull()?.let {
                reportedPendingCleanup = true
                context.toast(it, Toast.LENGTH_LONG)
            }
        }
    }

}
