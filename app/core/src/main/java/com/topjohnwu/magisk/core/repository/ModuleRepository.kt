package com.topjohnwu.magisk.core.repository

import com.topjohnwu.magisk.core.model.module.OnlineModule
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

data class TrustedRepository(
    val name: String,
    val url: String,
    val description: String = "",
    val modulesCount: Int? = null,
)

data class RepositoryModule(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val version: String,
    val versionCode: Int,
    val zipUrl: String,
    val notesUrl: String,
) {
    fun asOnlineModule() = OnlineModule(
        id = id,
        name = name,
        version = version,
        versionCode = versionCode,
        zipUrl = zipUrl,
        changelog = notesUrl,
    )
}

data class RepositoryCandidate(
    val id: String,
    val name: String = id,
    val author: String = "",
    val description: String = "",
    val version: String = "",
    val versionCode: Int = 0,
    val zipUrl: String = "",
    val notesUrl: String = "",
    val propUrl: String = "",
)

/** Reads repositories generated for MMRL/MRepo. */
class ModuleRepository(private val network: NetworkService) {

    suspend fun loadSources(rawSources: String): List<RepositoryCandidate> = coroutineScope {
        rawSources.lineSequence()
            .map(String::trim)
            .mapNotNull(::normalizeRepositoryUrl)
            .distinct()
            .take(MAX_SOURCES)
            .map { source -> async { runCatching { loadSource(source) }.getOrDefault(emptyList()) } }
            .toList()
            .awaitAll()
            .flatten()
            .filter { it.id.isNotBlank() && (it.zipUrl.isNotBlank() || it.propUrl.isNotBlank()) }
            .distinctBy { it.id.lowercase() to it.zipUrl }
    }

    suspend fun loadTrustedRepositories(): List<TrustedRepository> = runCatching {
        parseTrustedRepositories(network.fetchString(TRUSTED_REPOSITORIES_URL))
            .ifEmpty { fallbackTrustedRepositories }
    }.getOrDefault(fallbackTrustedRepositories)

    suspend fun resolve(candidates: List<RepositoryCandidate>): List<RepositoryModule> =
        coroutineScope {
            candidates.take(MAX_RESULTS).map { candidate ->
                async { runCatching { resolve(candidate) }.getOrNull() }
            }.awaitAll().filterNotNull()
        }

    private suspend fun loadSource(source: String): List<RepositoryCandidate> {
        val body = network.fetchString(source)
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{")) return emptyList()
        val root = JSONObject(trimmed)
        // MMRL repositories expose their catalogue as an object containing
        // a modules array. Reject generic JSON and direct module/GitHub URLs.
        if (root.optJSONArray("modules") == null) return emptyList()
        return parseJsonObject(root)
    }

    private fun parseJsonObject(root: JSONObject): List<RepositoryCandidate> =
        parseJsonArray(root.getJSONArray("modules"))

    private fun parseJsonArray(array: JSONArray): List<RepositoryCandidate> = buildList {
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let(::parseEntry)?.let(::add)
        }
    }

