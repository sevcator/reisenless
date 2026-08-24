package com.topjohnwu.magisk.core.repository

import androidx.core.net.toUri
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.download.DownloadNotifier
import com.topjohnwu.magisk.core.download.DownloadProcessor
import com.topjohnwu.magisk.core.tasks.FlashZip
import com.topjohnwu.magisk.core.utils.MediaStoreUtils
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

data class RepositoryQueueProgress(
    val position: Int,
    val total: Int,
    val module: RepositoryModule,
    val installing: Boolean,
    val elapsedSeconds: Int = 0,
    val detail: String = "",
)

data class RepositoryQueueResult(
    val completed: Int,
    val total: Int,
    val failedModule: RepositoryModule? = null,
    val failureDetail: String = "",
) {
    val successful get() = completed == total
}

/** Downloads or installs repository modules strictly in the selected order. */
class RepositoryQueueProcessor(
    private val network: NetworkService,
) {
    private val downloadProcessor = DownloadProcessor(object : DownloadNotifier {
        override val context get() = AppContext
        override fun notifyUpdate(
            id: Int,
            editor: (android.app.Notification.Builder) -> Unit,
        ) = Unit
    })

    suspend fun process(
        modules: List<RepositoryModule>,
        install: Boolean,
        onProgress: (RepositoryQueueProgress) -> Unit = {},
    ): RepositoryQueueResult {
        modules.forEachIndexed { index, module ->
            val progress = RepositoryQueueProgress(
                position = index + 1,
                total = modules.size,
                module = module,
                installing = install,
            )
            onProgress(progress)
            val detail = AtomicReference("")
            val success = coroutineScope {
                val operation = async(Dispatchers.IO) {
                    try {
                        if (install) {
                            installModule(module, index) { line -> detail.set(line) }
                        } else {
                            downloadModule(module)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                }
                var elapsed = 0
                while (!operation.isCompleted) {
                    delay(PROGRESS_INTERVAL_MILLIS)
                    elapsed++
                    if (!operation.isCompleted) {
                        onProgress(
                            progress.copy(
                                elapsedSeconds = elapsed,
                                detail = detail.get(),
                            )
                        )
                    }
                }
                operation.await()
            }
            if (!success) {
                return RepositoryQueueResult(
                    completed = index,
                    total = modules.size,
                    failedModule = module,
                    failureDetail = detail.get(),
                )
            }
        }
        return RepositoryQueueResult(modules.size, modules.size)
    }

    private suspend fun downloadModule(module: RepositoryModule): Boolean {
        val destination = MediaStoreUtils.getFile(module.asOnlineModule().downloadFilename)
        network.fetchFile(module.zipUrl).use { body ->
            downloadProcessor.handleModule(body.byteStream(), destination.uri)
        }
        return true
    }

    private suspend fun installModule(
        module: RepositoryModule,
        index: Int,
        onOutput: (String) -> Unit,
    ): Boolean {
        val queueDir = File(AppContext.cacheDir, "repository-queue").apply { mkdirs() }
        val archive = File(queueDir, "$index.zip")
        return try {
            network.fetchFile(module.zipUrl).use { body ->
                archive.outputStream().use { destination ->
                    downloadProcessor.handleModule(body.byteStream(), destination)
                }
            }
            val moduleId = readModuleId(archive) ?: return false
            val active = "${Const.MODULE_PATH}/$moduleId"
            val pending = "${Const.SECURE_DIR}/modules_update/$moduleId"
            val hadActiveModule = Shell.cmd("[ -f '$active/module.prop' ]").exec().isSuccess
            val console = object : CallbackList<String>(
                Collections.synchronizedList(mutableListOf<String>())
            ) {
                override fun onAddElement(line: String?) {
                    line?.trim()
                        ?.takeIf { it.isNotBlank() && it != "! installation failed" }
                        ?.let(onOutput)
                }
            }
            val logs = Collections.synchronizedList(mutableListOf<String>())
            val installed = FlashZip(
                archive.toUri(),
                console,
                logs,
                INSTALL_TIMEOUT_SECONDS,
            ).exec()
            if (!installed || !verifyInstalled(moduleId)) {
                rollbackFailedInstall(active, pending, hadActiveModule)
                return false
            }
            true
        } finally {
            archive.delete()
            if (queueDir.list().isNullOrEmpty()) queueDir.delete()
        }
    }

    private fun readModuleId(archive: File): String? = runCatching {
        ZipFile.Builder().setFile(archive).get().use { zip ->
            val prop = zip.getEntry("module.prop") ?: return null
            zip.getInputStream(prop).bufferedReader().useLines { lines ->
                lines.map(String::trim)
                    .firstOrNull { it.startsWith("id=") }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.takeIf(MODULE_ID::matches)
            }
        }
    }.getOrNull()

    private fun verifyInstalled(moduleId: String): Boolean {
        val active = "${Const.MODULE_PATH}/$moduleId/module.prop"
        val pending = "${Const.SECURE_DIR}/modules_update/$moduleId/module.prop"
        return Shell.cmd("[ -f '$active' ] || [ -f '$pending' ]").exec().isSuccess
    }

    private fun rollbackFailedInstall(active: String, pending: String, hadActiveModule: Boolean) {
        val commands = mutableListOf("rm -rf '$pending'")
        if (!hadActiveModule) commands.add("rm -rf '$active'")
        Shell.cmd(*commands.toTypedArray()).exec()
    }

    private companion object {
        val MODULE_ID = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
        const val PROGRESS_INTERVAL_MILLIS = 1_000L
        const val INSTALL_TIMEOUT_SECONDS = 10 * 60L
    }
}
