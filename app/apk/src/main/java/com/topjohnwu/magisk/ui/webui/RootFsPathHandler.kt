package com.topjohnwu.magisk.ui.webui

import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File


internal class RootFsPathHandler(
    webRoot: File,
) : WebViewAssetLoader.PathHandler {
    private val root = webRoot.canonicalFile
    private val rootPrefix = root.path + File.separator

    override fun handle(path: String): WebResourceResponse {
        return try {
            // URLDecoder applies form-encoding rules and turns literal '+' into a space.
            val decoded = Uri.decode(path.removePrefix("/"))
            val file = File(root, decoded).canonicalFile
            if (file.path != root.path && !file.path.startsWith(rootPrefix)) {
                return notFound()
            }

            if (!file.isFile) return notFound()
            WebResourceResponse(mimeType(file.name), null, file.inputStream())
        } catch (_: Exception) {
            notFound()
        }
    }

    private fun notFound() = WebResourceResponse(null, null, null)

    private fun mimeType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "wasm" -> "application/wasm"
                "json" -> "application/json"
                "svg" -> "image/svg+xml"
                else -> "text/plain"
            }
    }
}
