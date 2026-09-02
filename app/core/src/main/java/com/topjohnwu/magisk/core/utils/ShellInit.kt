package com.topjohnwu.magisk.core.utils

import android.content.Context
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.Udonge
import com.topjohnwu.magisk.core.isRunningAsStub
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

            val localBB: String
            if (isRunningAsStub) {
                if (!shell.isRoot)
                    return true







                add("export MAGISKTMP=\$(${Const.MAIN_BIN} --path)")
                localBB = "\$MAGISKTMP/${Const.INTERNAL_DIR}/" +
                    "${Const.BUSYBOX_NAME}/${Const.BUSYBOX_NAME}"
                Info.noDataExec = false
            } else {



                localBB = File(
                    context.applicationInfo.nativeLibraryDir,
                    "libbusybox.so",
                ).absolutePath
            }

            if (shell.isRoot && !isRunningAsStub) {
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

                if (isRunningAsStub) {



                    add("exec -a sh $localBB")
                } else {
                    add("exec $localBB sh")
                }
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
            Udonge.syncKeyboxUrls(shell)
            Udonge.syncBackgroundUpdates(shell)
            detectAndSaveRomKeywords(context, shell)
        }

        return true
    }

    private fun detectAndSaveRomKeywords(context: Context, shell: Shell) {
        val directProperties = linkedMapOf(
            "ro.lineage.version" to listOf("lineage", "lineageos"),
            "ro.lineage.build.version" to listOf("lineage", "lineageos"),
            "ro.cm.version" to listOf("lineage", "cyanogenmod"),
            "ro.resurrection.version" to listOf("resurrection"),
            "ro.pa.version" to listOf("paranoid", "aospa"),
            "ro.aospa.version" to listOf("paranoid", "aospa"),
            "ro.proton.version" to listOf("proton", "protonaosp"),
            "ro.protonaosp.version" to listOf("proton", "protonaosp"),
            "ro.calyxos.version" to listOf("calyx", "calyxos"),
            "ro.grapheneos.version" to listOf("graphene", "grapheneos"),
            "ro.pe.version" to listOf("pixelexperience"),
            "ro.pixelexperience.version" to listOf("pixelexperience"),
            "ro.crdroid.version" to listOf("crdroid"),
            "ro.evolution.version" to listOf("evolution", "evolutionx"),
            "ro.evolutionx.version" to listOf("evolution", "evolutionx"),
            "ro.havoc.version" to listOf("havoc"),
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
            "aospa" to listOf("aospa", "paranoid"),
            "paranoid" to listOf("aospa", "paranoid"),
            "evolutionx" to listOf("evolution", "evolutionx"),
            "omnirom" to listOf("omnirom"),
            "havoc" to listOf("havoc"),
            "resurrection" to listOf("resurrection"),
            "sparkos" to listOf("spark", "sparkos"),
            "risingos" to listOf("rising", "risingos"),
            "bliss" to listOf("bliss", "blissroms"),
        )
        identityKeywords.forEach { (signal, keywords) ->
            if (identity.contains(signal)) detected.addAll(keywords)
        }



        val installedPackages = runCatching {
            context.packageManager.getInstalledPackages(0)
                .asSequence()
                .map { it.packageName.lowercase() }
                .toList()
        }.getOrDefault(emptyList())
        identityKeywords.forEach { (signal, keywords) ->



            if (installedPackages.count { it.contains(signal) } >= 2) {
                detected.addAll(keywords)
            }
        }
        if (detected.isEmpty()) return
        val existing = Config.udongeRomKeywords
        val combined = (existing.lineSequence().filter { it.isNotBlank() } + detected.asSequence())
            .distinct().joinToString("\n")
        if (combined != existing) {
            Config.udongeRomKeywords = combined
            Udonge.setRomKeywords(combined, shell)
        }
    }
}
