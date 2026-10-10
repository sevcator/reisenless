#!/system/bin/sh

umask 077
root=/data/adb/udonge
[ -d "$root" ] || root="$(cd "$(dirname "$0")/.." && pwd)"
runtime=$root/runtime
state=$root/state
[ -f "$state/enabled" ] && [ ! -f "$state/disabled" ] &&
    [ ! -f "$state/pending-reboot" ] || exit 0
tee_state=$state
legacy_tee_state=$root/tee-state
lock=$root/.service-lock
work=$root/keybox-check
boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
. "$runtime/worker.sh" || exit 1
worker_clean_previous_boot

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
    for child in $(worker_children "$supervisor"); do
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
    if [ ! -f "$state/boot_hash.bin" ] || [ "$(wc -c < "$state/boot_hash.bin" 2>/dev/null)" != 32 ]; then
        local cur_digest
        cur_digest="$(resetprop ro.boot.vbmeta.digest 2>/dev/null || getprop ro.boot.vbmeta.digest 2>/dev/null)"
        cur_digest="$(printf '%s' "$cur_digest" | tr -d '[:space:]')"
        if [ "${#cur_digest}" = 64 ] && [ "$cur_digest" != "0000000000000000000000000000000000000000000000000000000000000000" ] && command -v xxd >/dev/null 2>&1; then
            printf '%s' "$cur_digest" | xxd -r -p > "$state/boot_hash.bin" 2>/dev/null
        fi
        if [ ! -f "$state/boot_hash.bin" ] || [ "$(wc -c < "$state/boot_hash.bin" 2>/dev/null)" != 32 ]; then
            head -c 32 /dev/urandom > "$state/boot_hash.bin" 2>/dev/null || true
        fi
        chmod 600 "$state/boot_hash.bin" 2>/dev/null || true
    fi
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
while ! worker_acquire service; do

    [ "$lock_wait" -ge 420 ] && exit 1
    sleep 1
    lock_wait=$((lock_wait + 1))
done
cleanup() {
    rm -rf "$work" "$root/tee-runtime.new"
    worker_release service
}
trap cleanup EXIT
trap 'exit 1' INT TERM

[ -f "$state/disabled" ] && exit 0

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

if [ ! -f /data/system/theme_config/theme_compatibility.xml ]; then
    mkdir -p /data/system/theme_config 2>/dev/null
    touch /data/system/theme_config/theme_compatibility.xml 2>/dev/null
    chmod 755 /data/system/theme_config 2>/dev/null || true
    chmod 644 /data/system/theme_config/theme_compatibility.xml 2>/dev/null || true
    chown system:system /data/system/theme_config /data/system/theme_config/theme_compatibility.xml 2>/dev/null || true
fi

refresh_keybox() {
    if [ -x "$runtime/keybox_heal.sh" ]; then
        "$runtime/keybox_heal.sh" heal
        return $?
    fi
}

