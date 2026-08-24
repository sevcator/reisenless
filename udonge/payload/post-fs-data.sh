#!/system/bin/sh

umask 077
root=/data/adb/udonge
runtime=$root/runtime
state=$root/state

mkdir -p "$state"
chmod 700 "$root" "$state"
rm -f "$state/vbmeta_hash" "$state/pif_urls.conf"

for name in targets.conf props.conf pif.conf keybox_urls.conf rom_keywords.conf; do
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
    command -v resetprop >/dev/null 2>&1 || return 1

    resetprop -n ro.boot.verifiedbootstate green
    resetprop -n ro.boot.flash.locked 1
    resetprop -n ro.boot.vbmeta.device_state locked

    for name in \
        ro.build.type \
        ro.product.build.type \
        ro.system.build.type \
        ro.system_ext.build.type \
        ro.vendor.build.type \
        ro.vendor_dlkm.build.type \
        ro.odm.build.type \
        ro.bootimage.build.type; do
        resetprop -n "$name" user
    done

    for name in ro.build.flavor ro.product.build.flavor; do
        value="$(resetprop "$name" 2>/dev/null)"
        case "$value" in
            *userdebug*) resetprop -n "$name" "$(printf '%s' "$value" | sed 's/userdebug/user/g')" ;;
        esac
    done

    digest="$(sed -n 's/^ro\.boot\.vbmeta\.digest=//p' "$state/props.conf" 2>/dev/null | tail -n 1)"
    [ "${#digest}" = 64 ] && resetprop -n ro.boot.vbmeta.digest "$digest"
}

normalize_boot_properties || true

sanitize_rom_traces() {
    [ -f "$state/rom_keywords.conf" ] || return 0
    command -v resetprop >/dev/null 2>&1 || return 1

    while IFS= read -r keyword; do

        [ "${#keyword}" -ge 3 ] || continue



        getprop | sed -n "s/^\\[\\([^]]*${keyword}[^]]*\\)\\]:.*/\\1/p" | \
        while IFS= read -r pname; do
            resetprop --delete "$pname" 2>/dev/null || true
        done



        for fname in ro.build.flavor ro.product.build.flavor; do
            val="$(resetprop "$fname" 2>/dev/null)"
            case "$val" in
                *"$keyword"*)
                    clean="${val##*-}"
                    [ -n "$clean" ] || clean="user"
                    resetprop -n "$fname" "$clean" 2>/dev/null || true
                    ;;
            esac
        done
    done < "$state/rom_keywords.conf"
}
sanitize_rom_traces || true

chmod 600 "$state/.certified" "$state/.keybox-checked" 2>/dev/null || true

if grep -qF 'google/tegu_beta/tegu:CANARY/ZP11.260618.005/15760424' "$state/pif.conf"; then
    cp "$runtime/defaults/pif.conf" "$state/pif.conf"
    chmod 600 "$state/pif.conf"
fi
