import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.dsl.ApkSigningConfig
import com.android.builder.internal.packaging.IncrementalPackager
import com.android.ide.common.signing.KeystoreHelper
import com.android.tools.build.apkzlib.sign.SigningExtension
import com.android.tools.build.apkzlib.sign.SigningOptions
import com.android.tools.build.apkzlib.zfile.ZFiles
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileOptions
import com.android.tools.build.apkzlib.zip.CompressionMethod
import com.android.tools.build.apkzlib.zip.AlignmentRules
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.jar.JarFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private fun ByteArray.replaceAll(from: ByteArray, to: ByteArray): Int {
    require(from.size == to.size)
    var count = 0
    var offset = 0
    while (offset <= size - from.size) {
        var matches = true
        for (index in from.indices) {
            if (this[offset + index] != from[index]) {
                matches = false
                break
            }
        }
        if (matches) {
            to.copyInto(this, offset)
            count++
            offset += from.size
        } else {
            offset++
        }
    }
    return count
}

private fun rebuildDex(data: ByteArray): ByteArray {
    // Renaming changes string/type/member ordering. Recalculating the checksum
    // alone leaves an invalid DEX. Re-intern every definition and reference so
    // the writer sorts all ID tables and remaps instructions and annotations.
    val dex = DexBackedDexFile(null, data)
    val store = MemoryDataStore(data.size)
    DexPool.writeTo(store, dex)
    return store.data
}

private fun ByteArray.rewriteHelperJar(replacements: List<Pair<ByteArray, ByteArray>>): ByteArray {
    val output = ByteArrayOutputStream(size)
    ZipInputStream(ByteArrayInputStream(this)).use { input ->
        ZipOutputStream(output).use { zip ->
            while (true) {
                val source = input.nextEntry ?: break
                var contents = input.readBytes()
                val changed = replacements.sumOf { (from, to) -> contents.replaceAll(from, to) }
                if (changed > 0 && source.name.matches(Regex("classes(?:\\d+)?\\.dex"))) {
                    contents = rebuildDex(contents)
                }
                val target = ZipEntry(source.name).apply {
                    method = source.method
                    time = 0L
                    if (method == ZipEntry.STORED) {
                        size = contents.size.toLong()
                        compressedSize = contents.size.toLong()
                        crc = CRC32().apply { update(contents) }.value
                    }
                }
                zip.putNextEntry(target)
                zip.write(contents)
                zip.closeEntry()
            }
        }
    }
    return output.toByteArray()
}

private fun ZFile.rewriteVisibleNamespaces(namespaces: Map<String, String>) {
    val ordered = namespaces.entries
        .filter { it.key != it.value }
        .sortedByDescending { it.key.length }
    if (ordered.isEmpty()) return
    ordered.forEach { (source, target) ->
        require(target.length == source.length) {
            "Randomized namespace must be ${source.length} characters: $source"
        }
    }
    val replacements = ordered.flatMap { (source, target) ->
        val sourcePath = source.replace('.', '/')
        val targetPath = target.replace('.', '/')
        listOf(
            source.toByteArray() to target.toByteArray(),
            sourcePath.toByteArray() to targetPath.toByteArray(),
            source.toByteArray(Charsets.UTF_16LE) to target.toByteArray(Charsets.UTF_16LE),
            sourcePath.toByteArray(Charsets.UTF_16LE) to targetPath.toByteArray(Charsets.UTF_16LE),
            source.toByteArray(Charsets.UTF_16BE) to target.toByteArray(Charsets.UTF_16BE),
            sourcePath.toByteArray(Charsets.UTF_16BE) to targetPath.toByteArray(Charsets.UTF_16BE),
        )
    }
    entries().toList().forEach { entry ->
        val name = entry.centralDirectoryHeader.name
        val mayCompress = entry.centralDirectoryHeader.compressionInfoWithWait.method !=
            CompressionMethod.STORE
        val rewrittenName = ordered.fold(name) { value, (source, target) ->
            value.replace(source, target).replace(
                source.replace('.', '/'), target.replace('.', '/')
            )
        }
        var contents = entry.read()
        var changed = replacements.sumOf { (from, to) -> contents.replaceAll(from, to) }
        if (name == "assets/main.jar") {
            contents = contents.rewriteHelperJar(replacements)
            changed++
        }
        if (changed > 0 || rewrittenName != name) {
            if (name.matches(Regex("classes(?:\\d+)?\\.dex"))) {
                contents = rebuildDex(contents)
            }
            if (rewrittenName != name) {
                entry.delete()
            }
            add(rewrittenName, ByteArrayInputStream(contents), mayCompress)
        }
    }
}

