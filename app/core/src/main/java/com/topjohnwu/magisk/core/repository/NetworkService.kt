package com.topjohnwu.magisk.core.repository

import com.topjohnwu.magisk.core.data.RawUrl
import retrofit2.HttpException
import java.io.IOException

class NetworkService(
    private val raw: RawUrl,
) {
    private inline fun <T> wrap(factory: () -> T): T {
        return try {
            factory()
        } catch (e: HttpException) {
            throw IOException(e)
        }
    }

    suspend fun fetchFile(url: String) = wrap { raw.fetchFile(url) }
    suspend fun fetchString(url: String) = wrap { raw.fetchString(url) }
    suspend fun fetchModuleJson(url: String) = wrap { raw.fetchModuleJson(url) }
}