start_tee() {
    local sdk abi source run next old target patch version current pid pid_start pid_boot healthy
    local health health_start health_boot args
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
    payload_id="$(cat "$runtime/payload.id" 2>/dev/null)"
    current_payload_id="$(cat "$run/.payload_id" 2>/dev/null)"
    if [ "$version" != "$current" ] || [ -z "$payload_id" ] || [ "$payload_id" != "$current_payload_id" ] || [ ! -x "$run/supervisor" ]; then
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
        printf '%s\n' "$payload_id" > "$next/.payload_id"

        health="$(cat "$run/.health-pid" 2>/dev/null)"
        health_start="$(cat "$run/.health-start" 2>/dev/null)"
        health_boot="$(cat "$run/.health-boot" 2>/dev/null)"
        if worker_is_current "$health" "$health_start" "$health_boot"; then
            args="$(tr '\000' ' ' < "/proc/$health/cmdline" 2>/dev/null)"
            case "$args" in *"$runtime/service.sh"*) worker_stop_tree "$health" "$health_start" ;; esac
        fi
        healthy="$(find_tee_supervisor "$run")"
        [ -z "$healthy" ] || remember_tee_supervisor "$run" "$healthy"
        pid="$(cat "$run/.pid" 2>/dev/null)"
        pid_start="$(cat "$run/.pid-start" 2>/dev/null)"
        pid_boot="$(cat "$run/.pid-boot" 2>/dev/null)"
        if worker_is_current "$pid" "$pid_start" "$pid_boot" &&
            { [ "$pid" = "$healthy" ] || worker_is_persistent_tee "$pid"; }; then
            worker_stop_tree "$pid" "$pid_start"
        fi
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
    [ -f "$target" ] || touch "$target"
    for conf_file in "$runtime/defaults/targets.conf" "$state/targets.conf"; do
        if [ -f "$conf_file" ]; then
            while IFS= read -r raw || [ -n "$raw" ]; do
                pkg="$(printf '%s' "$raw" | sed 's/^stealth://' | tr -d '\r\n ')"
                [ -n "$pkg" ] || continue
                case "$pkg" in '#'*) continue ;; esac
                grep -qxF "$pkg" "$target" 2>/dev/null || printf '%s\n' "$pkg" >> "$target"
            done < "$conf_file"
        fi
    done

    local tee_broken=0
    if [ "$sdk" -ge 31 ] 2>/dev/null; then
        if [ -f "$state/tee-broken" ] || [ -f "$state/tee-unavailable" ] || \
           [ "$(getprop ro.board.platform 2>/dev/null)" = "sdm845" ] || \
           [ "$(getprop ro.boot.hardware.platform 2>/dev/null)" = "sdm845" ] || \
           [ "$(getprop ro.product.manufacturer 2>/dev/null)" = "OnePlus" ]; then
            tee_broken=1
            touch "$state/tee-broken" 2>/dev/null || true
            chmod 600 "$state/tee-broken" 2>/dev/null || true
        fi
    fi

    temp_target="$tee_state/.target.$$"
    awk '{
        line = $0
        gsub(/[ \r\t]/, "", line)
        if (line == "" || line ~ /^#/) next
        clean = line
        gsub(/[!?]+$/, "", clean)
        if (!seen[clean]++) {
            order[count++] = clean
            mode[clean] = (line ~ /!$/) ? "!" : ((line ~ /\?$/) ? "?" : "")
        } else if (line ~ /!$/) {
            mode[clean] = "!"
        }
    }
    END {
        for (i = 0; i < count; i++) {
            c = order[i]
            print c mode[c]
        }
    }' "$target" > "$temp_target" 2>/dev/null && mv -f "$temp_target" "$target"

    if [ "$tee_broken" = 1 ]; then
        for p in com.android.vending com.google.android.gms com.google.android.gsf gr.nikolasspyr.integritycheck io.github.vvb2060.keyattestation io.github.qwq233.keyattestation; do
            sed -i "s/^${p}[!?]*\$/${p}!/" "$target" 2>/dev/null || true
        done
    else
        sed -i 's/^com\.android\.vending!$/com.android.vending/' "$target" 2>/dev/null || true
        sed -i 's/^com\.google\.android\.gms!$/com.google.android.gms/' "$target" 2>/dev/null || true
        sed -i 's/^com\.google\.android\.gsf!$/com.google.android.gsf/' "$target" 2>/dev/null || true
        sed -i 's/^gr\.nikolasspyr\.integritycheck[!?]*$/gr.nikolasspyr.integritycheck/' "$target" 2>/dev/null || true
        sed -i 's/^io\.github\.vvb2060\.keyattestation[!?]*$/io.github.vvb2060.keyattestation/' "$target" 2>/dev/null || true
        sed -i 's/^io\.github\.qwq233\.keyattestation[!?]*$/io.github.qwq233.keyattestation/' "$target" 2>/dev/null || true
    fi

    sed -i 's/^com\.eltavine\.duckdetector[!?]*$/com.eltavine.duckdetector/' "$target" 2>/dev/null || true

    printf 'force\n' > "$tee_state/boot_props_mode"
    chmod 600 "$tee_state/boot_props_mode"

    patch="$(sed -n 's/^SECURITY_PATCH=//p' "$state/pif.conf" 2>/dev/null | head -n 1)"
    [ -n "$patch" ] || patch="$(getprop ro.build.version.security_patch 2>/dev/null)"
    if [ -z "$patch" ]; then
        c_year=$(date +%Y 2>/dev/null || echo 2026)
        c_month=$(date +%m 2>/dev/null || echo 09)
        patch="${c_year}-${c_month}-05"
    fi
    cat > "$tee_state/security_patch.txt" <<EOF
