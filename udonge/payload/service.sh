#!/system/bin/sh

umask 077
root=/data/adb/udonge
[ -d "$root" ] || root="$(cd "$(dirname "$0")/.." && pwd)"
runtime=$root/runtime
state=$root/state
tee_state=$state
legacy_tee_state=$root/tee-state
lock=$root/.service-lock
work=$root/keybox-check
boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"

process_is_current() {
    local pid expected_start expected_boot current_start
    pid="$1"
    expected_start="$2"
    expected_boot="$3"
    [ -n "$pid" ] && [ -n "$expected_start" ] && [ "$expected_boot" = "$boot_id" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    current_start="$(awk '{print $22}' "/proc/$pid/stat" 2>/dev/null)"
    [ -n "$current_start" ] && [ "$current_start" = "$expected_start" ]
}

tee_child_is_current() {
    local supervisor child name
    supervisor="$1"
    for child in $(cat "/proc/$supervisor/task/$supervisor/children" 2>/dev/null); do
        name="$(cat "/proc/$child/comm" 2>/dev/null)"
        [ "$name" = TEESimulator ] && return 0
    done
    return 1
}

find_tee_supervisor() {
    local run child supervisor name cmdline
    run="$1"
    for child in $(pidof TEESimulator 2>/dev/null); do
        supervisor="$(awk '/^PPid:/{print $2}' "/proc/$child/status" 2>/dev/null)"
        [ -n "$supervisor" ] || continue
        name="$(cat "/proc/$supervisor/comm" 2>/dev/null)"
        [ "$name" = supervisor ] || continue
        cmdline="$(tr '\000' ' ' < "/proc/$supervisor/cmdline" 2>/dev/null)"
        case "$cmdline" in
            *"./daemon $run"*)
                printf '%s\n' "$supervisor"
                return 0
                ;;
        esac
    done
    return 1
}

remember_tee_supervisor() {
    local run pid start
    run="$1"
    pid="$2"
    start="$(awk '{print $22}' "/proc/$pid/stat" 2>/dev/null)"
    [ -n "$start" ] || return 1
    printf '%s\n' "$pid" > "$run/.pid"
    printf '%s\n' "$start" > "$run/.pid-start"
    printf '%s\n' "$boot_id" > "$run/.pid-boot"
}

sync_vbmeta_digest() {
    local digest temp
    [ "$(wc -c < "$state/boot_hash.bin" 2>/dev/null)" = 32 ] || return 1
    digest="$(od -An -tx1 -v "$state/boot_hash.bin" 2>/dev/null | tr -d ' \n')"
    [ "${#digest}" = 64 ] || return 1
    temp="$state/.props.$$"
    sed '/^ro\.boot\.vbmeta\.digest=/d' "$state/props.conf" 2>/dev/null > "$temp"
    printf 'ro.boot.vbmeta.digest=%s\n' "$digest" >> "$temp"
    chmod 600 "$temp"
    mv -f "$temp" "$state/props.conf"
}

lock_wait=0
while ! mkdir "$lock" 2>/dev/null; do
    owner="$(cat "$lock/pid" 2>/dev/null)"
    owner_start="$(cat "$lock/start" 2>/dev/null)"
    owner_boot="$(cat "$lock/boot" 2>/dev/null)"
    if process_is_current "$owner" "$owner_start" "$owner_boot"; then
        # A scheduled refresh may set its marker after the current owner has
        # already passed refresh_keybox. Wait, acquire the lock, and run again
        # so JobScheduler observes completion of its own request.
        [ "$lock_wait" -ge 420 ] && exit 1
        sleep 1
        lock_wait=$((lock_wait + 1))
    else
        rm -rf "$lock"
    fi
done
printf '%s\n' "$$" > "$lock/pid"
awk '{print $22}' "/proc/$$/stat" > "$lock/start" 2>/dev/null
printf '%s\n' "$boot_id" > "$lock/boot"
cleanup() {
    rm -rf "$work" "$root/tee-runtime.new" "$lock"
}
trap cleanup EXIT INT TERM

