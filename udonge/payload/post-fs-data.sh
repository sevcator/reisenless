#!/system/bin/sh

umask 077
root=/data/adb/udonge
[ -d "$root" ] || root="$(cd "$(dirname "$0")/.." && pwd)"
runtime=$root/runtime
state=$root/state
. "$runtime/worker.sh" || exit 1

[ -f "$state/enabled" ] && [ ! -f "$state/disabled" ] &&
    [ ! -f "$state/pending-reboot" ] || exit 0

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
    sed -i 's/[!?]*$//' "$state/targets.conf" 2>/dev/null || true
fi

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

if grep -qF 'google/tegu_beta/tegu:CANARY/ZP11.260618.005/15760424' "$state/pif.conf" 2>/dev/null || \
   grep -qF 'CP2A.260705.006' "$state/pif.conf" 2>/dev/null || \
   grep -qF 'tegu:17' "$state/pif.conf" 2>/dev/null; then
    cp "$runtime/defaults/pif.conf" "$state/pif.conf"
    chmod 600 "$state/pif.conf"
fi
