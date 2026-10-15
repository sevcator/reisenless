#!/system/bin/sh

umask 077
root=UDONGE_ROOT_STUB
[ -d "$root" ] || root="$(cd "$(dirname "$0")/.." && pwd)"
runtime=$root/runtime
state=$root/state
. "$runtime/worker.sh" || exit 1

if [ ! -f "$state/disabled" ] && [ ! -f "$state/pending-reboot" ]; then
    [ -f "$state/enabled" ] || { : > "$state/enabled"; chmod 600 "$state/enabled"; }
else
    exit 0
fi

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
    resetprop -n ro.boot.vbmeta.digest "$digest" 2>/dev/null || true
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
fi


# Neutralize OEM checknv daemon so it cannot reboot into recovery on transient modem events
if [ -f /system_ext/bin/checknv ]; then
    mount -o bind /system/bin/true /system_ext/bin/checknv 2>/dev/null || true
fi
