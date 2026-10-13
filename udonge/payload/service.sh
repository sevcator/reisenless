#!/system/bin/sh

umask 077
root=UDONGE_ROOT_STUB
[ -d "$root" ] || root="$(cd "$(dirname "$0")/.." && pwd)"
runtime=$root/runtime
state=$root/state
[ -f "$state/enabled" ] && [ ! -f "$state/disabled" ] &&
    [ ! -f "$state/pending-reboot" ] || exit 0
tee_state=$state
legacy_tee_state=$root/tee-state
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
    current_start="$(worker_process_start "$pid")"
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
    start="$(worker_process_start "$pid")"
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

confirm_boot() {
    local system_server server_start attempt
    system_server="$(pidof system_server 2>/dev/null)"
    server_start="$(worker_process_start "$system_server")"
    [ -n "$server_start" ] || return 0
    attempt=0
    while [ "$attempt" -lt 60 ]; do
        [ -f "$state/disabled" ] && return 0
        [ "$(getprop sys.boot_completed)" = 1 ] || return 0
        worker_is_current "$system_server" "$server_start" "$boot_id" || return 0
        sleep 2
        attempt=$((attempt + 1))
    done
    [ "$(cat "$state/boot-pending" 2>/dev/null)" != "$boot_id" ] || rm -f "$state/boot-pending"

    if background_allowed && [ ! -f "$state/.strong_locked" ] && [ -x "$runtime/keybox_heal.sh" ]; then
        "$runtime/keybox_heal.sh" hunt_daemon </dev/null >/dev/null 2>&1 &
    fi
}

# Boot health must not wait for keybox downloads or TEE initialization.
confirm_boot </dev/null >/dev/null 2>&1 &

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
    if [ "$(cat "$state/tee-failed-payload" 2>/dev/null)" = "$(cat "$runtime/payload.id" 2>/dev/null)" ]; then
        return 0
    fi
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
        cp "$source/inject" "$next/inject.bin" || return 0
        cp "$runtime/tee/inject.sh" "$next/inject" || return 0
        cp "$source/supervisor" "$next/supervisor" || return 0
        cp "$runtime/tee/classes.dex" "$next/classes.dex" || return 0
        cp "$runtime/tee/daemon" "$next/daemon" || return 0
        chmod 700 "$next/inject" "$next/inject.bin" "$next/supervisor" "$next/daemon"
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
        # Loading failures say nothing about hardware key generation. AUTO probes
        # the real TEE and handles vendor quirks; only an explicit override forces it.
        if [ -f "$state/tee-hardware-broken" ]; then
            tee_broken=1
        fi
    fi

    temp_target="$tee_state/.target.$$"
    # Shared system UIDs must never receive synthetic keys, including OEM services.
    local pkg_try=0
    while [ "$pkg_try" -lt 15 ]; do
        cmd package list packages -U 2>/dev/null | awk '{
            package = $1; sub(/^package:/, "", package)
            for (i = 2; i <= NF; i++) if ($i ~ /^uid:/) {
                uid = $i; sub(/^uid:/, "", uid)
                if (uid % 100000 < 10000) print package
            }
        }' > "$tee_state/.system-packages"
        [ -s "$tee_state/.system-packages" ] && break
        sleep 1
        pkg_try=$((pkg_try + 1))
    done
    if [ ! -s "$tee_state/.system-packages" ]; then
        cat << 'EOF' > "$tee_state/.system-packages"
