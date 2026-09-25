package com.topjohnwu.magisk.core.tasks

import android.net.Uri
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.OsConstants.O_WRONLY
import androidx.annotation.WorkerThread
import androidx.core.os.postDelayed
import com.topjohnwu.magisk.core.AppApkPath
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.di.ServiceLocator
import com.topjohnwu.magisk.core.ktx.copyAll
import com.topjohnwu.magisk.core.ktx.writeTo
import com.topjohnwu.magisk.core.utils.DummyList
import com.topjohnwu.magisk.core.utils.DataSourceChannel
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
import kotlinx.coroutines.NonCancellable
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
import java.util.UUID

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
        console.add("- locating target image")
        val fileSystem = rootFS
        val cmd =
            "RECOVERYMODE=${Config.recovery} " +
            "VENDORBOOT=${Info.isVendorBoot} " +
            "SLOT=$slot " +
            "find_boot_image; echo \$BOOTIMAGE"
        val namedCandidates = when {
            Info.isVendorBoot -> listOf("/dev/block/by-name/vendor_boot$slot")
            Config.recovery -> listOf(
                "/dev/block/by-name/recovery$slot",
                "/dev/block/by-name/sos",
            )
            isGtGki13Kernel() -> listOf(
                "/dev/block/by-name/init_boot$slot",
                "/dev/block/by-name/boot$slot",
            )
            else -> listOf("/dev/block/by-name/boot$slot")
        }
        val bootPath = namedCandidates.firstOrNull { fileSystem.getFile(it).exists() }
            ?: ("($cmd)").fsh()
        if (bootPath.isEmpty()) {
            console.add("! unable to detect target image")
            return false
        }
        console.add("- opening target image")
        srcBoot = fileSystem.getFile(bootPath)
        console.add("- target image: $bootPath")
        return true
    }

    private fun isGtGki13Kernel(): Boolean {
        val release = System.getProperty("os.version").orEmpty()
        val major = release.substringBefore('.').toIntOrNull() ?: return false
        return major >= 5 && !release.contains("android12-") && !release.startsWith("5.4")
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

        installDir = localFS.getFile(context.cacheDir, "install-${UUID.randomUUID()}")
        if (!installDir.mkdirs()) throw IOException("unable to create installation directory")

        try {
            val sourceApk = File(context.applicationInfo.sourceDir)
            ZipFile.builder().setFile(sourceApk).get().use { zf ->
                zf.entries.asSequence().filter {
                    !it.isDirectory && it.name.startsWith("lib/${Const.CPU_ABI}/")
                }.forEach {
                    val n = it.name.substring(it.name.lastIndexOf('/') + 1)
                    val packagedName = n.substring(3, n.length - 3)
                    val name = when (packagedName) {
                        BuildConfig.MAIN_LIB_NAME -> BuildConfig.MAIN_BIN_NAME
                        BuildConfig.POLICY_LIB_NAME -> BuildConfig.POLICY_NAME
                        BuildConfig.INIT_LD_LIB_NAME -> BuildConfig.INIT_LD_NAME
                        BuildConfig.BUSYBOX_LIB_NAME -> BuildConfig.BUSYBOX_NAME
                        BuildConfig.BOOT_LIB_NAME -> "mboot"
                        BuildConfig.INIT_LIB_NAME -> "minit"
                        BuildConfig.BOOTCTL_LIB_NAME -> "bootctl"
                        else -> packagedName
                    }
                    val dest = File(installDir, name)
                    if (!shell.isRoot && (name == "mboot" || name == BuildConfig.MAIN_BIN_NAME)) {
                        // Modern Android denies execution of files written into
                        // app data. Execute the package-installed, read-only copy.
                        val installed = File(context.applicationInfo.nativeLibraryDir, n)
                        if (!installed.canExecute()) throw IOException("Missing installed tool")
                        Os.symlink(installed.path, dest.path)
                    } else {
                        zf.getInputStream(it).writeTo(dest)
                        dest.setExecutable(true)
                    }
                }

                val abi32 = Const.CPU_ABI_32
                if (Process.is64Bit() && abi32 != null) {
                    val entry = zf.getEntry(
                        "lib/$abi32/lib${BuildConfig.MAIN_LIB_NAME}.so"
                    )
                    if (entry != null) {
                        val bin32 = File(installDir, BuildConfig.BIN32_NAME)
                        zf.getInputStream(entry).writeTo(bin32)
                        bin32.setExecutable(true)
                    }
                }
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

            rootFS.getFile(Const.TMPDIR, installDir.name).also {
                arrayOf(
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

                val name = entry.name.replace(".lz4", "")
                if (patchVbmetaData(rawData)) {
                    Info.patchBootVbmeta = false
                    console.add("-- patching  : $name")
                } else {
                    console.add("-- skipping  : invalid $name")
                }


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
        var pendingStream: OutputStream? = null
        var pendingFile: MediaStoreUtils.UriFile? = null
        var completed = false
        var bootItem: BootItem? = null
        try {
            PushbackInputStream(uri.inputStream().buffered(1024 * 1024), 512).use { src ->
                val head = ByteArray(512)
                java.io.DataInputStream(src).readFully(head)
                src.unread(head)
                val magic = head.copyOf(4)
                val tarMagic = head.copyOfRange(257, 262)
                val outFile = MediaStoreUtils.getPatchOutputFile("$destName.$destExt", destFolder)
                pendingFile = outFile
                val stream = outFile.uri.outputStream()
                pendingStream = stream
                srcBoot = if (tarMagic.contentEquals("ustar".toByteArray())) {
                    val tar = TarArchiveOutputStream(stream.buffered(1024 * 1024)).also {
                        it.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
                        it.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                    }
                    pendingStream = tar
                    bootItem = processTar(TarArchiveInputStream(src), tar)
                    bootItem.file
                } else {
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
                }
            }
            if (!patchBoot()) return false
            val outStream = requireNotNull(pendingStream)
            val newBoot = installDir.getChildFile("new-boot.img")
            if (bootItem != null) {
                bootItem.file = newBoot
                bootItem.copyTo(outStream as TarArchiveOutputStream)
            } else {
                newBoot.newInputStream().use { it.copyAll(outStream, 1024 * 1024) }
            }
            newBoot.delete()
            // Closing/finalizing the provider stream is part of success.
            outStream.close()
            pendingStream = null
            srcBoot.delete()
            "cp_readlink $installDir".sh()
            completed = true
            console.add("")
            console.add("****************************")
            console.add(" output file is written to ")
            console.add(" $pendingFile ")
            console.add("****************************")
            return true
        } catch (e: IOException) {
            if (e is NoBootException) console.add("! no boot image found")
            console.add("! process error: ${e.message.orEmpty()}")
            return false
        } finally {
            runCatching { pendingStream?.close() }
            if (!completed) runCatching { pendingFile?.delete() }
                .onFailure { console.add("! unable to remove incomplete output") }
        }
    }

    private suspend fun processUrl(url: String): Boolean {
        try {
            srcBoot = installDir.getChildFile("boot.img")
            ExtractImage(srcBoot, console, logs)
                .consume(DataSourceChannel(ServiceLocator.okhttp, url))
        } catch (e: IOException) {
            console.add("! error: ${e.message.orEmpty()}")
            return false
        }

        if (!patchBoot()) return false

        val outFile = try {
            MediaStoreUtils.getPatchOutputFile("$destName.$destExt", destFolder)
        } catch (e: IOException) {
            console.add("! failed to create output file")
            return false
        }
        try {
            val newBoot = installDir.getChildFile("new-boot.img")
            outFile.uri.outputStream().use { out ->
                newBoot.newInputStream().use { it.copyAll(out, 1024 * 1024) }
            }
            newBoot.delete()
            console.add("")
            console.add("****************************")
            console.add(" output file is written to ")
            console.add(" $outFile ")
            console.add("****************************")
        } catch (_: IOException) {
            console.add("! failed to output to $outFile")
            outFile.delete()
            return false
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

    private fun postOTA(): Boolean {
        "post_ota".sh()
        console.add("*************************************************************")
        console.add(" next reboot will boot to second slot!")
        console.add(" go back to system updates and press restart to complete ota")
        console.add("*************************************************************")
        return true
    }

    private fun Array<String>.eq() = shell.newJob().add(*this).to(console, logs).enqueue()
    private fun String.sh() = shell.newJob().add(this).to(console, logs).exec()
    private fun Array<String>.sh() = shell.newJob().add(*this).to(console, logs).exec()
    private fun String.fsh() = ShellUtils.fastCmd(shell, this)
    private fun Array<String>.fsh() = ShellUtils.fastCmd(shell, *this)

    protected suspend fun patchFile(file: Uri) = extractFiles() && processFile(file)

    protected suspend fun patchFile(url: String) = extractFiles() && processUrl(url)

    protected suspend fun direct() = findImage() && extractFiles() && patchBoot() && flashBoot()

    protected suspend fun secondSlot() =
        findSecondary() && extractFiles() && patchBoot() && flashBoot() && postOTA()

    protected suspend fun fixEnv() = extractFiles() && "fix_env $installDir".sh().isSuccess

    protected fun restore() = findImage() && "restore_imgs $srcBoot".sh().isSuccess

    protected fun uninstall() = "run_uninstaller $AppApkPath".sh().isSuccess

    @WorkerThread
    protected abstract suspend fun operations(): Boolean

    open suspend fun exec(): Boolean {
        val session = InstallSession.acquire() ?: run {
            console.add("! another installation is already running")
            return false
        }

        return try {
            withContext(Dispatchers.IO) { operations() }
        } catch (e: IOException) {
            console.add("! operation failed: ${e.message.orEmpty()}")
            false
        } catch (e: SecurityException) {
            console.add("! access denied: ${e.message.orEmpty()}")
            false
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    // Only this operation's unique staging directory, never a
                    // shared cache/root directory. Finish before releasing the lease.
                    if (::installDir.isInitialized && !installDir.deleteRecursively())
                        console.add("! unable to remove installation staging files")
                }
            } finally {
                session.close()
            }
        }
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

internal fun patchVbmetaData(data: ByteArray): Boolean {
    if (data.size < 256) return false
    ByteBuffer.wrap(data).putInt(120, 3)
    return true
}

class MagiskInstaller {

    class Patch(
        private val uri: Uri,
        console: MutableList<String>,
        logs: MutableList<String>
    ) : ConsoleInstaller(console, logs) {
        override suspend fun operations() = patchFile(uri)
    }

    class Download(
        private val url: String,
        console: MutableList<String>,
        logs: MutableList<String>,
    ) : ConsoleInstaller(console, logs) {
        override suspend fun operations() = patchFile(url)
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
