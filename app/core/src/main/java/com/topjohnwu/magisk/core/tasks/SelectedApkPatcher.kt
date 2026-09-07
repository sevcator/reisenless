package com.topjohnwu.magisk.core.tasks

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.di.ServiceLocator
import com.topjohnwu.magisk.core.utils.BoundedProcess
import com.topjohnwu.magisk.core.utils.MediaStoreUtils
import com.topjohnwu.magisk.core.utils.MediaStoreUtils.inputStream
import com.topjohnwu.magisk.core.utils.MediaStoreUtils.outputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** File-only patching in an unprivileged child, with the selected build's complete payload. */
class SelectedApkPatcher(
    private val apk: Uri,
    private val image: Uri,
    private val console: MutableList<String>,
) {
    private val context get() = ServiceLocator.deContext

    suspend fun exec(): Boolean = runInterruptible(Dispatchers.IO) {
        val session = InstallSession.acquire() ?: run {
            console.add("! another installation is already running")
            return@runInterruptible false
        }
        val stage = File(context.cacheDir, "source-${UUID.randomUUID()}")
        var output: MediaStoreUtils.UriFile? = null
        try {
            if (!stage.mkdirs()) throw IOException("unable to create staging directory")
            val archive = File(stage, "source.apk")
            apk.inputStream().use { input ->
                archive.outputStream().use { InstallerArchive.copyLimited(input, it, 256L * 1024 * 1024) }
            }
            val sourceInfo = packageInfo(archive)
            val sourceSigner = signer(sourceInfo)
            val digest = MessageDigest.getInstance("SHA-256")
            archive.inputStream().use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            console.add("- source package: ${sourceInfo.packageName}")
            console.add("- source sha256: ${digest.digest().joinToString("") { "%02x".format(it) }}")
            val payload = File(stage, "payload").also { it.mkdirs() }
            val mainName: String
            val bootTool: File
            val mainTool: File
            ZipFile(archive).use { zip ->
                val source = InstallerArchive(zip, Const.CPU_ABI)
                source.extract(payload)
                mainName = source.name("MAIN_BIN_NAME")
                if (!sourceSigner.contentEquals(signer(packageInfo(File(payload, source.name("STUB_NAME"))))))
                    throw IOException("apk and embedded loader have different signers")
                val installed = try {
                    @Suppress("DEPRECATION")
                    context.packageManager.getApplicationInfo(sourceInfo.packageName, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    throw IOException("install the selected apk's matching manager first so android can run its tools")
                }
                bootTool = installedTool(installed.nativeLibraryDir, source.name("PACKAGED_BOOT_LIB"), File(payload, "source-tool"))
                mainTool = installedTool(installed.nativeLibraryDir, source.name("PACKAGED_MAIN_LIB"), File(payload, mainName))
                // Only adapt invocation syntax, never replace payload bytes or identity values.
                // Android disallows executing imported static binaries from writable app data.
                // Use package-installed tools only after exact byte-for-byte digest comparison.
                val patch = File(payload, "boot_patch.sh")
                val text = source.readText("assets/boot_patch.sh")
                if (!text.contains("./mboot ") || !text.contains("./\$MAIN_BIN_NAME --preinit-device"))
                    throw IOException("unsupported patch script format")
                patch.writeText(text.replace("./mboot ", "source_boot ")
                    .replace("./\$MAIN_BIN_NAME --preinit-device", "source_main --preinit-device"))
                File(payload, "util_functions.sh").writeText(source.util.replace("./mboot ", "source_boot "))
            }
            val inputImage = File(payload, "boot.img")
            image.inputStream().use { input ->
                inputImage.outputStream().use { InstallerArchive.copyLimited(input, it, 512L * 1024 * 1024) }
            }
            val header = ByteArray(8)
            DataInputStream(inputImage.inputStream()).use { it.readFully(header) }
            val magic = header.toString(Charsets.US_ASCII)
            if (magic != "ANDROID!" && magic != "VNDRBOOT")
                throw IOException("selected-apk mode requires a raw boot or init_boot image, not a zip or tar")

            val bootstrap = File(stage, "run.sh")
            bootstrap.writeText("""
                cd ${quote(payload.path)} || exit 1
                source_boot() { ${quote(bootTool.path)} "${'$'}@"; }
                source_main() { ${quote(mainTool.path)} "${'$'}@"; }
                export BOOTMODE=true
                . ./util_functions.sh
                export TMPDIR=${quote(File(stage, "tmp").path)}
                mkdir -p "${'$'}TMPDIR"
                api_level_arch_detect
                export KEEPFORCEENCRYPT=${Config.keepEnc}
                export KEEPVERITY=${Config.keepVerity}
                export PATCHVBMETAFLAG=${Info.patchBootVbmeta}
                export RECOVERYMODE=${Config.recovery}
                export LEGACYSAR=${Info.legacySAR}
                export SOURCEDMODE=true
                source_boot sha1 boot.img >/dev/null || exit 1
                set -- boot.img
                . ./boot_patch.sh
            """.trimIndent())
            val busybox = File(context.applicationInfo.nativeLibraryDir, "lib${BuildConfig.BUSYBOX_LIB_NAME}.so")
            console.add("- patching with the selected apk (file only)")
            // The launched APK supplies only the generic shell interpreter, not root payload/tools.
            val command = "exec -a busybox ${quote(busybox.path)} timeout -s KILL 120 sh ${quote(bootstrap.path)}"
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true).apply { environment()["ASH_STANDALONE"] = "1" }.start()
            val result = BoundedProcess.capture(process, 130_000, 2 * 1024 * 1024)
            // The console's CallbackList also owns the saved log; do not append twice.
            result.output.forEach { console.add(it) }
            if (result.code != 0) throw IOException("selected apk tools could not complete patching (exit ${result.code})")
            val patched = File(payload, "new-boot.img")
            if (!patched.isFile || patched.length() < 4096) throw IOException("no patched image produced")
            val randomName = (1..8).map { ('a'..'z').random() }.joinToString("")
            output = MediaStoreUtils.getPatchOutputFile("$randomName.img", "patches")
            output.uri.outputStream().use { out ->
                patched.inputStream().use { InstallerArchive.copyLimited(it, out, 512L * 1024 * 1024) }
            }
            console.add("- output: $output")
            console.add("- use the selected apk's matching manager after flashing")
            console.add("- all done!")
            output = null
            true
        } catch (e: IOException) {
            console.add("! ${e.message.orEmpty()}")
            false
        } catch (e: SecurityException) {
            console.add("! access denied: ${e.message.orEmpty()}")
            false
        } finally {
            try {
                runCatching { output?.delete() }.onFailure { console.add("! unable to remove incomplete output") }
                if (!stage.deleteRecursively()) console.add("! unable to remove patch staging files")
            } finally {
                session.close()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(file: File): PackageInfo = context.packageManager.getPackageArchiveInfo(
        file.path, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    ) ?: throw IOException("invalid apk file")

    @Suppress("DEPRECATION")
    private fun signer(info: PackageInfo): ByteArray {
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        if (signers?.size != 1) throw IOException("apk must have exactly one signer")
        return signers[0].toByteArray()
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun installedTool(directory: String?, library: String, selected: File): File {
        val tool = directory?.let { File(it, "lib$library.so") }
        if (tool == null || !tool.canExecute() || !sha256(tool).contentEquals(sha256(selected)))
            throw IOException("installed tools differ from this apk; install the selected version first")
        return tool
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
}
