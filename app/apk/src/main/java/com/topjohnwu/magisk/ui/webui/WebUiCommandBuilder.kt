package com.topjohnwu.magisk.ui.webui

internal object WebUiCommandBuilder {
    private val moduleIdPattern = Regex("[A-Za-z0-9._-]+")
    private val environmentKeyPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun isValidModuleId(moduleId: String): Boolean =
        moduleId != "." && moduleId != ".." && moduleIdPattern.matches(moduleId)

    fun isInternalUrl(scheme: String?, host: String?, port: Int): Boolean =
        scheme.equals("https", ignoreCase = true) &&
            host.equals("appassets.androidplatform.net", ignoreCase = true) &&
            (port == -1 || port == 443)

    fun appendArguments(command: String, arguments: List<String>): String = buildString {
        append(command)
        for (argument in arguments) {
            append(' ').append(shellQuote(argument))
        }
    }

    fun withOptions(command: String, cwd: String?, environment: Map<String, String>): String = buildString {
        cwd?.takeIf { it.isNotBlank() }?.let {
            append("cd ").append(shellQuote(it)).append(" && ")
        }
        environment.forEach { (key, value) ->
            if (environmentKeyPattern.matches(key)) {
                append("export ").append(key).append('=').append(shellQuote(value)).append(" && ")
            }
        }
        append(command)
    }

    fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