system=prop
boot=$patch
vendor=$patch
EOF
    chmod 600 "$tee_state/security_patch.txt"

    mkdir -p /data/adb 2>/dev/null
    ln -sfn "$tee_state" /data/adb/tricky_store 2>/dev/null || true

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

RP="resetprop"
if command -v resetprop >/dev/null 2>&1; then
    $RP -n ro.boot.vbmeta.device_state locked
    $RP -n ro.boot.verifiedbootstate green
    $RP -n ro.boot.flash.locked 1
    $RP -n ro.boot.veritymode enforcing
    $RP -n ro.boot.veritymode.managed yes
    $RP -p persist.sys.pihooks.disable.gms_props true 2>/dev/null || true
    $RP -p persist.sys.pihooks.disable.gms_key_attestation_block true 2>/dev/null || true
    $RP -p persist.sys.entryhooks_enabled false 2>/dev/null || true
    $RP -p -d persist.sys.spoof.gms 2>/dev/null || true
    $RP -p -d persist.sys.pixelprops.gms 2>/dev/null || true
    $RP -d persist.sys.spoof.gms 2>/dev/null || true
    $RP -d persist.sys.pixelprops.gms 2>/dev/null || true
    $RP -n ro.boot.warranty_bit 0
    $RP -n ro.warranty_bit 0
    $RP -n ro.secure 1
    $RP -n ro.debuggable 0
    $RP -n ro.force.debuggable 0
    $RP -n ro.adb.secure 1
    $RP -n ro.build.type user
    $RP -n ro.build.tags release-keys
    $RP -n sys.oem_unlock_allowed 0
    $RP -n ro.boot.selinux enforcing
    $RP -n ro.boot.avb_version 1.3
    $RP -n ro.boot.vbmeta.avb_version 1.0
    $RP -n ro.boot.vbmeta.hash_alg sha256
    $RP -n ro.boot.vbmeta.size 4096
    $RP -n ro.is_ever_orange 0
    $RP -n ro.crypto.state encrypted
    $RP -n ro.oem_unlock_supported 0
    $RP -n vendor.boot.vbmeta.device_state locked
    $RP -n vendor.boot.verifiedbootstate green
    $RP -n ro.bootmode unknown
    $RP -n ro.boot.bootmode unknown
    $RP -n vendor.boot.bootmode unknown
    for part in system vendor product system_ext odm; do
        $RP -n "partition.${part}.verified" 1
    done
    $RP -d ro.boot.verifiedbooterror 2>/dev/null || true
    $RP -d ro.boot.verifyerrorpart 2>/dev/null || true
    patch="$(sed -n 's/^SECURITY_PATCH=//p' "$state/pif.conf" 2>/dev/null | head -n 1)"
    if [ -n "$patch" ]; then
        $RP -n ro.build.version.security_patch "$patch"
        $RP -n ro.vendor.build.security_patch "$patch"
    fi

    if [ -f "$state/pif.conf" ]; then
        brand="$(sed -n 's/^BRAND=//p' "$state/pif.conf" 2>/dev/null | head -n 1)"
        model="$(sed -n 's/^MODEL=//p' "$state/pif.conf" 2>/dev/null | head -n 1)"
        device="$(sed -n 's/^DEVICE=//p' "$state/pif.conf" 2>/dev/null | head -n 1)"
        manufacturer="$(sed -n 's/^MANUFACTURER=//p' "$state/pif.conf" 2>/dev/null | head -n 1)"
        product="$(sed -n 's/^PRODUCT=//p' "$state/pif.conf" 2>/dev/null | head -n 1)"
        fingerprint="$(sed -n 's/^FINGERPRINT=//p' "$state/pif.conf" 2>/dev/null | head -n 1)"
        id="$(sed -n 's/^ID=//p' "$state/pif.conf" 2>/dev/null | head -n 1)"

        for prefix in ro.product \
                      ro.product.bootimage \
                      ro.product.odm \
                      ro.product.odm_dlkm \
                      ro.product.product \
                      ro.product.system \
                      ro.product.system_ext \
                      ro.product.system_dlkm \
                      ro.product.vendor \
                      ro.product.vendor_dlkm; do
            [ -n "$brand" ] && $RP -n "${prefix}.brand" "$brand"
            [ -n "$model" ] && $RP -n "${prefix}.model" "$model"
            [ -n "$device" ] && $RP -n "${prefix}.device" "$device"
            [ -n "$manufacturer" ] && $RP -n "${prefix}.manufacturer" "$manufacturer"
            [ -n "$product" ] && $RP -n "${prefix}.name" "$product"
        done
        [ -n "$product" ] && $RP -n ro.build.product "$product"
        [ -n "$model" ] && $RP -n bluetooth.device.default_name "$model"
        $RP -n ro.com.google.clientidbase "android-google"
        for fp_prop in ro.build.fingerprint \
                       ro.bootimage.build.fingerprint \
                       ro.odm.build.fingerprint \
                       ro.product.build.fingerprint \
                       ro.system.build.fingerprint \
                       ro.system_ext.build.fingerprint \
                       ro.vendor.build.fingerprint \
                       ro.vendor_dlkm.build.fingerprint; do
            [ -n "$fingerprint" ] && $RP -n "$fp_prop" "$fingerprint"
        done
        [ -n "$id" ] && {
            $RP -n ro.build.id "$id"
            $RP -n ro.build.display.id "$id"
        }
        [ -n "$product" ] && $RP -n ro.build.description "${product}-user 15 CANARY release-keys"
        [ -n "$product" ] && $RP -n ro.build.flavor "${product}-user"
    fi

    for rom_prop in ro.lineage.build.version \
                     ro.lineage.device \
                     ro.lineage.display.version \
                     ro.lineage.releasetype \
                     ro.lineage.version \
                     ro.lineagelegal.url \
                     ro.boot.project_codename \
                     persist.vendor.camera.privapp.list; do
        $RP -n -d "$rom_prop" 2>/dev/null || true
    done

fi

stop soter-1-0 2>/dev/null || true
setprop ctl.stop soter-1-0 2>/dev/null || true
pm disable com.tencent.soter.soterserver >/dev/null 2>&1 || true

version="$(cat "$runtime/version" 2>/dev/null)"
pif_hash="$(sha256sum "$state/pif.conf" 2>/dev/null | awk '{print $1}')"
certified="$(cat "$state/.certified" 2>/dev/null)"
if [ -f "$state/pif.conf" ] && [ "${version}_${pif_hash}" != "$certified" ]; then
    am force-stop com.google.android.gms >/dev/null 2>&1
    am force-stop com.android.vending >/dev/null 2>&1
    pm clear com.android.vending >/dev/null 2>&1
    am broadcast -a android.server.checkin.CHECKIN >/dev/null 2>&1
    printf '%s\n' "${version}_${pif_hash}" > "$state/.certified"
    chmod 600 "$state/.certified"
fi

if background_allowed && [ ! -f "$state/.strong_locked" ] && [ -x "$runtime/keybox_heal.sh" ]; then
    "$runtime/keybox_heal.sh" hunt_daemon </dev/null >/dev/null 2>&1 &
fi