    private fun parseEntry(entry: JSONObject): RepositoryCandidate? {
        val metadata = entry.optJSONObject("metadata")
        val version = newestVersion(entry)
        fun string(vararg keys: String): String {
            for (key in keys) {
                entry.optString(key).takeIf { it.isNotBlank() }?.let { return it }
                metadata?.optString(key)?.takeIf { it.isNotBlank() }?.let { return it }
                version?.optString(key)?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return ""
        }
        fun int(vararg keys: String): Int {
            for (key in keys) {
                if (entry.has(key)) return entry.optInt(key)
                if (metadata?.has(key) == true) return metadata.optInt(key)
                if (version?.has(key) == true) return version.optInt(key)
            }
            return 0
        }

        val id = string("id", "moduleId", "module_id")
        if (id.isBlank()) return null
        return RepositoryCandidate(
            id = id,
            name = string("name").ifBlank { id },
            author = string("author"),
            description = string("description", "desc"),
            version = string("version"),
            versionCode = int("versionCode", "version_code"),
            zipUrl = string("zipUrl", "zip_url", "download"),
            notesUrl = string("notesUrl", "notes_url", "changelog"),
            propUrl = string("propUrl", "prop_url"),
        )
    }

    private fun newestVersion(entry: JSONObject): JSONObject? {
        entry.optJSONObject("latest")?.let { return it }
        val versions = entry.optJSONArray("versions") ?: return null
        var newest: JSONObject? = null
        var newestCode = Int.MIN_VALUE
        for (index in 0 until versions.length()) {
            val item = versions.optJSONObject(index) ?: continue
            val code = item.optInt("versionCode", item.optInt("version_code", index))
            if (code >= newestCode) {
                newest = item
                newestCode = code
            }
        }
        return newest
    }

    private suspend fun resolve(candidate: RepositoryCandidate): RepositoryModule? {
        val values = if (candidate.propUrl.isNotBlank()) {
            runCatching { parseProperties(network.fetchString(candidate.propUrl)) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        val id = values["id"].orEmpty().ifBlank { candidate.id }
        val zipUrl = candidate.zipUrl.ifBlank {
            values["zipUrl"].orEmpty().ifBlank { values["zip_url"].orEmpty() }
        }
        if (id.isBlank() || zipUrl.isBlank()) return null
        return RepositoryModule(
            id = id,
            name = values["name"].orEmpty().ifBlank { candidate.name.ifBlank { id } },
            author = values["author"].orEmpty().ifBlank { candidate.author },
            description = values["description"].orEmpty().ifBlank { candidate.description },
            version = values["version"].orEmpty().ifBlank { candidate.version.ifBlank { "unknown" } },
            versionCode = values["versionCode"]?.toIntOrNull()
                ?: candidate.versionCode,
            zipUrl = zipUrl,
            notesUrl = candidate.notesUrl.ifBlank {
                values["changelog"].orEmpty()
            },
        )
    }

    private fun candidateFromProperties(
        id: String,
        values: Map<String, String>,
        zipUrl: String,
        notesUrl: String,
        propUrl: String,
    ) = RepositoryCandidate(
        id = id,
        name = values["name"].orEmpty().ifBlank { id },
        author = values["author"].orEmpty(),
        description = values["description"].orEmpty(),
        version = values["version"].orEmpty(),
        versionCode = values["versionCode"]?.toIntOrNull() ?: 0,
        zipUrl = zipUrl,
        notesUrl = notesUrl,
        propUrl = propUrl,
    )

    private fun parseProperties(body: String): Map<String, String> = buildMap {
        body.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEach
            val separator = trimmed.indexOf('=')
            if (separator <= 0) return@forEach
            put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim())
        }
    }

    private fun parseTrustedRepositories(body: String): List<TrustedRepository> {
        val array = JSONArray(body)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = item.optString("url").trim()
                if (normalizeRepositoryUrl(url) == null) continue
                add(
                    TrustedRepository(
                        name = item.optString("name").ifBlank { url },
                        url = url.trimEnd('/') + "/",
                        description = item.optString("description"),
                        modulesCount = item.optInt("modules_count").takeIf { it > 0 },
                    )
                )
            }
        }.distinctBy { it.url.lowercase() }
    }

    companion object {
        private const val MAX_SOURCES = 20
        private const val MAX_RESULTS = 50
        private const val TRUSTED_REPOSITORIES_URL =
            "https://mmrl.dev/api/repositories.json"

        fun normalizeRepositoryUrl(raw: String): String? {
            val source = raw.trim()
            if (!source.startsWith("https://", ignoreCase = true)) return null
            val clean = source.substringBefore('#').substringBefore('?').trimEnd('/')
            return when {
                clean.endsWith("/json/modules.json", ignoreCase = true) -> clean
                clean.endsWith("/modules.json", ignoreCase = true) -> clean
                else -> "$clean/json/modules.json"
            }
        }

        private val fallbackTrustedRepositories = listOf(
            TrustedRepository("Googlers Magisk Repo", "https://gr.dergoogler.com/gmr/"),
            TrustedRepository(
                "Magisk Modules Alternative Repo",
                "https://magisk-modules-alt-repo.github.io/json-v2/",
            ),
            TrustedRepository(
                "IzzyOnDroid Magisk Repository",
                "https://apt.izzysoft.de/magisk/",
            ),
            TrustedRepository(
                "Magisk Modules Rikj000 Repo",
                "https://rikj000.github.io/Magisk-Modules-Rikj000-Repo/",
            ),
            TrustedRepository(
                "Celica Magisk Modules Repo",
                "https://natsumerinchan.github.io/celica-magisk-modules-repo/",
            ),
            TrustedRepository(
                "Magisk Font Collection Repository",
                "https://codeberg.org/fruitsnack/magisk-font-repo/raw/branch/main/",
            ),
            TrustedRepository("LelouBil Magisk Repo", "https://leloubil.github.io/magisk-repo/"),
            TrustedRepository("ZG089’s modules repo", "https://zguation-projects.github.io/ZG-R/"),
            TrustedRepository("Rem01 Projects", "https://mrepo.rem01gaming.dev/"),
            TrustedRepository(
                "SSMG4’s Magisk Modules Repository",
                "https://ssmg4.github.io/SSR/",
            ),
            TrustedRepository("Modules Repo by Julia", "https://juliazero.github.io/mrbj/"),
        )
    }
}
