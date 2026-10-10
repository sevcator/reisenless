package com.topjohnwu.magisk.ui.flash

internal object FlashZipCommands {
    fun install(directory: String, archive: String, displayName: String): String =
        "printf '%s\\n' ${quote("- Installing $displayName")}; " +
            "sh ${quote("$directory/update-binary")} dummy 1 ${quote(archive)}; " +
            "EXIT=\$?; " +
            "if [ \$EXIT -ne 0 ]; then echo '! Installation failed'; fi; " +
            "exit \$EXIT"

    private fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
}
