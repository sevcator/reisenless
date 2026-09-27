#!/system/bin/sh

umask 077
root=/data/adb/udonge
[ -d "$root" ] || root="$(cd "$(dirname "$0")/.." && pwd)"
runtime=$root/runtime
state=$root/state

[ -f "$state/disabled" ] && exit 0

mkdir -p "$state"
chmod 700 "$root" "$state"
rm -f "$state/vbmeta_hash" "$state/pif_urls.conf"

for name in targets.conf props.conf pif.conf keybox_urls.conf; do
    if [ ! -f "$state/$name" ] || { [ "$name" = keybox_urls.conf ] && ! grep -q '^https://' "$state/$name"; }; then
        cp "$runtime/defaults/$name" "$state/$name"
        chmod 600 "$state/$name"
    fi
done

sync_vbmeta_digest() {
    [ "$(wc -c < "$state/boot_hash.bin" 2>/dev/null)" = 32 ] || return 1
    digest="$(od -An -tx1 -v "$state/boot_hash.bin" 2>/dev/null | tr -d ' \n')"
    [ "${#digest}" = 64 ] || return 1
    temp="$state/.props.$$"
    sed '/^ro\.boot\.vbmeta\.digest=/d' "$state/props.conf" 2>/dev/null > "$temp"
    printf 'ro.boot.vbmeta.digest=%s\n' "$digest" >> "$temp"
    chmod 600 "$temp"
    mv -f "$temp" "$state/props.conf"
}

sync_vbmeta_digest || true

normalize_boot_properties() {
    RP="resetprop"
    if ! command -v resetprop >/dev/null 2>&1; then
        for cand in /sbin/resetprop /debug_ramdisk/resetprop "$root/../resetprop"; do
            if [ -x "$cand" ]; then
                RP="$cand"
                break
            fi
        done
        [ "$RP" = "resetprop" ] && return 1
    fi

    $RP -n ro.boot.verifiedbootstate green
    $RP -n ro.boot.flash.locked 1
    $RP -n ro.boot.vbmeta.device_state locked
    $RP -n ro.boot.selinux enforcing
    $RP -n ro.secureboot.lockstate locked
    $RP -n vendor.boot.verifiedbootstate green
    $RP -n vendor.boot.vbmeta.device_state locked
    $RP -n ro.is_ever_orange 0
    $RP -n ro.debuggable 0
    $RP -n ro.force.debuggable 0
    $RP -n ro.secure 1
    $RP -n service.adb.root 0
    $RP -n sys.oem_unlock_allowed 0
    $RP -n ro.boot.warranty_bit 0
    $RP -n ro.warranty_bit 0
    $RP -n ro.vendor.boot.warranty_bit 0
    $RP -n ro.vendor.warranty_bit 0
    $RP -n ro.boot.realmebootstate green
    $RP -n ro.boot.realme.lockstate 1

    for name in \
        ro.build.type \
        ro.product.build.type \
        ro.system.build.type \
        ro.system_ext.build.type \
        ro.vendor.build.type \
        ro.vendor_dlkm.build.type \
        ro.odm.build.type \
        ro.bootimage.build.type; do
        $RP -n "$name" user
    done

    for name in ro.build.flavor ro.product.build.flavor; do
        value="$($RP "$name" 2>/dev/null)"
        case "$value" in
            *userdebug*) $RP -n "$name" "$(printf '%s' "$value" | sed 's/userdebug/user/g')" ;;
            eng|*-eng|*_eng) $RP -n "$name" user ;;
        esac
    done

    digest="$(sed -n 's/^ro\.boot\.vbmeta\.digest=//p' "$state/props.conf" 2>/dev/null | tail -n 1)"
    [ "${#digest}" = 64 ] && $RP -n ro.boot.vbmeta.digest "$digest"

    # Ensure USB debugging configuration persists across reboots
    cfg="$(getprop persist.sys.usb.config 2>/dev/null)"
    case "$cfg" in
        *adb*) ;;
        none|""|mtp) setprop persist.sys.usb.config mtp,adb 2>/dev/null ;;
        *) setprop persist.sys.usb.config "${cfg},adb" 2>/dev/null ;;
    esac
    setprop persist.sys.oppo.usbactive 1 2>/dev/null

    for settings_xml in /data/system/users/0/settings_global.xml /data/system/users/*/settings_global.xml; do
        if [ -f "$settings_xml" ]; then
            sed -i 's/name="adb_enabled" value="0"/name="adb_enabled" value="1"/' "$settings_xml" 2>/dev/null || true
            sed -i 's/name="usb_debugging_auto_disabled" value="1"/name="usb_debugging_auto_disabled" value="0"/' "$settings_xml" 2>/dev/null || true
        fi
    done

    # Clear VPN-revealing properties early
    for prop in $(getprop 2>/dev/null | grep -oE '\[net\.vpn[^]]*\]' | tr -d '[]'); do
        [ -n "$prop" ] && $RP -d "$prop" 2>/dev/null || true
    done
}

normalize_boot_properties || true

# Disable broken vendor Soter HAL service that triggers attestation anomalies on unlocked bootloaders
stop soter-1-0 2>/dev/null || true
setprop ctl.stop soter-1-0 2>/dev/null || true

chmod 600 "$state/.certified" "$state/.keybox-checked" 2>/dev/null || true

# Prevent MIUI ThemeCompatibilityLoader crash in app_process/TEESimulator
if [ ! -f /data/system/theme_config/theme_compatibility.xml ]; then
    mkdir -p /data/system/theme_config 2>/dev/null
    touch /data/system/theme_config/theme_compatibility.xml 2>/dev/null
    chmod 755 /data/system/theme_config 2>/dev/null || true
    chmod 644 /data/system/theme_config/theme_compatibility.xml 2>/dev/null || true
    chown system:system /data/system/theme_config /data/system/theme_config/theme_compatibility.xml 2>/dev/null || true
fi

if grep -qF 'google/tegu_beta/tegu:CANARY/ZP11.260618.005/15760424' "$state/pif.conf"; then
    cp "$runtime/defaults/pif.conf" "$state/pif.conf"
    chmod 600 "$state/pif.conf"
fi