ensure_usb_debugging() {
    for i in $(seq 1 30); do
        cfg="$(getprop persist.sys.usb.config 2>/dev/null)"
        case "$cfg" in
            *adb*) ;;
            none|""|mtp) setprop persist.sys.usb.config mtp,adb 2>/dev/null ;;
            *) setprop persist.sys.usb.config "${cfg},adb" 2>/dev/null ;;
        esac
        setprop persist.sys.oppo.usbactive 1 2>/dev/null

        if command -v settings >/dev/null 2>&1; then
            settings put global adb_enabled 1 2>/dev/null
            settings put secure adb_enabled 1 2>/dev/null
            settings put global usb_debugging_auto_disabled 0 2>/dev/null
            settings put secure usb_turn_off_time 0 2>/dev/null
            settings put system oppo_settings_manager_fingerprint_auto_disable_usb 0 2>/dev/null
            settings put secure oppo_settings_manager_fingerprint_auto_disable_usb 0 2>/dev/null
        fi

        [ "$(getprop init.svc.adbd 2>/dev/null)" = "running" ] || start adbd 2>/dev/null
        sleep 2
    done
}
ensure_usb_debugging &

boot_wait=0

until [ "$(getprop sys.boot_completed)" = 1 ]; do
    [ "$boot_wait" -ge 90 ] && exit 0
    sleep 2
    boot_wait=$((boot_wait + 1))
done

[ -f "$state/disabled" ] && exit 0

if [ -d "$legacy_tee_state" ]; then
    for name in keybox.xml target.txt security_patch.txt hbk boot_props_mode boot_hash.bin boot_key.bin; do
        if [ -f "$legacy_tee_state/$name" ] && [ ! -e "$tee_state/$name" ]; then
            mv "$legacy_tee_state/$name" "$tee_state/$name"
        fi
    done
    if [ -d "$legacy_tee_state/persistent_keys" ] && [ ! -e "$tee_state/persistent_keys" ]; then
        mv "$legacy_tee_state/persistent_keys" "$tee_state/persistent_keys"
    fi
    rm -rf "$legacy_tee_state"
fi
chmod 700 "$root" "$state"

refresh_keybox() {
    if [ -x "$runtime/keybox_heal.sh" ]; then
        "$runtime/keybox_heal.sh" heal
        return $?
    fi
}

