package com.topjohnwu.magisk.core

internal object UdongeRuntimeCommands {
    fun install(root: String, extracted: String, payloadId: String, fileType: String,
                busyboxName: String = "busybox"): String = """
        (
            set -e
            root=${quote(root)}
            rm -rf "${'$'}root/runtime.new"
            mkdir -p "${'$'}root/runtime.new" "${'$'}root/state"
            cp -af ${quote("$extracted/.")} "${'$'}root/runtime.new/"
            for required in service.sh worker.sh hideapps.dex version payload.id; do
                [ -s "${'$'}root/runtime.new/${'$'}required" ] || exit 1
            done
            printf '%s\n' ${quote(payloadId)} > "${'$'}root/runtime.new/payload.id"
            chmod -R 700 "${'$'}root/runtime.new"
            chcon -R u:object_r:system_file:s0 "${'$'}root/runtime.new" 2>/dev/null || true
            for library in "${'$'}root/runtime.new/tee/"*"/libTEESimulator.so"; do
                [ ! -f "${'$'}library" ] || chcon ${quote("u:object_r:$fileType:s0")} "${'$'}library"
            done
            if [ -x "${'$'}root/runtime/keybox_heal.sh" ] && [ -f "${'$'}root/runtime/worker.sh" ]; then
                "${'$'}root/runtime/keybox_heal.sh" stop_daemon
            fi
            runtime="${'$'}root/runtime"
            state="${'$'}root/state"
            boot_id=${'$'}(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true)
            worker_busybox_name=${quote(busyboxName)}
            . "${'$'}root/runtime.new/worker.sh"
            worker_stop_legacy_hunters
            rm -rf "${'$'}root/runtime.old"
            if [ -d "${'$'}root/runtime" ]; then mv "${'$'}root/runtime" "${'$'}root/runtime.old"; fi
            if ! mv "${'$'}root/runtime.new" "${'$'}root/runtime"; then
                if [ -d "${'$'}root/runtime.old" ]; then mv "${'$'}root/runtime.old" "${'$'}root/runtime"; fi
                exit 1
            fi
        )
    """.trimIndent()

    private fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
}
