package com.topjohnwu.magisk.ui.webui

internal object WebUiUserAgent {

    fun normalize(value: String): String = buildString(value.length) {
        for (character in value) {
            append(if (character < ' ' || character == '\u007f') ' ' else character)
        }
    }.trim()
}
