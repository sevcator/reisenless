package com.topjohnwu.magisk.core.tasks

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile

/** Reads literal build metadata, never evaluates shell assignments. */
internal class InstallerArchive(private val zip: ZipFile, abi: String) {
    val util = readText("assets/util_functions.sh")
    val names = parseNames(util)
    val libraries = linkedMapOf(
        "PACKAGED_MAIN_LIB" to name("MAIN_BIN_NAME"),
        "PACKAGED_INIT_LIB" to "minit",
        "PACKAGED_INIT_LD_LIB" to name("INIT_LD_NAME"),
        "PACKAGED_BOOT_LIB" to "source-tool",
    ).mapKeys { "lib/$abi/lib${name(it.key)}.so" }
    val assets = listOf(name("STUB_NAME"), name(archiveKey),
        "boot_patch.sh", "util_functions.sh", "chromeos/futility",
        "chromeos/kernel_data_key.vbprivk", "chromeos/kernel.keyblock")

    init {
        val entries = zip.entries().asSequence().toList()
        if (entries.size > 20000 || entries.map { it.name }.distinct().size != entries.size)
            throw IOException("invalid or duplicate archive entries")
        for (entry in libraries.keys + assets.map { "assets/$it" }) {
            val item = zip.getEntry(entry) ?: throw IOException("missing payload entry: $entry")
            if (item.isDirectory || item.size !in 1..MAX_ENTRY)
                throw IOException("invalid payload entry: $entry")
        }
        if (libraries.values.distinct().size != libraries.size ||
            (libraries.values + assets).distinct().size != libraries.size + assets.size)
            throw IOException("colliding payload filenames")
        if ((libraries.values + assets).any { it in setOf("boot.img", "new-boot.img", "config", "stock_boot.img") })
            throw IOException("reserved payload filename")
    }

    fun name(key: String): String = names[key] ?: throw IOException("missing build metadata: $key")

    fun readText(path: String): String {
        val entry = zip.getEntry(path) ?: throw IOException("missing payload entry: $path")
        return zip.getInputStream(entry).use { stream ->
            val out = java.io.ByteArrayOutputStream()
            copyLimited(stream, out, 512 * 1024)
            out.toString(Charsets.UTF_8.name())
        }
    }

    fun extract(directory: File) {
        for ((entry, dest) in libraries + assets.associate { "assets/$it" to it }) {
            val target = File(directory, dest)
            if (!target.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                throw IOException("invalid extraction path")
            target.parentFile?.mkdirs()
            zip.getInputStream(zip.getEntry(entry)).use { input ->
                target.outputStream().use { copyLimited(input, it, MAX_ENTRY) }
            }
        }
    }

    companion object {
        private const val MAX_ENTRY = 64L * 1024 * 1024
        // The archive-variable prefix is build-specific. Normalize the one archive
        // declaration rather than embedding a prefix rewritten by APK branding/R8.
        private const val archiveKey = "PAYLOAD_ARCHIVE"
        private val keys = setOf("MAIN_BIN_NAME", "INIT_LD_NAME", "RAMDISK_NAME", "STUB_NAME",
            archiveKey, "BACKUP_CONFIG", "PACKAGED_MAIN_LIB", "PACKAGED_INIT_LIB",
            "PACKAGED_INIT_LD_LIB", "PACKAGED_BOOT_LIB")

        fun parseNames(script: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            for (line in script.lineSequence()) {
                val rawKey = line.substringBefore('=')
                val key = if (rawKey.endsWith("_ARCHIVE") && !rawKey.startsWith("LEGACY_")) archiveKey else rawKey
                if (key !in keys) continue
                val match = Regex("([A-Z_]+)='(\\.?[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79})'")
                    .matchEntire(line) ?: throw IOException("invalid build metadata: $key")
                val value = match.groupValues[2]
                if (value.contains("..") || result.put(key, value) != null)
                    throw IOException("ambiguous build metadata: $key")
            }
            if (result.keys != keys) throw IOException("unsupported apk: missing metadata ${keys - result.keys}")
            return result
        }

        fun copyLimited(input: InputStream, output: OutputStream, limit: Long) {
            val buffer = ByteArray(65536)
            var total = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw IOException("input exceeds supported size")
                output.write(buffer, 0, count)
            }
        }
    }
}
