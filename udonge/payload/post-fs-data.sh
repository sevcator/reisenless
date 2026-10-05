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
    if [ ! -s "$state/$name" ] || { [ "$name" = keybox_urls.conf ] && ! grep -q '^https://' "$state/$name"; }; then
        cp "$runtime/defaults/$name" "$state/$name"
        chmod 600 "$state/$name"
    fi
done

if [ -f "$state/targets.conf" ]; then
    sed -i 's/^com\.android\.vending!$/com.android.vending/' "$state/targets.conf" 2>/dev/null || true
    sed -i 's/^com\.google\.android\.gms!$/com.google.android.gms/' "$state/targets.conf" 2>/dev/null || true
    sed -i 's/^com\.google\.android\.gsf!$/com.google.android.gsf/' "$state/targets.conf" 2>/dev/null || true
    sed -i 's/^gr\.nikolasspyr\.integritycheck!$/gr.nikolasspyr.integritycheck?/' "$state/targets.conf" 2>/dev/null || true
    sed -i 's/^io\.github\.vvb2060\.keyattestation!$/io.github.vvb2060.keyattestation?/' "$state/targets.conf" 2>/dev/null || true
    sed -i 's/^com\.eltavine\.duckdetector!$/com.eltavine.duckdetector?/' "$state/targets.conf" 2>/dev/null || true
fi

if [ -f "$runtime/defaults/targets.conf" ] && [ -f "$state/targets.conf" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
        [ -n "$line" ] || continue
        case "$line" in '#'*) continue ;; esac
        grep -qxF "$line" "$state/targets.conf" 2>/dev/null || printf '%s\n' "$line" >> "$state/targets.conf"
    done < "$runtime/defaults/targets.conf"
fi

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
        for cand in /sbin/resetprop /debug_ramdisk/resetprop "$root/../resetprop" "$root/../"*; do
            if [ -x "$cand" ]; then
                if "$cand" -v >/dev/null 2>&1; then
                    RP="$cand"
                    break
                elif "$cand" resetprop -v >/dev/null 2>&1; then
                    RP="$cand resetprop"
                    break
                fi
            fi
        done
        [ "$RP" = "resetprop" ] && return 1
    fi

    $RP -n ro.boot.verifiedbootstate green
    $RP -n ro.boot.flash.locked 1
    $RP -n ro.boot.vbmeta.device_state locked
    $RP -n ro.boot.veritymode enforcing
    $RP -n ro.boot.veritymode.managed yes
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
    $RP -n ro.adb.secure 1

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
    fi

    # Clean up leftover root artifacts in /data/local/tmp that trigger DroidGuard AVC denials
    rm -f /data/local/tmp/su /data/local/tmp/su-old-apk /data/local/tmp/*su* 2>/dev/null || true

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

if grep -qF 'google/tegu_beta/tegu:CANARY/ZP11.260618.005/15760424' "$state/pif.conf" 2>/dev/null || \
   grep -qF 'CP2A.260705.006' "$state/pif.conf" 2>/dev/null || \
   grep -qF 'tegu:17' "$state/pif.conf" 2>/dev/null; then
    cp "$runtime/defaults/pif.conf" "$state/pif.conf"
    chmod 600 "$state/pif.conf"
fi