private fun ZFile.rewriteVisibleBranding(
    brands: Map<String, String>,
    globalBrands: Map<String, String>,
) {
    val ordered = (brands + globalBrands).entries
        .filter { it.key != it.value }
        .sortedByDescending { it.key.length }
    ordered.forEach { (source, target) ->
        require(source.length == target.length) {
            "Replacement must preserve encoded length: $source"
        }
    }
    entries().toList().forEach { entry ->
        val name = entry.centralDirectoryHeader.name
        val mayCompress = entry.centralDirectoryHeader.compressionInfoWithWait.method !=
            CompressionMethod.STORE
        val rewrittenName = ordered.fold(name) { value, (source, target) ->
            value.replace(source, target)
        }
        val appVisible = name == "resources.arsc" ||
            name.matches(Regex("classes(?:\\d+)?\\.dex")) ||
            // Data Binding layout tags must match their rewritten DEX strings.
            (name.startsWith("res/") && name.endsWith(".xml")) ||
            name == "AndroidManifest.xml"
        val selected = if (appVisible) ordered else globalBrands.entries
        val replacements = selected.flatMap { (source, target) ->
            listOf(
                source.toByteArray() to target.toByteArray(),
                source.toByteArray(Charsets.UTF_16LE) to target.toByteArray(Charsets.UTF_16LE),
                source.toByteArray(Charsets.UTF_16BE) to target.toByteArray(Charsets.UTF_16BE),
            )
        }
        var contents = entry.read()
        // The user-facing product/status names are not internal identities.
        // Protect only complete Android string-pool values, not substrings in
        // resource keys, class names, paths, scripts, or other text.
        // All product and component names are internal identities in a
        // release build. Let the branding map rewrite their visible strings
        // too; only the app label remains intentionally user-facing.
        val displayLabels = emptyList<Pair<ByteArray, ByteArray>>()
        val protectedLabels = displayLabels.filter { (label, placeholder) ->
            require(contents.replaceAll(placeholder, placeholder) == 0) {
                "Reserved display-label placeholder in resource table"
            }
            contents.replaceAll(label, placeholder) > 0
        }
        val changed = replacements.sumOf { (from, to) -> contents.replaceAll(from, to) }
        protectedLabels.forEach { (label, placeholder) -> contents.replaceAll(placeholder, label) }
        if (changed > 0 || rewrittenName != name) {
            if (name.matches(Regex("classes(?:\\d+)?\\.dex"))) contents = rebuildDex(contents)
            if (rewrittenName != name) {
                entry.delete()
            }
            add(rewrittenName, ByteArrayInputStream(contents), mayCompress)
        }
    }
}

abstract class TransformApkTask : DefaultTask() {
    @get:Input
    abstract val signingConfig: Property<ApkSigningConfig>

    @get:Input
    abstract val namespaceMappings: MapProperty<String, String>

    @get:Input
    abstract val brandingMappings: MapProperty<String, String>

    @get:Input
    abstract val globalBrandingMappings: MapProperty<String, String>

    @get:InputFiles
    abstract val apkFolder: DirectoryProperty

    @get:OutputDirectory
    abstract val outFolder: DirectoryProperty

    @get:Internal
    abstract val transformations: ListProperty<(ZFile) -> Unit>

    @get:Internal
    abstract val transformationRequest: Property<ArtifactTransformationRequest<TransformApkTask>>

    @TaskAction
    fun taskAction() = transformationRequest.get().submit(this) { artifact ->
        val inFile = File(artifact.outputFile)
        val outFile = outFolder.file(inFile.name).get().asFile

        val config = signingConfig.get()
        val info = KeystoreHelper.getCertificateInfo(
            config.storeType,
            config.storeFile,
            config.storePassword,
            config.keyPassword,
            config.keyAlias
        )

        val signingOptions = SigningOptions.builder()
            .setMinSdkVersion(0)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setKey(info.key)
            .setCertificates(info.certificate)
            .setValidation(SigningOptions.Validation.ASSUME_INVALID)
            .build()
        val options = ZFileOptions().apply {
            noTimestamps = true
            autoSortFiles = true
            alignmentRule = AlignmentRules.compose(
                AlignmentRules.constantForSuffix(".so", 16 * 1024),
                AlignmentRules.constant(4),
            )
            coverEmptySpaceUsingExtraField = true
        }
        outFile.parentFile?.mkdirs()
        inFile.copyTo(outFile, overwrite = true)
        ZFiles.apk(outFile, options).use {
            SigningExtension(signingOptions).register(it)
            it.get(IncrementalPackager.APP_METADATA_ENTRY_PATH)?.delete()
            it.get(IncrementalPackager.VERSION_CONTROL_INFO_ENTRY_PATH)?.delete()
            it.get(JarFile.MANIFEST_NAME)?.delete()
            it.get("assets/PublicSuffixDatabase.list")?.delete()
            // Profiles contain DEX indexes from before the namespace rewrite.
            it.get("assets/dexopt/baseline.prof")?.delete()
            it.get("assets/dexopt/baseline.profm")?.delete()
            it.rewriteVisibleNamespaces(namespaceMappings.get())
            it.rewriteVisibleBranding(
                brandingMappings.get(),
                globalBrandingMappings.get(),
            )
            transformations.get().forEach { transform -> transform(it) }
            // Request extraction in the manifest while retaining stored,
            // page-aligned entries in the signed release archive.
            it.entries().toList().filter { entry ->
                entry.centralDirectoryHeader.name.matches(Regex("lib/[^/]+/[^/]+\\.so"))
            }.forEach { entry ->
                it.add(entry.centralDirectoryHeader.name, ByteArrayInputStream(entry.read()), false)
            }
            it.realign()
        }

        outFile
    }
}
