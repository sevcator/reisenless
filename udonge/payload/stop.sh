#!/system/bin/sh

umask 077
root=/data/adb/udonge
[ -d "$root" ] || root="$(cd "$(dirname "$0")/.." && pwd)"
runtime=$root/runtime
state=$root/state
run=$root/tee-runtime
lock=$root/.service-lock
boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
. "$runtime/worker.sh" || exit 1
worker_stop hunter 0
worker_stop keybox 0
worker_stop usb 0

terminate_pid() {
    local start
    start="$(worker_process_start "$1")"
    [ -z "$start" ] || worker_stop_tree "$1" "$start"
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
        *"$pattern"*) worker_stop_tree "$pid" "$expected_start" ;;
    esac
}

supervisor="$(cat "$run/.pid" 2>/dev/null)"
supervisor_start="$(cat "$run/.pid-start" 2>/dev/null)"
supervisor_boot="$(cat "$run/.pid-boot" 2>/dev/null)"
stop_recorded_process "$run/.health-pid" "$run/.health-start" "$run/.health-boot" "$runtime/service.sh"
stop_recorded_process "$run/.pid" "$run/.pid-start" "$run/.pid-boot" "./supervisor ./daemon $run"
stop_recorded_process "$lock/pid" "$lock/start" "$lock/boot" "$runtime/service.sh"

rm -f "$run/.pid" "$run/.pid-start" "$run/.pid-boot"
rm -f "$run/.health-pid" "$run/.health-start" "$run/.health-boot"
rm -rf "$root/keybox-check" "$root/tee-runtime.new" "$lock"
rm -f "$state/.keybox-refresh"