android
system_server
com.android.phone
com.android.systemui
com.tencent.soter.soterserver
com.miui.securitycenter
EOF
    fi
    awk '{
        line = $0
        gsub(/[ \r\t]/, "", line)
        if (line == "" || line ~ /^#/) next
        clean = line
        gsub(/[!?]+$/, "", clean)
        if (clean == "android" || clean == "system_server" ||
            clean == "com.android.phone" || clean == "com.android.systemui") next
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
    }' "$target" | awk 'NR == FNR { blocked[$1] = 1; next }
        { package = $0; sub(/[!?]+$/, "", package); if (!(package in blocked)) print }
    ' "$tee_state/.system-packages" - > "$temp_target" && mv -f "$temp_target" "$target"
    rm -f "$tee_state/.system-packages"

    # Retain force-generation ('!') for critical attestation consumers across all devices
    for p in com.android.vending com.google.android.gms com.google.android.gsf gr.nikolasspyr.integritycheck io.github.vvb2060.keyattestation io.github.qwq233.keyattestation com.henrikhermansen.playintegritychecker rikka.safetynetchecker; do
        sed -i "s/^${p}[!?]*\$/${p}!/" "$target" 2>/dev/null || true
    done

    sed -i 's/^com\.eltavine\.duckdetector[!?]*$/com.eltavine.duckdetector/' "$target" 2>/dev/null || true

    # Set boot_props_mode to force so TEESimulator spoofs ro.boot props for target processes
    if [ ! -f "$tee_state/boot_props_mode" ]; then
        printf 'force\n' > "$tee_state/boot_props_mode"
        chmod 600 "$tee_state/boot_props_mode"
    fi

    find_resetprop() {
        for p in $(which resetprop 2>/dev/null) \
                 /data/adb/magisk/resetprop \
                 /data/adb/ksu/bin/resetprop \
                 /data/adb/ap/bin/resetprop \
                 /product/bin/resetprop \
                 /system/bin/resetprop \
                 /system/xbin/resetprop \
                 /sbin/resetprop; do
            if [ -x "$p" ]; then
                printf '%s\n' "$p"
                return 0
            fi
        done
        command -v resetprop 2>/dev/null || echo "resetprop"
    }

    apply_universal_security_props() {
        local rp
        rp="$(find_resetprop)"
        command -v "$rp" >/dev/null 2>&1 || [ -x "$rp" ] || return 0

        # Samsung Knox / Warranty
        "$rp" -n ro.boot.warranty_bit 0 2>/dev/null || true
        "$rp" -n ro.warranty_bit 0 2>/dev/null || true
        "$rp" -n ro.vendor.boot.warranty_bit 0 2>/dev/null || true
        "$rp" -n ro.vendor.warranty_bit 0 2>/dev/null || true

        # Realme / Oppo
        "$rp" -n ro.boot.realmebootstate green 2>/dev/null || true
        "$rp" -n ro.boot.realme.lockstate 1 2>/dev/null || true

        # OnePlus
        "$rp" -n ro.is_ever_orange 0 2>/dev/null || true

        # Verified boot / security state (universal for all devices)
        "$rp" -n ro.boot.verifiedbootstate green 2>/dev/null || true
        "$rp" -n vendor.boot.verifiedbootstate green 2>/dev/null || true
        "$rp" -n ro.boot.flash.locked 1 2>/dev/null || true
        "$rp" -n ro.boot.vbmeta.device_state locked 2>/dev/null || true
        "$rp" -n vendor.boot.vbmeta.device_state locked 2>/dev/null || true
        "$rp" -n ro.secureboot.lockstate locked 2>/dev/null || true
        "$rp" -n ro.secureboot.devicelock 1 2>/dev/null || true
        "$rp" -n ro.boot.veritymode enforcing 2>/dev/null || true
        "$rp" -n ro.boot.veritymode.managed yes 2>/dev/null || true
        "$rp" -n ro.boot.force_normal_boot 1 2>/dev/null || true
        "$rp" -n ro.boot.secureboot enabled 2>/dev/null || true
        "$rp" -n ro.debuggable 0 2>/dev/null || true
        "$rp" -n ro.force.debuggable 0 2>/dev/null || true
        "$rp" -n ro.secure 1 2>/dev/null || true
        "$rp" -n ro.adb.secure 1 2>/dev/null || true
        "$rp" -n ro.build.type user 2>/dev/null || true
        "$rp" -n ro.build.tags release-keys 2>/dev/null || true
        "$rp" -n ro.build.user android-build 2>/dev/null || true
        "$rp" -n ro.build.host google.com 2>/dev/null || true
        "$rp" -n ro.com.google.clientidbase android-google 2>/dev/null || true
        "$rp" -n ro.oem_unlock_supported 0 2>/dev/null || true
        "$rp" -n sys.oem_unlock_allowed 0 2>/dev/null || true

        # Emulation flags
        "$rp" -n ro.kernel.qemu 0 2>/dev/null || true
        "$rp" -n ro.boot.qemu 0 2>/dev/null || true
        "$rp" -n ro.hardware.virtual_device 0 2>/dev/null || true

        # Delete verified boot error flags
        "$rp" -d ro.boot.verifiedbooterror 2>/dev/null || true
        "$rp" -d ro.boot.verifyerrorpart 2>/dev/null || true

        local conf="$state/pif.conf"
        if [ -f "$conf" ]; then
            local fp brand dev manuf model prod id patch api_level incremental
            brand="$(grep '^BRAND=' "$conf" | head -n1 | cut -d= -f2- | tr -d '\r\n ')"
            dev="$(grep '^DEVICE=' "$conf" | head -n1 | cut -d= -f2- | tr -d '\r\n ')"
            manuf="$(grep '^MANUFACTURER=' "$conf" | head -n1 | cut -d= -f2- | tr -d '\r\n ')"
            model="$(grep '^MODEL=' "$conf" | head -n1 | cut -d= -f2- | tr -d '\r\n')"
            prod="$(grep '^PRODUCT=' "$conf" | head -n1 | cut -d= -f2- | tr -d '\r\n ')"
            fp="$(grep '^FINGERPRINT=' "$conf" | head -n1 | cut -d= -f2- | tr -d '\r\n ')"
            id="$(grep '^ID=' "$conf" | head -n1 | cut -d= -f2- | tr -d '\r\n ')"
            patch="$(grep '^SECURITY_PATCH=' "$conf" | head -n1 | cut -d= -f2- | tr -d '\r\n ')"
            api_level="$(grep '^DEVICE_INITIAL_SDK_INT=' "$conf" | head -n1 | cut -d= -f2- | tr -d '\r\n ')"
            incremental="$(grep '^INCREMENTAL=' "$conf" | head -n1 | cut -d= -f2- | tr -d '\r\n ')"
            [ -z "$incremental" ] && [ -n "$fp" ] && incremental="$(printf '%s' "$fp" | cut -d: -f2 | cut -d/ -f3 | tr -d '\r\n ')"

            [ -n "$brand" ] || brand="google"
            [ -n "$dev" ] || dev="tegu"
            [ -n "$manuf" ] || manuf="Google"
            [ -n "$model" ] || model="Pixel 9a"
            [ -n "$prod" ] || prod="tegu_beta"

            for prefix in ro.product ro.product.bootimage ro.product.odm ro.product.odm_dlkm ro.product.product ro.product.system ro.product.system_dlkm ro.product.system_ext ro.product.vendor ro.product.vendor_dlkm; do
                "$rp" -n "${prefix}.brand" "$brand" 2>/dev/null || true
                "$rp" -n "${prefix}.device" "$dev" 2>/dev/null || true
                "$rp" -n "${prefix}.manufacturer" "$manuf" 2>/dev/null || true
                "$rp" -n "${prefix}.model" "$model" 2>/dev/null || true
                "$rp" -n "${prefix}.name" "$prod" 2>/dev/null || true
            done

            if [ -n "$fp" ]; then
                for prefix in ro.build ro.bootimage.build ro.odm.build ro.odm_dlkm.build ro.product.build ro.system.build ro.system_dlkm.build ro.system_ext.build ro.vendor.build ro.vendor_dlkm.build; do
                    "$rp" -n "${prefix}.fingerprint" "$fp" 2>/dev/null || true
                done
            fi

            [ -n "$id" ] && { "$rp" -n ro.build.id "$id" 2>/dev/null || true; "$rp" -n ro.build.display.id "$id" 2>/dev/null || true; }
            [ -n "$dev" ] && { "$rp" -n ro.build.product "$dev" 2>/dev/null || true; "$rp" -n ro.boot.hardware.sku "$dev" 2>/dev/null || true; "$rp" -n ro.boot.product.hardware.sku "$dev" 2>/dev/null || true; }
            [ -n "$prod" ] && "$rp" -n ro.build.flavor "${prod}-user" 2>/dev/null || true
            [ -n "$patch" ] && "$rp" -n ro.build.version.security_patch "$patch" 2>/dev/null || true
            [ -n "$incremental" ] && "$rp" -n ro.build.version.incremental "$incremental" 2>/dev/null || true

            if [ -n "$api_level" ]; then
                "$rp" -n ro.product.first_api_level "$api_level" 2>/dev/null || true
                "$rp" -n ro.build.version.first_api_level "$api_level" 2>/dev/null || true
                "$rp" -n ro.board.first_api_level "$api_level" 2>/dev/null || true
            fi
        fi

        getprop 2>/dev/null | sed 's/^\[//; s/\].*//' | grep -iE 'lineage|evolution|crdroid|arrow|mistos|axion|infinity|pixelos|rising|lunaris|halcyon|havoc|alphadroid|avium|bliss|calyx|derpfest|graphene|lmodroid|lumine|matrixx|sakura|statix|superior|clover|witaqua|yaap|mica|aospa|paranoid|omni|resurrection|aicp|corvus|syberia|elixir|nusantara|spark|voltage|cherish|octavi|carbon|dirtyunicorns|pixys|posp|revenge|descendant|fluid|dotos|stag|potato|colt|miku|baikal|droidx|ancient|xdroid|exthm|radiation|apollo|m2007j3sg' | while read -r p; do
            case "$p" in
                init.svc.*|ro.boottime.*) continue ;;
                ro.product.*|ro.*.fingerprint) continue ;;
                *) "$rp" -d "$p" 2>/dev/null || true ;;
            esac
        done

        for p in ro.modversion ro.lineage.version ro.lineage.build.version ro.lineage.display.version \
                 ro.lineagelegal.url ro.build.lineage.version ro.custom.version ro.rom.version \
                 ro.boot.project_codename persist.vendor.camera.privapp.list ro.boot.cert \
                 ro.vendor.radio.midevice ro.hardware.audio.primary vendor.camera.aux.packagelist; do
            "$rp" -d "$p" 2>/dev/null || true
        done

        "$rp" -c 2>/dev/null || true
    }
    apply_universal_security_props || true

    patch="$(read_profile_value SECURITY_PATCH)"
    [ -n "$patch" ] || patch="$(getprop ro.build.version.security_patch 2>/dev/null)"
    if [ -z "$patch" ]; then
        c_year=$(date +%Y 2>/dev/null || echo 2026)
        c_month=$(date +%m 2>/dev/null || echo 09)
        patch="${c_year}-${c_month}-05"
    fi
    cat > "$tee_state/security_patch.txt" <<EOF
