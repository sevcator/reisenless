#!/system/bin/sh

umask 077
root=/data/adb/udonge
runtime=$root/runtime
state=$root/state
run=$root/tee-runtime
lock=$root/.service-lock
boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"

terminate_pid() {
    target="$1"
    kill -TERM "$target" 2>/dev/null || true
    attempts=0
    while [ -d "/proc/$target" ] && [ "$attempts" -lt 10 ]; do
        sleep 0.1
        attempts=$((attempts + 1))
    done
    [ ! -d "/proc/$target" ] || kill -KILL "$target" 2>/dev/null || true
}

stop_recorded_process() {
    pid_file="$1"
    start_file="$2"
    boot_file="$3"
    pattern="$4"
    pid="$(cat "$pid_file" 2>/dev/null)"
    expected_start="$(cat "$start_file" 2>/dev/null)"
    expected_boot="$(cat "$boot_file" 2>/dev/null)"
    [ -n "$pid" ] && [ -n "$expected_start" ] && [ "$expected_boot" = "$boot_id" ] || return 0
    current_start="$(awk '{print $22}' "/proc/$pid/stat" 2>/dev/null)"
    [ "$current_start" = "$expected_start" ] || return 0
    cmdline="$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
    case "$cmdline" in
        *"$pattern"*) terminate_pid "$pid" ;;
    esac
}

supervisor="$(cat "$run/.pid" 2>/dev/null)"
supervisor_start="$(cat "$run/.pid-start" 2>/dev/null)"
supervisor_boot="$(cat "$run/.pid-boot" 2>/dev/null)"
if [ -n "$supervisor" ] && [ -n "$supervisor_start" ] && [ "$supervisor_boot" = "$boot_id" ] &&
    [ "$(awk '{print $22}' "/proc/$supervisor/stat" 2>/dev/null)" = "$supervisor_start" ]; then
    for child in $(ps -A -o PID,PPID,NAME 2>/dev/null | awk -v parent="$supervisor" \
        '$2 == parent && $3 == "TEESimulator" { print $1 }'); do
        terminate_pid "$child"
    done
fi

stop_recorded_process "$run/.health-pid" "$run/.health-start" "$run/.health-boot" "$runtime/service.sh"
stop_recorded_process "$run/.pid" "$run/.pid-start" "$run/.pid-boot" "./supervisor ./daemon $run"
stop_recorded_process "$lock/pid" "$lock/start" "$lock/boot" "$runtime/service.sh"

rm -f "$run/.pid" "$run/.pid-start" "$run/.pid-boot"
rm -f "$run/.health-pid" "$run/.health-start" "$run/.health-boot"
rm -rf "$root/keybox-check" "$root/tee-runtime.new" "$lock"
rm -f "$state/.keybox-refresh"
