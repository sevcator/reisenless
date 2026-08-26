package com.topjohnwu.magisk.core.tasks

import android.net.Uri
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.OsConstants.O_WRONLY
import androidx.annotation.WorkerThread
import androidx.core.os.postDelayed
import com.topjohnwu.magisk.StubApk
import com.topjohnwu.magisk.core.AppApkPath
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.di.ServiceLocator
import com.topjohnwu.magisk.core.isRunningAsStub
import com.topjohnwu.magisk.core.ktx.copyAll
import com.topjohnwu.magisk.core.ktx.writeTo
import com.topjohnwu.magisk.core.utils.DummyList
import com.topjohnwu.magisk.core.utils.MediaStoreUtils
import com.topjohnwu.magisk.core.utils.MediaStoreUtils.inputStream
import com.topjohnwu.magisk.core.utils.MediaStoreUtils.outputStream
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.internal.UiThreadHandler
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

abstract class MagiskInstallImpl protected constructor(
    protected val console: MutableList<String>,
    private val logs: MutableList<String>
) {

    private lateinit var installDir: ExtendedFile
    private lateinit var srcBoot: ExtendedFile

    private val shell = Shell.getShell()
    private val useRootDir = shell.isRoot && Info.noDataExec
    protected val context get() = ServiceLocator.deContext

    private val rootFS get() = RootUtils.fs
    private val localFS get() = FileSystemManager.getLocal()

    private val asciiLetters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val random = SecureRandom()

    private fun randStr(min: Int, max: Int): String {
        val len = if (min == max) min else min + random.nextInt(max - min + 1)
        return buildString(len) {
            repeat(len) { append(asciiLetters[random.nextInt(asciiLetters.length)]) }
        }
    }

    private val destFolder: String by lazy { randStr(4, 9) }
    private val destName: String by lazy { randStr(4, 9) }
    private val destExt: String by lazy { randStr(3, 3) }

    private fun findImage(slot: String): Boolean {
        val cmd =
            "RECOVERYMODE=${Config.recovery} " +
            "VENDORBOOT=${Info.isVendorBoot} " +
            "SLOT=$slot " +
            "find_boot_image; echo \$BOOTIMAGE"
        val bootPath = ("($cmd)").fsh()
        if (bootPath.isEmpty()) {
            console.add("! unable to detect target image")
            return false
        }
        srcBoot = rootFS.getFile(bootPath)
        console.add("- target image: $bootPath")
        return true
    }

    private fun findImage(): Boolean {
        return findImage(Info.slot)
    }

    private fun findSecondary(): Boolean {
        val slot = if (Info.slot == "_a") "_b" else "_a"
        console.add("- target slot: $slot")
        return findImage(slot)
    }

    private suspend fun extractFiles(): Boolean {
        console.add("- device platform: ${Const.CPU_ABI}")
        console.add("- installing: ${BuildConfig.APP_VERSION_NAME} (${BuildConfig.APP_VERSION_CODE})")

        installDir = localFS.getFile(context.filesDir.parent, "install")
        installDir.deleteRecursively()
        installDir.mkdirs()

        try {
            val sourceApk = if (isRunningAsStub) {
                StubApk.current(context)
            } else {
                File(context.applicationInfo.sourceDir)
            }

            if (isRunningAsStub) {
                ZipFile.builder().setFile(sourceApk).get().use { zf ->
                    zf.entries.asSequence().filter {
                        !it.isDirectory && it.name.startsWith("lib/${Const.CPU_ABI}/")
                    }.forEach {
                        val n = it.name.substring(it.name.lastIndexOf('/') + 1)
                        val packagedName = n.substring(3, n.length - 3)
                        val name = when (packagedName) {
                            "mpol" -> BuildConfig.POLICY_NAME
                            "init-ld" -> BuildConfig.INIT_LD_NAME
                            "busybox" -> BuildConfig.BUSYBOX_NAME
                            else -> packagedName
                        }
                        val dest = File(installDir, name)
                        zf.getInputStream(it).writeTo(dest)
                        dest.setExecutable(true)
                    }

                    val abi32 = Const.CPU_ABI_32
                    if (Process.is64Bit() && abi32 != null) {
                        val entry = zf.getEntry("lib/$abi32/libmagisk.so")
                        if (entry != null) {
                            val bin32 = File(installDir, BuildConfig.BIN32_NAME)
                            zf.getInputStream(entry).writeTo(bin32)
                        }
                    }
                }
            } else {
                ZipFile.builder().setFile(sourceApk).get().use { zf ->
                    zf.entries.asSequence().filter {
                        !it.isDirectory && it.name.startsWith("lib/${Const.CPU_ABI}/")
                    }.forEach {
                        val n = it.name.substring(it.name.lastIndexOf('/') + 1)
                        val packagedName = n.substring(3, n.length - 3)
                        val name = when (packagedName) {
                            "busybox" -> BuildConfig.BUSYBOX_NAME
                            else -> packagedName
                        }
                        val dest = File(installDir, name)
                        zf.getInputStream(it).writeTo(dest)
                        dest.setExecutable(true)
                    }

                    val abi32 = Const.CPU_ABI_32
                    if (Process.is64Bit() && abi32 != null) {
                        val entry = zf.getEntry("lib/$abi32/libmagisk.so")
                        if (entry != null) {
                            val bin32 = File(installDir, BuildConfig.BIN32_NAME)
                            zf.getInputStream(entry).writeTo(bin32)
                            bin32.setExecutable(true)
                        }
                    }
                }
            }



            val packagedMain = File(installDir, "magisk")
            val runtimeMain = File(installDir, BuildConfig.MAIN_BIN_NAME)
            if (packagedMain != runtimeMain && packagedMain.exists()) {
                if (!packagedMain.renameTo(runtimeMain)) {
                    packagedMain.copyTo(runtimeMain, overwrite = true)
                    packagedMain.delete()
                }
                runtimeMain.setExecutable(true)
            }

            val packagedPolicy = File(installDir, "mpol")
            val runtimePolicy = File(installDir, BuildConfig.POLICY_NAME)
            if (packagedPolicy != runtimePolicy && packagedPolicy.exists()) {
                packagedPolicy.renameTo(runtimePolicy)
            }
            val packagedInitLd = File(installDir, "init-ld")
            val runtimeInitLd = File(installDir, BuildConfig.INIT_LD_NAME)
            if (packagedInitLd != runtimeInitLd && packagedInitLd.exists()) {
                packagedInitLd.renameTo(runtimeInitLd)
            }


            ZipFile.builder().setFile(sourceApk).get().use { zf ->
                for (asset in listOf(
                    "util_functions.sh", "boot_patch.sh", "addon.d.sh",
                    BuildConfig.STUB_NAME, BuildConfig.UDONGE_ARCHIVE,
                    "chromeos/futility", "chromeos/kernel_data_key.vbprivk",
                    "chromeos/kernel.keyblock"
                )) {
                    val entry = requireNotNull(zf.getEntry("assets/$asset"))
                    val dest = File(installDir, asset)
                    dest.parentFile?.mkdirs()
                    zf.getInputStream(entry).writeTo(dest)
                }
            }
        } catch (e: Exception) {
            console.add("! unable to extract files")
            return false
        }

        if (useRootDir) {

            rootFS.getFile(Const.TMPDIR).also {
                arrayOf(
                    "rm -rf $it",
                    "mkdir -p $it",
                    "cp_readlink $installDir $it",
                    "rm -rf $installDir"
                ).sh()
                installDir = it
            }
        }

        return true
    }

    private suspend fun InputStream.copyAndCloseOut(out: OutputStream) =
        out.use { copyAll(it, 1024 * 1024) }

    private class NoAvailableStream(s: InputStream) : FilterInputStream(s) {


        override fun available() = 0
    }

    private class NoBootException : IOException()

    inner class BootItem(private val entry: TarArchiveEntry) {
        val name = entry.name.replace(".lz4", "")
        var file = installDir.getChildFile(name)

        suspend fun copyTo(tarOut: TarArchiveOutputStream) {
            entry.name = name
            entry.size = file.length()
            file.newInputStream().use {
                console.add("-- writing   : $name")
                tarOut.putArchiveEntry(entry)
                it.copyAll(tarOut)
                tarOut.closeArchiveEntry()
            }
        }
    }

    @Throws(IOException::class)
    private suspend fun processTar(
        tarIn: TarArchiveInputStream,
        tarOut: TarArchiveOutputStream
    ): BootItem {
        console.add("- processing tar file")
        var entry: TarArchiveEntry? = tarIn.nextEntry

        fun decompressedStream(): InputStream {
            val stream = if (tarIn.currentEntry.name.endsWith(".lz4"))
                FramedLZ4CompressorInputStream(tarIn, true) else tarIn
            return NoAvailableStream(stream)
        }

        var boot: BootItem? = null
        var initBoot: BootItem? = null
        var recovery: BootItem? = null

        while (entry != null) {
            val bootItem: BootItem?
            if (entry.name.startsWith("boot.img")) {
                bootItem = BootItem(entry)
                boot = bootItem
            } else if (entry.name.startsWith("init_boot.img")) {
                bootItem = BootItem(entry)
                initBoot = bootItem
            } else if (Config.recovery && entry.name.contains("recovery.img")) {
                bootItem = BootItem(entry)
                recovery = bootItem
            } else {
                bootItem = null
            }

            if (bootItem != null) {
                console.add("-- extracting: ${bootItem.name}")
                decompressedStream().copyAndCloseOut(bootItem.file.newOutputStream())
            } else if (entry.name.contains("vbmeta.img")) {
                val rawData = decompressedStream().readBytes()

                if (rawData.size < 256)
                    continue


                Info.patchBootVbmeta = false

                val name = entry.name.replace(".lz4", "")
                console.add("-- patching  : $name")



                ByteBuffer.wrap(rawData).putInt(120, 3)


                val vbmeta = entry
                entry = tarIn.nextEntry


                vbmeta.name = name
                vbmeta.size = rawData.size.toLong()


                tarOut.putArchiveEntry(vbmeta)
                tarOut.write(rawData)
                tarOut.closeArchiveEntry()
                continue
            } else if (entry.name.contains("userdata.img")) {
                console.add("-- skipping  : ${entry.name}")
            } else {
                console.add("-- copying   : ${entry.name}")
                tarOut.putArchiveEntry(entry)
                tarIn.copyAll(tarOut)
                tarOut.closeArchiveEntry()
            }
            entry = tarIn.nextEntry ?: break
        }


        return when {
            recovery != null -> {
                if (boot != null) {

                    arrayOf(
                        "cd $installDir",
                        "chmod -R 755 .",
                        "./mboot unpack boot.img",
                        "./mboot repack boot.img",
                        "cat new-boot.img > boot.img",
                        "./mboot cleanup",
                        "rm -f new-boot.img",
                        "cd /").sh()
                    boot.copyTo(tarOut)
                }
                recovery
            }
            initBoot != null -> {
                boot?.copyTo(tarOut)
                initBoot
            }
            boot != null -> boot
            else -> throw NoBootException()
        }
    }

    @Throws(IOException::class)
    private suspend fun processZip(zipIn: ZipArchiveInputStream): ExtendedFile {
        console.add("- processing zip file")
        val boot = installDir.getChildFile("boot.img")
        val initBoot = installDir.getChildFile("init_boot.img")
        var entry: ZipArchiveEntry
        while (true) {
            entry = zipIn.nextEntry ?: break
            if (entry.isDirectory) continue
            when (entry.name.substringAfterLast('/')) {
                "payload.bin" -> {
                    try {
                        return processPayload(zipIn)
                    } catch (e: IOException) {

                    }
                }
                "init_boot.img" -> {
                    console.add("- extracting init_boot.img")
                    zipIn.copyAndCloseOut(initBoot.newOutputStream())
                    return initBoot
                }
                "boot.img" -> {
                    console.add("- extracting boot.img")
                    zipIn.copyAndCloseOut(boot.newOutputStream())

                }
            }
        }
        if (boot.exists()) {
            return boot
        } else {
            throw NoBootException()
        }
    }

    @Throws(IOException::class)
    private fun processPayload(input: InputStream): ExtendedFile {
        var fifo: File? = null
        try {
            console.add("- processing payload.bin")
            fifo = File.createTempFile("payload-fifo-", null, installDir)
            fifo.delete()
            Os.mkfifo(fifo.path, 420           )


            val future = arrayOf(
                "cd $installDir",
                "./mboot extract $fifo",
                "cd /"
            ).eq()

            val fd = Os.open(fifo.path, O_WRONLY, 0)
            try {
                val bufSize = 1024 * 1024
                val buf = ByteBuffer.allocate(bufSize)
                buf.position(input.read(buf.array()).coerceAtLeast(0)).flip()
                while (buf.hasRemaining()) {
                    try {
                        Os.write(fd, buf)
                    } catch (e: ErrnoException) {
                        if (e.errno != OsConstants.EPIPE)
                            throw e

                        break
                    }
                    if (!buf.hasRemaining()) {
                        buf.limit(bufSize)
                        buf.position(input.read(buf.array()).coerceAtLeast(0)).flip()
                    }
                }
            } finally {
                Os.close(fd)
            }

            val success = try { future.get().isSuccess } catch (e: Exception) { false }
            if (!success) {
                console.add("! error while extracting payload.bin")
                throw IOException()
            }
            val boot = installDir.getChildFile("boot.img")
            val initBoot = installDir.getChildFile("init_boot.img")
            return when {
                initBoot.exists() -> {
                    console.add("-- extract init_boot.img")
                    initBoot
                }
                boot.exists() -> {
                    console.add("-- extract boot.img")
                    boot
                }
                else -> {
                    throw NoBootException()
                }
            }
        } catch (e: ErrnoException) {
            throw IOException(e)
        } finally {
            fifo?.delete()
        }
    }

    private suspend fun processFile(uri: Uri): Boolean {
        val outStream: OutputStream
        val outFile: MediaStoreUtils.UriFile
        var bootItem: BootItem? = null


        try {
            PushbackInputStream(uri.inputStream().buffered(1024 * 1024), 512).use { src ->
                val head = ByteArray(512)
                if (src.read(head) != head.size) {
                    console.add("! invalid input file")
                    return false
                }
                src.unread(head)

                val magic = head.copyOf(4)
                val tarMagic = head.copyOfRange(257, 262)

                srcBoot = if (tarMagic.contentEquals("ustar".toByteArray())) {

                    outFile = MediaStoreUtils.getFileAtStorageRoot(
                        "$destName.$destExt",
                        destFolder,
                    )
                    val os = outFile.uri.outputStream().buffered(1024 * 1024)
                    outStream = TarArchiveOutputStream(os).also {
                        it.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
                        it.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                    }

                    try {
                        bootItem = processTar(TarArchiveInputStream(src), outStream)
                        bootItem.file
                    } catch (e: IOException) {
                        outStream.close()
                        outFile.delete()
                        throw e
                    }
                } else {

                    outFile = MediaStoreUtils.getFileAtStorageRoot(
                        "$destName.$destExt",
                        destFolder,
                    )
                    outStream = outFile.uri.outputStream()

                    try {
                        if (magic.contentEquals("CrAU".toByteArray())) {
                            processPayload(src)
                        } else if (magic.contentEquals("PK\u0003\u0004".toByteArray())) {
                            processZip(ZipArchiveInputStream(src))
                        } else {
                            console.add("- copying image to cache")
                            installDir.getChildFile("boot.img").also {
                                src.copyAndCloseOut(it.newOutputStream())
                            }
                        }
                    } catch (e: IOException) {
                        outStream.close()
                        outFile.delete()
                        throw e
                    }
                }
            }
        } catch (e: IOException) {
            if (e is NoBootException)
                console.add("! no boot image found")
            console.add("! process error")
            return false
        }


        if (!patchBoot()) {
            outFile.delete()
            return false
        }


        try {
            val newBoot = installDir.getChildFile("new-boot.img")
            if (bootItem != null) {
                bootItem.file = newBoot
                bootItem.copyTo(outStream as TarArchiveOutputStream)
            } else {
                newBoot.newInputStream().use { it.copyAll(outStream, 1024 * 1024) }
            }
            newBoot.delete()

            console.add("")
            console.add("****************************")
            console.add(" output file is written to ")
            console.add(" $outFile ")
            console.add("****************************")
        } catch (e: IOException) {
            console.add("! failed to output to $outFile")
            outFile.delete()
            return false
        } finally {
            outStream.close()
        }


        srcBoot.delete()
        "cp_readlink $installDir".sh()

        return true
    }

    private fun patchBoot(): Boolean {
        val newBoot = installDir.getChildFile("new-boot.img")
        if (!useRootDir) {

            newBoot.createNewFile()
            File(installDir, "stock_boot.img").createNewFile()
        }

        val cmds = arrayOf(
            "cd $installDir",
            "KEEPFORCEENCRYPT=${Config.keepEnc} " +
            "KEEPVERITY=${Config.keepVerity} " +
            "PATCHVBMETAFLAG=${Info.patchBootVbmeta} " +
            "RECOVERYMODE=${Config.recovery} " +
            "LEGACYSAR=${Info.legacySAR} " +
            "sh boot_patch.sh $srcBoot")
        val isSuccess = cmds.sh().isSuccess

        shell.newJob().add("./mboot cleanup", "cd /").exec()

        return isSuccess
    }

    private fun flashBoot() = "direct_install $installDir $srcBoot".sh().isSuccess

    private suspend fun postOTA(): Boolean {
        var bootctl: File? = null
        return try {
            val file = File.createTempFile("bootctl", null, context.cacheDir)
            bootctl = file
            context.assets.open("bootctl").writeTo(file)
            "post_ota $file".sh()

            console.add("*************************************************************")
            console.add(" next reboot will boot to second slot!")
            console.add(" go back to system updates and press restart to complete ota")
            console.add("*************************************************************")
            true
        } catch (_: IOException) {
            console.add("! unable to download bootctl")
            false
        } finally {
            bootctl?.delete()
        }
    }

    private fun Array<String>.eq() = shell.newJob().add(*this).to(console, logs).enqueue()
    private fun String.sh() = shell.newJob().add(this).to(console, logs).exec()
    private fun Array<String>.sh() = shell.newJob().add(*this).to(console, logs).exec()
    private fun String.fsh() = ShellUtils.fastCmd(shell, this)
    private fun Array<String>.fsh() = ShellUtils.fastCmd(shell, *this)

    protected suspend fun patchFile(file: Uri) = extractFiles() && processFile(file)

    protected suspend fun direct() = findImage() && extractFiles() && patchBoot() && flashBoot()

    protected suspend fun secondSlot() =
        findSecondary() && extractFiles() && patchBoot() && flashBoot() && postOTA()

    protected suspend fun fixEnv() = extractFiles() && "fix_env $installDir".sh().isSuccess

    protected fun restore() = findImage() && "restore_imgs $srcBoot".sh().isSuccess

    protected fun uninstall() = "run_uninstaller $AppApkPath".sh().isSuccess

    @WorkerThread
    protected abstract suspend fun operations(): Boolean

    open suspend fun exec(): Boolean {
        if (haveActiveSession.getAndSet(true))
            return false

        val result = withContext(Dispatchers.IO) { operations() }
        haveActiveSession.set(false)
        if (result)
            return true


        if (::installDir.isInitialized)
            Shell.cmd("rm -rf $installDir").submit()
        return false
    }

    companion object {
        private var haveActiveSession = AtomicBoolean(false)
    }
}

