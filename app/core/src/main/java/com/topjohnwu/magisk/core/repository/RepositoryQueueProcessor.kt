package com.topjohnwu.magisk.core.repository

import androidx.core.net.toUri
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.download.DownloadNotifier
import com.topjohnwu.magisk.core.download.DownloadProcessor
import com.topjohnwu.magisk.core.tasks.FlashZip
import com.topjohnwu.magisk.core.utils.MediaStoreUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class RepositoryQueueProgress(
    val position: Int,
    val total: Int,
    val module: RepositoryModule,
    val installing: Boolean,
)

data class RepositoryQueueResult(
    val completed: Int,
    val total: Int,
    val failedModule: RepositoryModule? = null,
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
            onProgress(
                RepositoryQueueProgress(
                    position = index + 1,
                    total = modules.size,
                    module = module,
                    installing = install,
                )
            )
            val success = withContext(Dispatchers.IO) {
                try {
                    if (install) installModule(module, index) else downloadModule(module)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
            }
            if (!success) {
                return RepositoryQueueResult(index, modules.size, module)
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

    private suspend fun installModule(module: RepositoryModule, index: Int): Boolean {
        val queueDir = File(AppContext.cacheDir, "repository-queue").apply { mkdirs() }
        val archive = File(queueDir, "$index.zip")
        return try {
            network.fetchFile(module.zipUrl).use { body ->
                archive.outputStream().use { destination ->
                    downloadProcessor.handleModule(body.byteStream(), destination)
                }
            }
            FlashZip(archive.toUri(), mutableListOf(), mutableListOf()).exec()
        } finally {
            archive.delete()
            if (queueDir.list().isNullOrEmpty()) queueDir.delete()
        }
    }
}
