package com.topjohnwu.magisk.hideapps

import org.json.JSONArray
import org.json.JSONObject

data class HideAppsRule(
    val useWhitelist: Boolean = false,
    val excludeSystemApps: Boolean = true,
    val packages: Set<String> = emptySet(),
)

data class HideAppsConfig(
    val version: Int = CURRENT_VERSION,
    val enabled: Boolean = false,
    val hiddenPackages: Set<String> = emptySet(),
    val viewerWhitelist: Set<String> = emptySet(),
    val scope: Map<String, HideAppsRule> = emptyMap(),
) {
    fun shouldHide(caller: String?, target: String?, isSystemApp: Boolean): Boolean {
        if (caller == null || target == null || caller == target) return false
        if (caller in NEVER_HIDE || target in NEVER_HIDE) return false

        if (enabled) {
            return caller !in viewerWhitelist && target in hiddenPackages
        }

        val rule = scope[caller] ?: return false
        if (rule.useWhitelist && rule.excludeSystemApps && isSystemApp) return false
        return if (rule.useWhitelist) target !in rule.packages else target in rule.packages
    }

    fun toJson(): String {
        val scopes = JSONObject()
        scope.toSortedMap().forEach { (packageName, rule) ->
            scopes.put(packageName, JSONObject().apply {
                put("useWhitelist", rule.useWhitelist)
                put("excludeSystemApps", rule.excludeSystemApps)
                put("packages", JSONArray(rule.packages.sorted()))
            })
        }
        return JSONObject()
            .put("version", version)
            .put("enabled", enabled)
            .put("hiddenPackages", JSONArray(hiddenPackages.sorted()))
            .put("viewerWhitelist", JSONArray(viewerWhitelist.sorted()))
            .put("scope", scopes)
            .toString()
    }

    fun toRuntimeConfig(
        managerPackage: String,
        systemPackages: Set<String>,
        installedPackages: Set<String> = scope.keys,
        includeCompatibilityMarkers: Boolean = true,
    ): String {
        val safeManager = managerPackage.takeIf(::isPackageName).orEmpty()
        return buildString {
            append("V\t")
            append(HideAppsConstants.RUNTIME_VERSION)
            append('\n')
            if (enabled) {
                // The installed manager package is generated when the app is
                // hidden. Always include that live package, including for
                // configs migrated from the original application ID.
                val hidden = (hiddenPackages.asSequence() + sequenceOf(safeManager))
                    .filter(::isPackageName)
                    .distinct()
                    .sorted()
                    .joinToString(",")
                if (hidden.isEmpty()) return@buildString
                val exempt = sequenceOf(
                    viewerWhitelist.asSequence(),
                    NEVER_HIDE.asSequence(),
                    sequenceOf(safeManager),
                ).flatten()
                    .filter(::isPackageName)
                    .distinct()
                    .sorted()
                    .joinToString(",")
                append("G\t")
                append(safeManager)
                append('\t')
                append(hidden)
                append('\t')
                append(exempt)
                append('\n')
                if (includeCompatibilityMarkers) {
                    installedPackages.asSequence()
                        .filter(::isPackageName)
                        .filter { it != safeManager }
                        .filterNot(viewerWhitelist::contains)
                        .filterNot(NEVER_HIDE::contains)
                        .sorted()
                        .forEach { caller ->
                            // Compatibility marker for an older running core.
                            append("R\t")
                            append(caller)
                            append("\tG\n")
                        }
                }
                return@buildString
            }
            val systems = if (scope.values.any {
                    it.useWhitelist && it.excludeSystemApps
                }) {
                systemPackages.asSequence()
                    .filter(::isPackageName)
                    .sorted()
                    .joinToString(",")
            } else {
                ""
            }
            scope.toSortedMap().forEach { (caller, rule) ->
                if (!isPackageName(caller)) return@forEach
                append("R\t")
                append(caller)
                append('\t')
                append(if (rule.useWhitelist) 'W' else 'B')
                append('\t')
                append(if (rule.excludeSystemApps) '1' else '0')
                append('\t')
                append(safeManager)
                append('\t')
                append(rule.packages.asSequence().filter(::isPackageName).sorted().joinToString(","))
                append('\t')
                if (rule.useWhitelist && rule.excludeSystemApps) append(systems)
                append('\n')
            }
        }
    }

    companion object {
        const val CURRENT_VERSION = 2

        val NEVER_HIDE = setOf(
            "android",
            "android.media",
            "android.uid.system",
            "android.uid.shell",
            "android.uid.systemui",
            "com.android.permissioncontroller",
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.android.providers.settings",
            "com.google.android.providers.media.module",
            "com.google.android.webview",
        )

        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

        private fun isPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

        fun parse(json: String): HideAppsConfig {
            val root = JSONObject(json)
            val version = root.optInt("version", CURRENT_VERSION)
            val scopes = root.optJSONObject("scope") ?: JSONObject()
            val rules = buildMap {
                scopes.keys().forEach { packageName ->
                    val value = scopes.optJSONObject(packageName) ?: return@forEach
                    val packages = value.optJSONArray("packages") ?: JSONArray()
                    put(packageName, HideAppsRule(
                        useWhitelist = value.optBoolean("useWhitelist", false),
                        excludeSystemApps = value.optBoolean("excludeSystemApps", true),
                        packages = buildSet {
                            for (index in 0 until packages.length()) {
                                packages.optString(index).takeIf(String::isNotBlank)?.let(::add)
                            }
                        },
                    ))
                }
            }
            fun JSONObject.stringSet(name: String): Set<String> {
                val values = optJSONArray(name) ?: return emptySet()
                return buildSet {
                    for (index in 0 until values.length()) {
                        values.optString(index).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }

            if (root.has("enabled") || root.has("hiddenPackages") ||
                root.has("viewerWhitelist")) {
                return HideAppsConfig(
                    version = version,
                    enabled = root.optBoolean("enabled", false),
                    hiddenPackages = root.stringSet("hiddenPackages"),
                    viewerWhitelist = root.stringSet("viewerWhitelist"),
                    scope = rules,
                )
            }

            // Migrate the old caller-first blacklist model to the global UI.
            val hidden = rules.values.asSequence()
                .filterNot(HideAppsRule::useWhitelist)
                .flatMap { it.packages.asSequence() }
                .toSet()
            return HideAppsConfig(
                version = CURRENT_VERSION,
                enabled = rules.isNotEmpty(),
                hiddenPackages = hidden,
            )
        }
    }
}
