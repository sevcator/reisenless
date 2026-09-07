package com.topjohnwu.magisk.core.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class InstallerArchiveTest {
    private val fields = listOf("MAIN_BIN_NAME", "INIT_LD_NAME", "RAMDISK_NAME", "STUB_NAME",
        "UDONGE_ARCHIVE", "BACKUP_CONFIG", "PACKAGED_MAIN_LIB", "PACKAGED_INIT_LIB",
        "PACKAGED_INIT_LD_LIB", "PACKAGED_BOOT_LIB")

    private fun fixture(prefix: String = "a") = fields.mapIndexed { index, key ->
        "$key='$prefix$index'"
    }.joinToString("\n")

    @Test fun independentBuildNamesAreReadFromEachSource() {
        assertEquals("a0", InstallerArchive.parseNames(fixture())["MAIN_BIN_NAME"])
        assertEquals("b0", InstallerArchive.parseNames(fixture("b"))["MAIN_BIN_NAME"])
        assertEquals(".marker", InstallerArchive.parseNames(fixture().replace("BACKUP_CONFIG='a5'", "BACKUP_CONFIG='.marker'"))["BACKUP_CONFIG"])
    }

    @Test fun incompleteAndDuplicateMetadataFailClosed() {
        assertThrows(IOException::class.java) { InstallerArchive.parseNames("MAIN_BIN_NAME='a'") }
        assertThrows(IOException::class.java) { InstallerArchive.parseNames(fixture() + "\nMAIN_BIN_NAME='duplicate'") }
        assertThrows(IOException::class.java) { InstallerArchive.parseNames(fixture() + "\nOTHER_ARCHIVE='duplicate'") }
    }

    @Test fun archivePrefixIsNotBoundToTheLaunchedBuild() {
        val original = InstallerArchive.parseNames(fixture())
        assertEquals(original, InstallerArchive.parseNames(fixture().replace("UDONGE_ARCHIVE", "OTHER_ARCHIVE")))
    }

    @Test fun pathsAndShellSubstitutionAreRejected() {
        for (bad in listOf("../file", "/tmp/file", "a/b", "..", "a'\\n", "\$(id)", "a;b")) {
            assertThrows(IOException::class.java) { InstallerArchive.parseNames(fixture().replace("a0", bad)) }
        }
    }

    @Test fun copyBoundsAreEnforcedForUnknownStreamLengths() {
        val out = ByteArrayOutputStream()
        InstallerArchive.copyLimited(ByteArrayInputStream(byteArrayOf(1, 2)), out, 2)
        assertEquals(2, out.size())
        assertThrows(IOException::class.java) {
            InstallerArchive.copyLimited(ByteArrayInputStream(ByteArray(3)), ByteArrayOutputStream(), 2)
        }
    }

    @Test fun extractionUsesOnlySelectedAbiAndExplicitEntries() {
        val file = File.createTempFile("source-test", ".apk")
        val folder = java.nio.file.Files.createTempDirectory("source-test").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                val entries = mapOf(
                    "assets/util_functions.sh" to fixture("b"),
                    "assets/boot_patch.sh" to "patch", "assets/b3" to "stub", "assets/b4" to "payload",
                    "assets/chromeos/futility" to "tool",
                    "assets/chromeos/kernel_data_key.vbprivk" to "key",
                    "assets/chromeos/kernel.keyblock" to "key",
                    "lib/arm64-v8a/libb6.so" to "selected-main",
                    "lib/arm64-v8a/libb7.so" to "selected-init",
                    "lib/arm64-v8a/libb8.so" to "selected-loader",
                    "lib/arm64-v8a/libb9.so" to "selected-boot",
                    "lib/armeabi-v7a/libb6.so" to "wrong-abi",
                    "../outside" to "must-not-extract",
                )
                entries.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
            }
            ZipFile(file).use { zip ->
                InstallerArchive(zip, "arm64-v8a").extract(folder)
                assertEquals("selected-main", File(folder, "b0").readText())
                assertEquals("selected-boot", File(folder, "source-tool").readText())
                assertThrows(IOException::class.java) { InstallerArchive(zip, "x86_64") }
            }
        } finally {
            file.delete()
            folder.deleteRecursively()
        }
    }
}