system=$patch
boot=$patch
vendor=$patch
EOF
    chmod 600 "$tee_state/security_patch.txt"

    [ -f "$tee_state/hbk" ] || head -c 32 /dev/urandom > "$tee_state/hbk"
    for item in "$tee_state"/*; do
        [ -e "$item" ] || continue
        if [ -d "$item" ]; then
            chmod 700 "$item"
        else
            chmod 600 "$item"
        fi
    done

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
        fi
        return 0
    fi
    rm -f "$run/.pid" "$run/.pid-start" "$run/.pid-boot"
    printf 'starting\n' > "$state/tee-status"
    : > "$state/tee-unavailable"
    local keystore keystore_start server server_start
    local wait_k=0
    while [ "$wait_k" -lt 30 ]; do
        keystore="$(pidof keystore2 2>/dev/null || pidof keystore 2>/dev/null)"
        keystore_start="$(worker_process_start "$keystore")"
        server="$(pidof system_server 2>/dev/null)"
        server_start="$(worker_process_start "$server")"
        [ -n "$keystore_start" ] && [ -n "$server_start" ] && break
        sleep 1
        wait_k=$((wait_k + 1))
    done
    [ -n "$keystore_start" ] && [ -n "$server_start" ] || return 1
    printf '%s\n' "$keystore" > "$run/.keystore-pid"
    printf '%s\n' "$keystore_start" > "$run/.keystore-start"
    [ ! -f "$run/startup.log" ] || mv -f "$run/startup.log" "$run/startup.previous.log"
    (cd "$run" && exec ./supervisor ./daemon "$run" </dev/null >startup.log 2>&1) &
    pid="$!"
    printf '%s\n' "$pid" > "$run/.pid"
    pid_start="$(worker_process_start "$pid")"
    printf '%s\n' "$pid_start" > "$run/.pid-start"
    printf '%s\n' "$boot_id" > "$run/.pid-boot"

    (
        local attempt=0 failed=0
        while [ "$attempt" -lt 30 ]; do
            sleep 2
            if ! worker_is_current "$keystore" "$keystore_start" "$boot_id" ||
                ! worker_is_current "$server" "$server_start" "$boot_id"; then
                failed=1
                break
            fi
            attempt=$((attempt + 1))
        done
        sync_vbmeta_digest || true
        healthy="$(find_tee_supervisor "$run")"
        if [ "$failed" = 0 ] && [ -n "$healthy" ] && remember_tee_supervisor "$run" "$healthy" &&
            grep -qF libTEESimulator.so "/proc/$keystore/maps"; then
            rm -f "$state/tee-unavailable"
            printf 'ready\n' > "$state/tee-status"
        else
            worker_is_current "$pid" "$pid_start" "$boot_id" && worker_stop_tree "$pid" "$pid_start"
            rm -f "$run/.pid" "$run/.pid-start" "$run/.pid-boot"
            : > "$state/tee-unavailable"
            chmod 600 "$state/tee-unavailable"
            printf 'startup-failed\n' > "$state/tee-status"
            cp "$runtime/payload.id" "$state/tee-failed-payload"
        fi
        while [ "$failed" = 0 ] && [ -n "$healthy" ] && [ ! -f "$state/tee-unavailable" ]; do
            sleep 30
            if ! worker_is_current "$keystore" "$keystore_start" "$boot_id" ||
                ! worker_is_current "$server" "$server_start" "$boot_id" ||
                ! worker_is_current "$pid" "$pid_start" "$boot_id"; then
                worker_is_current "$pid" "$pid_start" "$boot_id" && worker_stop_tree "$pid" "$pid_start"
                : > "$state/tee-unavailable"
                printf 'service-restarted\n' > "$state/tee-status"
                cp "$runtime/payload.id" "$state/tee-failed-payload"
                break
            fi
        done
        rm -f "$run/.health-pid" "$run/.health-start" "$run/.health-boot"
    ) &
    health_pid="$!"
    printf '%s\n' "$health_pid" > "$run/.health-pid"
    awk '{print $22}' "/proc/$health_pid/stat" > "$run/.health-start" 2>/dev/null
    printf '%s\n' "$boot_id" > "$run/.health-boot"
}

start_tee
refresh_keybox
sync_vbmeta_digest || true

# Wait for boot completion and clear boot-pending so future boots remain enabled
system_server="$(pidof system_server 2>/dev/null)"
server_start="$(worker_process_start "$system_server")"
if [ -n "$server_start" ]; then
    attempt=0
    while [ "$attempt" -lt 30 ]; do
        [ -f "$state/disabled" ] && exit 0
        [ "$(getprop sys.boot_completed)" = "1" ] && break
        worker_is_current "$system_server" "$server_start" "$boot_id" || exit 0
        sleep 2
        attempt=$((attempt + 1))
    done
    [ "$(cat "$state/boot-pending" 2>/dev/null)" != "$boot_id" ] || rm -f "$state/boot-pending"
fi
