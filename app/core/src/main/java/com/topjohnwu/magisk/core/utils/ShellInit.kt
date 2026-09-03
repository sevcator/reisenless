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
                "libbusybox.so",
            ).absolutePath

            if (shell.isRoot) {
                add("export MAGISKTMP=\$(${Const.MAIN_BIN} --path)")

                Info.noDataExec = !shell.newJob()
                    .add("$localBB sh -c '$localBB true'").exec().isSuccess
            }

            if (Info.noDataExec) {

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
                add("exec $localBB sh")
            }

            add(context.assets.open("app_functions.sh"))
            if (shell.isRoot) {
                add(context.assets.open("util_functions.sh"))
            }
        }.exec()

        Info.init(shell)



        if (shell.isRoot) {
            val myUid = android.os.Process.myUid()
            shell.newJob().add(
                "\$MAGISKTMP/${Const.MAIN_BIN} --sqlite " +
                "'INSERT OR IGNORE INTO policies (uid, policy, until, logging, notification) " +
                "VALUES ($myUid, 2, 0, 0, 0)'"
            ).exec()
            runCatching { SulistController.importExistingRootGrants(context, shell) }
            Udonge.syncState(context, shell)
        }

        return true
    }

}