abstract class ConsoleInstaller(
    console: MutableList<String>,
    logs: MutableList<String>
) : MagiskInstallImpl(console, logs) {
    override suspend fun exec(): Boolean {
        val success = super.exec()
        if (success) {
            console.add("- all done!")
        } else {
            console.add("! installation failed")
        }
        return success
    }
}

abstract class CallBackInstaller : MagiskInstallImpl(DummyList, DummyList) {
    suspend fun exec(callback: (Boolean) -> Unit): Boolean {
        val success = exec()
        callback(success)
        return success
    }
}

class MagiskInstaller {

    class Patch(
        private val uri: Uri,
        console: MutableList<String>,
        logs: MutableList<String>
    ) : ConsoleInstaller(console, logs) {
        override suspend fun operations() = patchFile(uri)
    }

    class SecondSlot(
        console: MutableList<String>,
        logs: MutableList<String>
    ) : ConsoleInstaller(console, logs) {
        override suspend fun operations() = secondSlot()
    }

    class Direct(
        console: MutableList<String>,
        logs: MutableList<String>
    ) : ConsoleInstaller(console, logs) {
        override suspend fun operations() = direct()
    }

    class Emulator(
        console: MutableList<String>,
        logs: MutableList<String>
    ) : ConsoleInstaller(console, logs) {
        override suspend fun operations() = fixEnv()
    }

    class Uninstall(
        console: MutableList<String>,
        logs: MutableList<String>
    ) : ConsoleInstaller(console, logs) {
        override suspend fun operations() = uninstall()

        override suspend fun exec(): Boolean {
            val success = super.exec()
            if (success) {
                UiThreadHandler.handler.postDelayed(3000) {
                    Shell.cmd("pm uninstall ${context.packageName}").exec()
                }
            }
            return success
        }
    }

    class Restore : CallBackInstaller() {
        override suspend fun operations() = restore()
    }

    class FixEnv : CallBackInstaller() {
        override suspend fun operations() = fixEnv()
    }
}
