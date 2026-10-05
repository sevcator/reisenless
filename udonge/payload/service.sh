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

# Prevent MIUI ThemeCompatibilityLoader crash in app_process/TEESimulator
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

    # Broken TEE handling: for devices with Keymaster <= 3 on Android 12+ or broken TEE HAL,
    # force GENERATE mode (!) for Google Play Services and Store so hardware leaf generation is bypassed.
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

    # Deduplicate and ensure '!' mode takes precedence over non-'!' entries
    temp_target="$tee_state/.target.$$"
    awk '{
        line = $0
        gsub(/[ \r\t]/, "", line)
        if (line == "" || line ~ /^#/) next
        if (line ~ /!$/) {
            base = substr(line, 1, length(line) - 1)
            is_gen[base] = 1
        } else {
            has_plain[line] = 1
        }
        order[count++] = line
    }
    END {
        for (i = 0; i < count; i++) {
            l = order[i]
            if (l ~ /!$/) {
                print l
            } else if (!is_gen[l]) {
                print l
            }
        }
    }' "$target" > "$temp_target" 2>/dev/null && mv -f "$temp_target" "$target"

    # If tee_broken (e.g. sdm845 / OnePlus with Keymaster <= 3 on Android 12+),
    # ensure '!' is appended so hardware leaf generation is bypassed.
    if [ "$tee_broken" = 1 ]; then
        for p in com.android.vending com.google.android.gms com.google.android.gsf gr.nikolasspyr.integritycheck io.github.vvb2060.keyattestation; do
            sed -i "s/^${p}\$/${p}!/" "$target" 2>/dev/null || true
        done
    else
        sed -i 's/^com\.android\.vending!$/com.android.vending/' "$target" 2>/dev/null || true
        sed -i 's/^com\.google\.android\.gms!$/com.google.android.gms/' "$target" 2>/dev/null || true
        sed -i 's/^com\.google\.android\.gsf!$/com.google.android.gsf/' "$target" 2>/dev/null || true
        sed -i 's/^gr\.nikolasspyr\.integritycheck[!?]*$/gr.nikolasspyr.integritycheck/' "$target" 2>/dev/null || true
        sed -i 's/^io\.github\.vvb2060\.keyattestation[!?]*$/io.github.vvb2060.keyattestation/' "$target" 2>/dev/null || true
    fi
    # Always keep com.eltavine.duckdetector clean without '!' or '?'
    sed -i 's/^com\.eltavine\.duckdetector[!?]*$/com.eltavine.duckdetector/' "$target" 2>/dev/null || true

    # Force boot_props_mode to ensure BootStateManager does not skip Oplus-family devices (e.g. OnePlus 6)
    printf 'force\n' > "$tee_state/boot_props_mode"
    chmod 600 "$tee_state/boot_props_mode"

    patch="$(sed -n 's/^SECURITY_PATCH=//p' "$state/pif.conf" 2>/dev/null | head -n 1)"
    [ -n "$patch" ] || patch="$(getprop ro.build.version.security_patch 2>/dev/null)"
    if [ -n "$patch" ]; then
        cat > "$tee_state/security_patch.txt" <<EOF
system=prop
boot=$patch
vendor=$patch
EOF
        chmod 600 "$tee_state/security_patch.txt"
    fi

    # Compatibility symlink for TrickyStore / YuriKey / IntegrityBox modules
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

# AVB and verified boot state hardening (from IntegrityBox & YuriKey)
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
    $RP -n ro.is_ever_orange 0
    $RP -n ro.crypto.state encrypted
    $RP -n ro.oem_unlock_supported 0
    $RP -n vendor.boot.vbmeta.device_state locked
    $RP -n vendor.boot.verifiedbootstate green
    $RP -n ro.bootmode unknown
    $RP -n ro.boot.bootmode unknown
    $RP -n vendor.boot.bootmode unknown
    for part in system vendor product system_ext odm; do
        $RP -n "partition.${part}.verified" 0
    done
    $RP -d ro.boot.verifiedbooterror 2>/dev/null || true
    $RP -d ro.boot.verifyerrorpart 2>/dev/null || true
    patch="$(sed -n 's/^SECURITY_PATCH=//p' "$state/pif.conf" 2>/dev/null | head -n 1)"
    if [ -n "$patch" ]; then
        $RP -n ro.build.version.security_patch "$patch"
        $RP -n ro.vendor.build.security_patch "$patch"
    fi

    # Sync product identity from pif.conf for Device ID Attestation consistency
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

    # Strip custom ROM and OEM test residue properties (keep hardware platform props intact for Keymaster HAL)
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

    # Clean up leftover root artifacts in /data/local/tmp that trigger DroidGuard AVC denials
    rm -f /data/local/tmp/su /data/local/tmp/su-old-apk /data/local/tmp/*su* 2>/dev/null || true
fi

# Disable broken vendor Soter HAL service and daemon that trigger attestation anomalies on unlocked bootloaders
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

# Launch Keybox Hunter background worker if Strong Integrity is not yet locked
if [ ! -f "$state/.strong_locked" ] && [ -x "$runtime/keybox_heal.sh" ]; then
    (
        sleep 20
        "$runtime/keybox_heal.sh" hunt_daemon </dev/null >/dev/null 2>&1
    ) &
fi
