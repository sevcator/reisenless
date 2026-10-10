#!/system/bin/sh

worker_current_pid() {

    local rest
    worker_pid=$$
    read -r worker_pid rest < /proc/self/stat 2>/dev/null || worker_pid=$$
}

worker_process_start() {
    case "$1" in ''|*[!0-9]*) return 1 ;; esac
    awk '{sub(/^.*\) /, ""); print $20}' "/proc/$1/stat" 2>/dev/null
}

worker_is_current() {
    local actual
    [ -n "$1" ] && [ -n "$2" ] && [ -n "$3" ] && [ "$3" = "$boot_id" ] || return 1
    actual="$(worker_process_start "$1")"
    [ -n "$actual" ] && [ "$actual" = "$2" ] && kill -0 "$1" 2>/dev/null
}

worker_guard() {
    local result worker_has_guard=0

    if command -v flock >/dev/null 2>&1; then
        exec 9> "$root/.worker-guard"
        flock -x 9 9>&9 || { exec 9>&-; return 1; }
        worker_has_guard=1
        "$@"
        result=$?
        flock -u 9 9>&9
        exec 9>&-
        return "$result"
    fi
    "$@"
}

worker_acquire() {
    worker_guard worker_acquire_locked "$1"
}

worker_acquire_locked() {
    local dir owner start boot inode
    dir="$root/.$1-lock"
    worker_current_pid
    if ! mkdir "$dir" 2>/dev/null; then
        inode="$(stat -c %i "$dir" 2>/dev/null)"
        [ -n "$inode" ] || return 1
        owner="$(cat "$dir/pid" 2>/dev/null)"
        start="$(cat "$dir/start" 2>/dev/null)"
        boot="$(cat "$dir/boot" 2>/dev/null)"
        worker_is_current "$owner" "$start" "$boot" && return 1

        if [ -z "$owner" ] || [ -z "$start" ] || [ -z "$boot" ]; then
            local age now modified
            now="$(date +%s)"
            modified="$(stat -c %Y "$dir" 2>/dev/null)"
            [ -n "$modified" ] || return 1
            age=$((now - modified))
            [ "$age" -ge 10 ] || return 1
        fi
        worker_retire "$1" "$dir" "$inode" "$owner" "$start" || return 1
        mkdir "$dir" 2>/dev/null || return 1
    fi
    start="$(worker_process_start "$worker_pid")"
    if [ -z "$start" ] || [ -z "$boot_id" ]; then
        rmdir "$dir" 2>/dev/null
        return 1
    fi
    printf '%s\n' "$worker_pid" > "$dir/pid"
    printf '%s\n' "$start" > "$dir/start"
    printf '%s\n' "$boot_id" > "$dir/boot"
}

worker_retire() {
    local retired

    retired="$root/.$1-retired-$boot_id-$3-${4:-incomplete}-${5:-incomplete}"
    : > "$2/.retired" || return 1
    mv -T "$2" "$retired" 2>/dev/null
}

worker_release() {
    worker_guard worker_release_locked "$1"
}

worker_release_locked() {
    local dir inode owner start
    dir="$root/.$1-lock"
    worker_current_pid
    [ "$(cat "$dir/pid" 2>/dev/null)" = "$worker_pid" ] || return 0
    [ "$(cat "$dir/start" 2>/dev/null)" = "$(worker_process_start "$worker_pid")" ] || return 0
    [ "$(cat "$dir/boot" 2>/dev/null)" = "$boot_id" ] || return 0
    if [ "${worker_has_guard:-0}" = 1 ]; then
        rm -rf "$dir"
    else
        inode="$(stat -c %i "$dir" 2>/dev/null)"
        owner="$(cat "$dir/pid" 2>/dev/null)"
        start="$(cat "$dir/start" 2>/dev/null)"
        [ -z "$inode" ] || worker_retire "$1" "$dir" "$inode" "$owner" "$start" || true
    fi
}

worker_clean_previous_boot() {
    local retired
    [ -n "$boot_id" ] || return 0
    for retired in "$root"/.*-retired-*; do
        [ -d "$retired" ] || continue
        case "$retired" in *"-retired-$boot_id-"*) ;; *) rm -rf "$retired" ;; esac
    done
}

worker_children() {
    if [ -r "/proc/$1/task/$1/children" ]; then
        cat "/proc/$1/task/$1/children" 2>/dev/null
    else

        ps -A -o PID,PPID 2>/dev/null | awk -v parent="$1" '$2 == parent { print $1 }'
    fi
}

worker_is_persistent_tee() {
    local args
    [ "$(cat "/proc/$1/comm" 2>/dev/null)" = supervisor ] || return 1
    [ "$(readlink "/proc/$1/cwd" 2>/dev/null)" = "$root/tee-runtime" ] || return 1
    args="$(tr '\000' ' ' < "/proc/$1/cmdline" 2>/dev/null)"
    case "$args" in *"./daemon $root/tee-runtime"*) return 0 ;; *) return 1 ;; esac
}

