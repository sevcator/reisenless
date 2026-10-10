package com.topjohnwu.magisk.core

internal object UdongeCommands {
    fun enableBackground(root: String): String =
        "mkdir -p '$root/state' && : > '$root/state/background-updates' && " +
            "if [ -f '$root/state/enabled' ] && [ ! -f '$root/state/disabled' ] && " +
            "[ ! -f '$root/state/pending-reboot' ] && [ -x '$root/runtime/keybox_heal.sh' ]; then " +
            "'$root/runtime/keybox_heal.sh' hunt_daemon </dev/null >/dev/null 2>&1 & fi"
}
