package com.topjohnwu.magisk.core.repository

import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Config.Value.BETA_CHANNEL
import com.topjohnwu.magisk.core.Config.Value.CUSTOM_CHANNEL
import com.topjohnwu.magisk.core.Config.Value.DEBUG_CHANNEL
import com.topjohnwu.magisk.core.Config.Value.DEFAULT_CHANNEL
import com.topjohnwu.magisk.core.Config.Value.STABLE_CHANNEL
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.data.GithubApiServices
import com.topjohnwu.magisk.core.data.RawUrl
import com.topjohnwu.magisk.core.ktx.dateFormat
import com.topjohnwu.magisk.core.model.Release
import com.topjohnwu.magisk.core.model.ReleaseAssets
import com.topjohnwu.magisk.core.model.UpdateInfo
import retrofit2.HttpException
import java.io.IOException

class NetworkService(
    private val raw: RawUrl,
    private val api: GithubApiServices,
) {
    suspend fun fetchUpdate() = safe {
        var info = when (Config.updateChannel) {
            DEFAULT_CHANNEL -> if (BuildConfig.DEBUG) fetchDebugUpdate() else fetchStableUpdate()
            STABLE_CHANNEL -> fetchStableUpdate()
            BETA_CHANNEL -> fetchBetaUpdate()
            DEBUG_CHANNEL -> fetchDebugUpdate()
            CUSTOM_CHANNEL -> fetchCustomUpdate(Config.customChannelUrl)
            else -> throw IllegalArgumentException("invalid update channel")
        }
        if (info.versionCode < Info.env.versionCode &&
            Config.updateChannel == DEFAULT_CHANNEL && !BuildConfig.DEBUG
        ) {
            Config.updateChannel = BETA_CHANNEL
            info = fetchBetaUpdate()
        }
        info
    }

    suspend fun fetchUpdate(version: Int) = safe {
        findRelease { it.versionCode == version }.asInfo()
    }

    private suspend inline fun findRelease(predicate: (Release) -> Boolean): Release? {
        var page = 1
        while (true) {
            val response = api.fetchReleases(page = page)
            val releases = response.body() ?: throw HttpException(response)
            releases.removeAll { !it.tag.startsWith("v") && !it.tag.startsWith("canary") }
            releases.sortByDescending { it.createdTime }
            releases.find(predicate)?.let { return it }
            if (response.headers()["link"]?.contains("rel=\"next\"", true) == true) {
                page += 1
            } else {
                return null
            }
        }
    }

    private inline fun Release?.asInfo(
        selector: (ReleaseAssets) -> Boolean = {
            it.name.endsWith(".apk") && !it.name.contains("debug")
        },
    ): UpdateInfo {
        if (this == null) return UpdateInfo()
        return if (tag.startsWith("v")) {
            UpdateInfo(
                version = tag.drop(1),
                versionCode = versionCode,
                link = assets.first(selector).url,
                note = "## ${dateFormat.format(createdTime)} $name\n\n$body",
            )
        } else {
            UpdateInfo(
                version = name.removePrefix("Magisk ").take(8),
                versionCode = versionCode,
                link = assets.first(selector).url,
                note = "## $name\n\n$body",
            )
        }
    }

    private suspend fun fetchStableUpdate() = api.fetchLatestRelease().asInfo()
    private suspend fun fetchBetaUpdate() = findRelease { true }.asInfo()
    private suspend fun fetchDebugUpdate() = findRelease { true }.asInfo {
        it.name == "app-debug.apk"
    }

    private suspend fun fetchCustomUpdate(url: String): UpdateInfo {
        val info = raw.fetchUpdateJson(url).magisk
        return info.copy(note = raw.fetchString(info.note))
    }

    private inline fun <T> safe(factory: () -> T): T? {
        return try {
            if (Info.isConnected.value == true) factory() else null
        } catch (_: Exception) {
            null
        }
    }

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
