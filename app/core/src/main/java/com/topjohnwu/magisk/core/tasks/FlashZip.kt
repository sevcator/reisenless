package com.topjohnwu.magisk.core.tasks

import android.net.Uri
import androidx.core.net.toFile
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.ktx.writeTo
import com.topjohnwu.magisk.core.utils.MediaStoreUtils.displayName
import com.topjohnwu.magisk.core.utils.MediaStoreUtils.inputStream
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

open class FlashZip(
    private val mUri: Uri,
    private val console: MutableList<String>,
    private val logs: MutableList<String>,
    private val timeoutSeconds: Long = 0,
) {

    private val installDir = File(AppContext.cacheDir, "flash")
    private lateinit var zipFile: File

    @Throws(IOException::class)
    private suspend fun flash(): Boolean {
        installDir.deleteRecursively()
        installDir.mkdirs()

        zipFile = if (mUri.scheme == "file") {
            mUri.toFile()
        } else {
            File(installDir, "install.zip").also {
                console.add("- copying zip to temp directory")
                try {
                    mUri.inputStream().writeTo(it)
                } catch (e: IOException) {
                    when (e) {
                        is FileNotFoundException -> console.add("! invalid uri")
                        else -> console.add("! cannot copy to cache")
                    }
                    throw e
                }
            }
        }

        try {
            val binary = File(installDir, "update-binary")
            AppContext.assets.open("module_installer.sh").use { it.writeTo(binary) }
        } catch (e: IOException) {
            console.add("! unzip error")
            throw e
        }

        console.add("- installing ${mUri.displayName.lowercase()}")

        val installCommand = "sh $installDir/update-binary dummy 1 \'$zipFile\'"
        val command = if (timeoutSeconds > 0) {
            "timeout -s KILL ${timeoutSeconds}s $installCommand; " +
                "rc=\$?; [ \$rc -eq 137 ] && echo '$TIMEOUT_MARKER'; exit \$rc"
        } else {
            installCommand
        }
        val result = Shell.cmd(command).to(console, logs).exec()
        if (console.remove(TIMEOUT_MARKER)) {
            console.add("! installation timed out after ${timeoutSeconds / 60} minutes")
        }
        return result.isSuccess
    }

    open suspend fun exec() = withContext(Dispatchers.IO) {
        try {
            if (!flash()) {
                console.add("! installation failed")
                false
            } else {
                true
            }
        } catch (e: IOException) {
            false
        } finally {
            Shell.cmd("cd /", "rm -rf $installDir ${Const.TMPDIR}").submit()
        }
    }

    private companion object {
        const val TIMEOUT_MARKER = "__REISENLESS_INSTALL_TIMEOUT__"
    }
}