start_tee() {
    local sdk abi source run next old target patch version current pid pid_start pid_boot healthy
    sdk="$(getprop ro.build.version.sdk 2>/dev/null)"
    [ "$sdk" -ge 29 ] 2>/dev/null || return 0
    abi="$(getprop ro.product.cpu.abi 2>/dev/null)"
    source="$runtime/tee/$abi"
    [ -f "$source/libTEESimulator.so" ] || return 0
    [ -f "$source/inject" ] || return 0
    [ -f "$source/supervisor" ] || return 0
    [ -f "$runtime/tee/classes.dex" ] || return 0
    [ -f "$runtime/tee/daemon" ] || return 0

    run="$root/tee-runtime"
    version="$(cat "$runtime/version" 2>/dev/null)"
    current="$(cat "$run/.version" 2>/dev/null)"
    if [ "$version" != "$current" ] || [ ! -x "$run/supervisor" ]; then
        next="$root/tee-runtime.new"
        old="$root/tee-runtime.old"
        rm -rf "$next" "$old"
        mkdir -p "$next" "$tee_state"
        chmod 700 "$next" "$tee_state"
        cp "$source/libTEESimulator.so" "$next/" || return 0
        [ ! -f "$source/libcertgen.so" ] || cp "$source/libcertgen.so" "$next/"
        cp "$source/inject" "$next/inject" || return 0
        cp "$source/supervisor" "$next/supervisor" || return 0
        cp "$runtime/tee/classes.dex" "$next/classes.dex" || return 0
        cp "$runtime/tee/daemon" "$next/daemon" || return 0
        chmod 700 "$next/inject" "$next/supervisor" "$next/daemon"
        printf '%s\n' "$version" > "$next/.version"
        [ ! -d "$run" ] || mv "$run" "$old"
        if mv "$next" "$run"; then
            rm -rf "$old"
        else
            [ ! -d "$old" ] || mv "$old" "$run"
            return 0
        fi
    else
        mkdir -p "$tee_state"
        chmod 700 "$tee_state" "$run"
    fi

    if ! chcon u:object_r:udonge_lib_file:s0 "$run/libTEESimulator.so" 2>/dev/null; then
        : > "$state/tee-unavailable"
        chmod 600 "$state/tee-unavailable"
        return 0
    fi

    if [ ! -f "$tee_state/keybox.xml" ]; then
        cp "$runtime/defaults/keybox.xml" "$tee_state/keybox.xml"
        chmod 600 "$tee_state/keybox.xml"
    fi
    target="$tee_state/target.txt"
    if [ ! -f "$target" ]; then
        if [ -f "$state/targets.conf" ]; then
            cp "$state/targets.conf" "$target"
        elif [ -f "$runtime/defaults/targets.conf" ]; then
            cp "$runtime/defaults/targets.conf" "$target"
        else
            printf 'com.android.vending\ncom.google.android.gms\n' > "$target"
        fi
    fi
    if [ -f "$state/targets.conf" ]; then
        while IFS= read -r pkg; do
            [ -n "$pkg" ] || continue
            grep -qxF "$pkg" "$target" 2>/dev/null || printf '%s\n' "$pkg" >> "$target"
        done < "$state/targets.conf"
    fi
    if [ ! -f "$tee_state/security_patch.txt" ] || {
        grep -q '^system=' "$tee_state/security_patch.txt" &&
        ! grep -Eq '^(all|vendor|boot)=' "$tee_state/security_patch.txt";
    }; then
        patch="$(sed -n 's/^SECURITY_PATCH=//p' "$state/pif.conf" | head -n 1)"
        [ -z "$patch" ] || printf 'all=%s\n' "$patch" > "$tee_state/security_patch.txt"
    fi
    [ -f "$tee_state/hbk" ] || head -c 32 /dev/urandom > "$tee_state/hbk"
    for item in "$tee_state"/*; do
        [ -e "$item" ] || continue
        if [ -d "$item" ]; then
            chmod 700 "$item"
        else
            chmod 600 "$item"
        fi
    done

    rm -rf "$tee_state/logs"

    healthy="$(find_tee_supervisor "$run")"
    if [ -n "$healthy" ] && remember_tee_supervisor "$run" "$healthy"; then
        rm -f "$state/tee-unavailable"
        return 0
    fi

    pid="$(cat "$run/.pid" 2>/dev/null)"
    pid_start="$(cat "$run/.pid-start" 2>/dev/null)"
    pid_boot="$(cat "$run/.pid-boot" 2>/dev/null)"
    if process_is_current "$pid" "$pid_start" "$pid_boot"; then
        if tee_child_is_current "$pid"; then
            rm -f "$state/tee-unavailable"
            rm -rf "$tee_state/logs"
        fi
        return 0
    fi
    rm -f "$run/.pid" "$run/.pid-start" "$run/.pid-boot"
    rm -f "$state/tee-unavailable"
    (cd "$run" && exec ./supervisor ./daemon "$run" </dev/null >/dev/null 2>&1) &
    pid="$!"
    printf '%s\n' "$pid" > "$run/.pid"
    pid_start="$(awk '{print $22}' "/proc/$pid/stat" 2>/dev/null)"
    printf '%s\n' "$pid_start" > "$run/.pid-start"
    printf '%s\n' "$boot_id" > "$run/.pid-boot"

    (
        sleep 2
        rm -rf "$tee_state/logs"
        sleep 58
        sync_vbmeta_digest || true
        healthy="$(find_tee_supervisor "$run")"
        if [ -n "$healthy" ] && remember_tee_supervisor "$run" "$healthy"; then
            rm -f "$state/tee-unavailable"
            rm -rf "$tee_state/logs"
        else
            process_is_current "$pid" "$pid_start" "$boot_id" && kill "$pid" 2>/dev/null
            rm -f "$run/.pid" "$run/.pid-start" "$run/.pid-boot"
            : > "$state/tee-unavailable"
            chmod 600 "$state/tee-unavailable"
        fi
        rm -f "$run/.health-pid" "$run/.health-start" "$run/.health-boot"
    ) &
    health_pid="$!"
    printf '%s\n' "$health_pid" > "$run/.health-pid"
    awk '{print $22}' "/proc/$health_pid/stat" > "$run/.health-start" 2>/dev/null
    printf '%s\n' "$boot_id" > "$run/.health-boot"
}

refresh_keybox
start_tee
sync_vbmeta_digest || true

# Disable broken vendor Soter HAL service and daemon that trigger attestation anomalies on unlocked bootloaders
stop soter-1-0 2>/dev/null || true
setprop ctl.stop soter-1-0 2>/dev/null || true
pm disable com.tencent.soter.soterserver >/dev/null 2>&1 || true


version="$(cat "$runtime/version" 2>/dev/null)"
certified="$(cat "$state/.certified" 2>/dev/null)"
if [ -f "$state/pif.conf" ] && [ "$version" != "$certified" ]; then
    am force-stop com.google.android.gms >/dev/null 2>&1
    am broadcast -a android.server.checkin.CHECKIN >/dev/null 2>&1
    printf '%s\n' "$version" > "$state/.certified"
    chmod 600 "$state/.certified"
fi