worker_stop_tree() {
    local pid start child child_start attempts preserve_tee
    pid="$1"
    start="$2"
    preserve_tee="${3:-0}"
    [ "$(worker_process_start "$pid")" = "$start" ] || return 0
    if [ "$preserve_tee" = 1 ] && worker_is_persistent_tee "$pid"; then return 0; fi

    kill -STOP "$pid" 2>/dev/null || return 0
    for child in $(worker_children "$pid"); do
        child_start="$(worker_process_start "$child")" || continue
        [ -z "$child_start" ] || worker_stop_tree "$child" "$child_start" "$preserve_tee"
    done
    if [ "$(worker_process_start "$pid")" = "$start" ]; then
        kill -TERM "$pid" 2>/dev/null || true
        kill -CONT "$pid" 2>/dev/null || true
        attempts=0
        while [ "$(worker_process_start "$pid")" = "$start" ] && [ "$attempts" -lt 10 ]; do
            sleep 0.05
            attempts=$((attempts + 1))
        done
        if [ "$(worker_process_start "$pid")" = "$start" ]; then
            kill -KILL "$pid" 2>/dev/null || true
        fi
    fi
}

worker_stop() {
    worker_guard worker_stop_locked "$1" "${2:-1}"
}

worker_stop_locked() {
    local dir pid start boot args inode
    dir="$root/.$1-lock"
    [ -d "$dir" ] || return 0
    inode="$(stat -c %i "$dir" 2>/dev/null)"
    [ -n "$inode" ] || return 0
    pid="$(cat "$dir/pid" 2>/dev/null)"
    start="$(cat "$dir/start" 2>/dev/null)"
    boot="$(cat "$dir/boot" 2>/dev/null)"
    if worker_is_current "$pid" "$start" "$boot"; then
        args="$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
        case "$args" in *"$runtime/"*) worker_stop_tree "$pid" "$start" "$2" ;; *) return 1 ;; esac
    fi
    worker_retire "$1" "$dir" "$inode" "$pid" "$start" || true
}

worker_is_legacy_hunter() {
    local cwd args
    cwd="$(readlink "/proc/$1/cwd" 2>/dev/null)"
    args="$(tr '\000' '\n' < "/proc/$1/cmdline" 2>/dev/null)" || return 1
    printf '%s\n' "$args" | awk -v runtime="$runtime" -v root="$root" -v cwd="$cwd" \
        -v busybox="${worker_busybox_name:-busybox}" '
        { args[++count] = $0 }
        END {
            first = args[1]
            sub(/^.*\//, "", first)
            index_arg = 1
            if (first == "sh" || first == "mksh" || first == "bash" || first == "ash" || first == "dash") {
                index_arg = 2
            } else if (first == busybox && (args[2] == "sh" || args[2] == "ash")) {
                index_arg = 3
            }
            while (args[index_arg] ~ /^-[euxfv]+$/ || args[index_arg] == "--") ++index_arg
            script = args[index_arg]
            matches = script == runtime "/keybox_heal.sh" ||
                (cwd == runtime && (script == "keybox_heal.sh" || script == "./keybox_heal.sh")) ||
                (cwd == root && (script == "runtime/keybox_heal.sh" || script == "./runtime/keybox_heal.sh"))
            exit !(matches && index_arg + 1 == count && args[index_arg + 1] == "hunt_daemon")
        }'
}

worker_stop_legacy_hunters() {
    local process pid start args processes candidates

    if processes="$(ps -A -o pid,args 2>/dev/null)"; then
        candidates="$(printf '%s\n' "$processes" | awk '$1 ~ /^[0-9]+$/ && /keybox_heal\.sh/ && /hunt_daemon/ { print $1 }')"
    else
        candidates="$(
            for process in /proc/[0-9]*; do
                [ -d "$process" ] || continue
                args="$(tr '\000' ' ' < "$process/cmdline" 2>/dev/null)" || continue
                case "$args" in *keybox_heal.sh*hunt_daemon*) printf '%s\n' "${process##*/}" ;; esac
            done
        )"
    fi
    for pid in $candidates; do
        start="$(worker_process_start "$pid")" || continue
        [ -n "$start" ] || continue
        worker_is_legacy_hunter "$pid" || continue

        worker_stop_tree "$pid" "$start" 1
    done
}

background_allowed() {
    [ -f "$state/enabled" ] && [ ! -f "$state/disabled" ] &&
        [ -f "$state/background-updates" ] && [ ! -f "$state/pending-reboot" ]
}

verification_allowed() {
    local power window
    power="$(dumpsys power 2>/dev/null | grep -E 'mWakefulness=|mInteractive=')"
    printf '%s\n' "$power" | grep -qE 'mWakefulness=Awake|mInteractive=true' || return 1
    window="$(dumpsys window policy 2>/dev/null | grep -E 'showing=|mShowingLockscreen=|isStatusBarKeyguard=')"
    printf '%s\n' "$window" | grep -qE 'showing=false|mShowingLockscreen=false|isStatusBarKeyguard=false' || return 1
    printf '%s\n' "$window" | grep -qE 'showing=true|mShowingLockscreen=true|isStatusBarKeyguard=true' && return 1
    return 0
}
