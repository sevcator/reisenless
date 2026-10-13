#!/system/bin/sh

umask 077
root=UDONGE_ROOT_STUB
[ -d "$root" ] || root="$(cd "$(dirname "$0")/.." && pwd)"
runtime=$root/runtime
state=$root/state
. "$runtime/worker.sh" || exit 1

[ -f "$state/enabled" ] && [ ! -f "$state/disabled" ] &&
    [ ! -f "$state/pending-reboot" ] || exit 0

mkdir -p "$state"
chmod 700 "$root" "$state"
rm -f "$state/vbmeta_hash" "$state/pif_urls.conf"

for name in targets.conf props.conf pif.conf keybox_urls.conf boot_key.bin; do
    if [ ! -s "$state/$name" ] || { [ "$name" = keybox_urls.conf ] && ! grep -q '^https://' "$state/$name"; }; then
        cp "$runtime/defaults/$name" "$state/$name"
        chmod 600 "$state/$name"
    fi
done
sync_oem_boot_key() {
    local vbmeta="/dev/block/by-name/vbmeta"
    if [ ! -e "$vbmeta" ]; then
        local slot
        slot="$(getprop ro.boot.slot_suffix 2>/dev/null)"
        [ -n "$slot" ] && vbmeta="/dev/block/by-name/vbmeta${slot}"
    fi
    [ -e "$vbmeta" ] || return 0
    [ "$(head -c 4 "$vbmeta" 2>/dev/null)" = "AVB0" ] || return 0
    local a_hex pko_hex pks_hex
    a_hex="$(xxd -p -s 16 -l 4 "$vbmeta" 2>/dev/null | tr -d '\r\n')"
    pko_hex="$(xxd -p -s 68 -l 4 "$vbmeta" 2>/dev/null | tr -d '\r\n')"
    pks_hex="$(xxd -p -s 76 -l 4 "$vbmeta" 2>/dev/null | tr -d '\r\n')"
    [ -n "$a_hex" ] && [ -n "$pko_hex" ] && [ -n "$pks_hex" ] || return 0
    local auth=$((16#$a_hex)) pko=$((16#$pko_hex)) pks=$((16#$pks_hex))
    [ "$pks" -gt 0 ] 2>/dev/null || return 0
    local pk_start=$((256 + auth + pko))
    dd if="$vbmeta" bs=1 skip="$pk_start" count="$pks" 2>/dev/null | sha256sum | awk '{print $1}' | xxd -r -p > "$state/boot_key.bin" 2>/dev/null || true
    chmod 600 "$state/boot_key.bin" 2>/dev/null || true
}

sync_oem_boot_key || true


if [ -f "$runtime/defaults/targets.conf" ] && [ -f "$state/targets.conf" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
        [ -n "$line" ] || continue
        case "$line" in '#'*) continue ;; esac
        grep -qxF "$line" "$state/targets.conf" 2>/dev/null || printf '%s\n' "$line" >> "$state/targets.conf"
    done < "$runtime/defaults/targets.conf"
fi

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

chmod 600 "$state/.certified" "$state/.keybox-checked" 2>/dev/null || true

if [ -f "$state/pif.conf" ]; then
    v="1.0"
    [ -f "$runtime/version" ] && v="$(cat "$runtime/version" 2>/dev/null)"
    pif_h="$(sha256sum "$state/pif.conf" 2>/dev/null | awk '{print $1}')"
    if [ -n "$pif_h" ]; then
        printf '%s_%s\n' "$v" "$pif_h" > "$state/.certified"
        chmod 600 "$state/.certified" 2>/dev/null || true
    fi
    # Ensure tee-broken flag exists for universal attestation support
    [ -f "$state/tee-broken" ] || { : > "$state/tee-broken"; chmod 600 "$state/tee-broken"; }
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

    # Read target values from pif.conf if present
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

        # Critical for universal Device Integrity: allow Basic/fallback attestation on non-hardware-enforced tiers
        if [ -n "$api_level" ]; then
            "$rp" -n ro.product.first_api_level "$api_level" 2>/dev/null || true
            "$rp" -n ro.build.version.first_api_level "$api_level" 2>/dev/null || true
            "$rp" -n ro.board.first_api_level "$api_level" 2>/dev/null || true
        fi
    fi

    # Remove Custom ROM / Lineage / OEM leaked identifiers universally
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

# Neutralize OEM checknv daemon so it cannot reboot into recovery on transient modem events
if [ -f /system_ext/bin/checknv ]; then
    mount -o bind /system/bin/true /system_ext/bin/checknv 2>/dev/null || true
fi
