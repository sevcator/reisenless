package com.topjohnwu.magisk.core.utils

import android.content.Context
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.Udonge
import com.topjohnwu.magisk.core.sulist.SulistController
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
                // Keep the app-to-script variable neutral: DEX branding is not
                // applied to the scripts' canonical module environment names.
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
                // BusyBox dispatches on argv[0]; a randomized .so filename is
                // not an applet. Preserve its internal dispatcher name.
                add("exec -a busybox '$localBB' sh")
            }

            add(context.assets.open("app_functions.sh"))
            if (shell.isRoot) {
                add(context.assets.open("util_functions.sh"))
            }
        }.exec()

        Info.init(shell)



        if (shell.isRoot) {
            runCatching { SulistController.importExistingRootGrants(context) }
            Udonge.syncState(context, shell)
            cleanupObsoleteManagers(context, shell)
        }

        return true
    }

    private fun cleanupObsoleteManagers(context: Context, shell: Shell) {
        runCatching {
            val pm = context.packageManager
            val currentPkg = context.packageName
            val installed = pm.getInstalledApplications(0)
            for (app in installed) {
                val pkg = app.packageName
                if (pkg != currentPkg && pm.checkSignatures(currentPkg, pkg) == android.content.pm.PackageManager.SIGNATURE_MATCH) {
                    shell.newJob().add("pm uninstall '$pkg'").exec()
                }
            }
        }
    }

}
