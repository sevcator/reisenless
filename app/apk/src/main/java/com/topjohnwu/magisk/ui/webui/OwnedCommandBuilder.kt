package com.topjohnwu.magisk.ui.webui

internal object OwnedCommandBuilder {
    fun body(command: String, owner: String): String = """
        record=${WebUiCommandBuilder.shellQuote(owner)}
        [ ! -f "${'$'}record.cancel" ] || exit 130
        start=${'$'}(awk '{sub(/^.*\) /, ""); print ${'$'}20}' /proc/${'$'}${'$'}/stat)
        boot=${'$'}(cat /proc/sys/kernel/random/boot_id)
        printf '%s %s %s\n' "${'$'}${'$'}" "${'$'}start" "${'$'}boot" > "${'$'}record"
        trap 'rm -f "${'$'}record" "${'$'}record.cancel"' EXIT
        [ ! -f "${'$'}record.cancel" ] || exit 130
        sh -c ${WebUiCommandBuilder.shellQuote(command)}
    """.trimIndent()

    fun cancel(owner: String): String = """
        record=${WebUiCommandBuilder.shellQuote(owner)}
        read -r pid start boot < "${'$'}record" 2>/dev/null || exit 0
        case "${'$'}pid" in ''|*[!0-9]*) exit 0 ;; esac
        [ "${'$'}boot" = "${'$'}(cat /proc/sys/kernel/random/boot_id)" ] || exit 0
        current() { awk '{sub(/^.*\) /, ""); print ${'$'}20}' "/proc/${'$'}pid/stat" 2>/dev/null; }
        [ "${'$'}(current)" = "${'$'}start" ] || exit 0
        args=${'$'}(tr '\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null)
        case "${'$'}args" in *"${'$'}record"*) ;; *) exit 0 ;; esac
        group=${'$'}(awk '{sub(/^.*\) /, ""); print ${'$'}3}' "/proc/${'$'}pid/stat" 2>/dev/null)
        [ "${'$'}group" = "${'$'}pid" ] || exit 0
        # Freeze the entire group before taking its member/start-time snapshot.
        kill -STOP -- -"${'$'}pid" 2>/dev/null || exit 0
        members=${'$'}(
            ps -A -o PID,PGID | awk -v group="${'$'}pid" '${'$'}2 == group {print ${'$'}1}' |
            while read -r member; do
                stamp=${'$'}(awk '{sub(/^.*\) /, ""); print ${'$'}20}' "/proc/${'$'}member/stat" 2>/dev/null)
                [ -z "${'$'}stamp" ] || printf '%s:%s\n' "${'$'}member" "${'$'}stamp"
            done
        )
        kill -TERM -- -"${'$'}pid" 2>/dev/null || true
        kill -CONT -- -"${'$'}pid" 2>/dev/null || true
        sleep 0.1
        # A surviving member retains the original group identity even if its
        # leader has already exited. Never rely solely on the leader here.
        for entry in ${'$'}members; do
            member=${'$'}{entry%:*}
            stamp=${'$'}{entry#*:}
            actual=${'$'}(awk '{sub(/^.*\) /, ""); print ${'$'}20}' "/proc/${'$'}member/stat" 2>/dev/null)
            group=${'$'}(awk '{sub(/^.*\) /, ""); print ${'$'}3}' "/proc/${'$'}member/stat" 2>/dev/null)
            if [ "${'$'}actual" = "${'$'}stamp" ] && [ "${'$'}group" = "${'$'}pid" ]; then
                kill -KILL -- -"${'$'}pid" 2>/dev/null || true
                break
            fi
        done
    """.trimIndent()
}
