#!/system/bin/sh

umask 077

find_root() {

    local parent
    parent="$(cd "$(dirname "$0")/.." && pwd 2>/dev/null)"

    if [ -f "$parent/runtime/service.sh" ] ||
       { [ -d "$parent/state" ] && [ -f "$parent/state/keybox.xml" ]; }; then
        printf '%s' "$parent"
        return 0
    fi

    local p cwd
    for p in $(pidof TEESimulator supervisor 2>/dev/null); do
        cwd="$(readlink "/proc/$p/cwd" 2>/dev/null)"
        case "$cwd" in
            */tee-runtime)
                printf '%s' "${cwd%/tee-runtime}"
                return 0
                ;;
        esac
    done

    local base sub
    for base in /data/.* /data/*; do
        [ -d "$base" ] || continue
        case "$base" in */.|*/..) continue ;; esac
        for sub in "$base"/.* "$base"/*; do
            [ -d "$sub" ] || continue
            case "$sub" in */.|*/..) continue ;; esac
            if [ -d "$sub/state" ] && [ -f "$sub/state/keybox.xml" ]; then
                printf '%s' "$sub"
                return 0
            fi
        done
    done
    for base in /data/.* /data/*; do
        [ -d "$base" ] || continue
        case "$base" in */.|*/..) continue ;; esac
        for sub in "$base"/.* "$base"/*; do
            [ -d "$sub" ] || continue
            case "$sub" in */.|*/..) continue ;; esac
            if [ -d "$sub/runtime" ] && [ -f "$sub/runtime/service.sh" ]; then
                printf '%s' "$sub"
                return 0
            fi
        done
    done
    if [ -d "/data/adb/udonge" ]; then
        printf '%s' "/data/adb/udonge"
        return 0
    fi
    printf '%s' "$parent"
}

root="$(find_root)"
runtime="$root/runtime"
state="$root/state"
tee_state="$state"
work="$root/keybox-heal-work.$$"
crl_cache="$state/.crl_status.json"
boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
. "$runtime/worker.sh" || exit 1

log() {
    printf '[KeyboxHeal] %s\n' "$*"
}

log_err() {
    printf '[KeyboxHeal:ERROR] %s\n' "$*" >&2
}

candidate_urls() {
    awk '/^https?:\/\// && !seen[$0]++ { print; if (++count == 16) exit }' "$1"
}

fetch_candidate() {
    local url destination cache key raw headers temp code etag status
    url="$1"
    destination="$2"
    cache="$state/.candidate-cache"
    key="$(printf '%s' "$url" | sha256sum | awk '{print $1}')"
    [ -n "$key" ] || return 1
    mkdir -p "$cache" "$work"
    raw="$cache/$key.raw"
    headers="$work/$key.headers"
    temp="$work/$key.download"
    set -- -sSL --connect-timeout 8 -m 15 --max-filesize 1048576
    if [ -s "$raw" ]; then
        set -- "$@" -z "$raw"
        etag="$(cat "$cache/$key.etag" 2>/dev/null)"
        [ -z "$etag" ] || set -- "$@" -H "If-None-Match: $etag"
    fi
    code="$(curl "$@" -D "$headers" -o "$temp" -w '%{http_code}' "$url" 2>/dev/null)"
    status=$?
    [ "$status" -eq 0 ] || code=000
    case "$code" in
        200)
            if [ -s "$temp" ]; then
                mv -f "$temp" "$raw"
                etag="$(sed -n 's/^[Ee][Tt][Aa][Gg]:[[:space:]]*//p' "$headers" 2>/dev/null | tr -d '\r' | tail -n 1)"
                printf '%s' "$etag" > "$cache/$key.etag"
            fi
            ;;
        304) ;;
        *) rm -f "$temp" ;;
    esac
    rm -f "$temp" "$headers"
    [ -s "$raw" ] && cp -f "$raw" "$destination"
}

verification_cache_hit() {
    local hash recorded context verdict now age lifetime
    hash="$1"
    if [ -z "${verification_context:-}" ]; then
        verification_context="$(
            { sha256sum "$state/pif.conf" "$state/props.conf" "$runtime/payload.id" 2>/dev/null;
              dumpsys package gr.nikolasspyr.integritycheck 2>/dev/null | grep 'versionCode='; } |
                sha256sum | awk '{print $1}'
        )"
    fi
    read -r recorded context verdict 2>/dev/null < "$state/.verification-cache/$hash" || return 1
    [ "$context" = "$verification_context" ] || return 1
    case "$recorded" in ''|*[!0-9]*) return 1 ;; esac
    case "$verdict" in DEVICE) lifetime=86400 ;; ERROR|LAUNCH_FAIL) lifetime=1800 ;; *) return 1 ;; esac
    now="$(date +%s)"
    age=$((now - recorded))
    cached_verdict="$verdict"
    [ "$age" -ge 0 ] && [ "$age" -lt "$lifetime" ]
}

remember_verification() {
    local hash verdict temp
    hash="$1"
    verdict="$2"
    case "$verdict" in DEVICE|ERROR|LAUNCH_FAIL) ;; *) return 0 ;; esac
    mkdir -p "$state/.verification-cache"
    temp="$state/.verification-cache/.$hash.$$"
    printf '%s %s %s\n' "$(date +%s)" "$verification_context" "$verdict" > "$temp"
    mv -f "$temp" "$state/.verification-cache/$hash"
}

is_offline_blacklisted() {
    case "$1" in
        *3207438651777393527*|*03207438651777393527*|*10843619390107158798*|*14765769813626195621159*)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

update_crl_cache() {
    [ -d "$state" ] || mkdir -p "$state"

    local now last_mod
    now=$(date +%s 2>/dev/null || echo 0)
    if [ -f "$crl_cache" ] && [ -s "$crl_cache" ]; then
        last_mod=$(stat -c %Y "$crl_cache" 2>/dev/null || echo 0)
        if [ "$now" -gt 0 ] && [ "$last_mod" -gt 0 ] && [ $((now - last_mod)) -lt 86400 ]; then
            return 0
        fi
    fi

    [ "${crl_attempted:-0}" = 0 ] || return 1
    crl_attempted=1
    if command -v curl >/dev/null 2>&1; then
        curl -sSL --connect-timeout 5 -m 10 "https://android.googleapis.com/attestation/status" -o "$crl_cache.tmp" 2>/dev/null
        if [ -s "$crl_cache.tmp" ] && grep -q '"status"' "$crl_cache.tmp" 2>/dev/null; then
            mv -f "$crl_cache.tmp" "$crl_cache"
            chmod 600 "$crl_cache"
            return 0
        fi
        rm -f "$crl_cache.tmp"
    fi
    return 1
}

extract_serials() {
    local file="$1"
    [ -f "$file" ] || return 1

    awk '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/ {
        if ($0 ~ /BEGIN CERTIFICATE/) { b64 = ""; next }
        if ($0 ~ /END CERTIFICATE/) {
            print b64
            b64 = ""
            next
        }
        gsub(/[ \r\n\t]/, "", $0)
        b64 = b64 $0
    }' "$file" | while IFS= read -r b64_cert; do
        [ -n "$b64_cert" ] || continue
        local hex idx b b_val num_bytes tag slen_hex slen serial clean_serial
        hex=$(printf '%s' "$b64_cert" | base64 -d 2>/dev/null | xxd -p -c 256 | tr -d '\r\n ')
        [ -n "$hex" ] || continue

        idx=3
        b=$(printf '%s' "$hex" | cut -c $idx-$((idx+1)))
        b_val=$((0x$b))
        if [ $((b_val & 0x80)) -ne 0 ]; then
            num_bytes=$((b_val & 0x7f))
            idx=$((idx + 2 + num_bytes * 2))
        else
            idx=$((idx + 2))
        fi

        idx=$((idx + 2))
        b=$(printf '%s' "$hex" | cut -c $idx-$((idx+1)))
        b_val=$((0x$b))
        if [ $((b_val & 0x80)) -ne 0 ]; then
            num_bytes=$((b_val & 0x7f))
            idx=$((idx + 2 + num_bytes * 2))
        else
            idx=$((idx + 2))
        fi

        tag=$(printf '%s' "$hex" | cut -c $idx-$((idx+1)))
        if [ "$tag" = "a0" ]; then
            idx=$((idx + 2))
            b=$(printf '%s' "$hex" | cut -c $idx-$((idx+1)))
            b_val=$((0x$b))
            if [ $((b_val & 0x80)) -ne 0 ]; then
                num_bytes=$((b_val & 0x7f))
                idx=$((idx + 2 + num_bytes * 2))
            else
                idx=$((idx + 2 + b_val * 2))
            fi
            tag=$(printf '%s' "$hex" | cut -c $idx-$((idx+1)))
        fi

        if [ "$tag" = "02" ]; then
            idx=$((idx + 2))
            slen_hex=$(printf '%s' "$hex" | cut -c $idx-$((idx+1)))
            slen=$((0x$slen_hex))
            idx=$((idx + 2))
            serial=$(printf '%s' "$hex" | cut -c $idx-$((idx + slen * 2 - 1)))
            clean_serial=$(printf '%s' "$serial" | sed 's/^00*//')
            [ -n "$clean_serial" ] || clean_serial="0"
            printf '%s\n' "$clean_serial"

            local dec_val
            dec_val=$(printf '%u' "0x${clean_serial}" 2>/dev/null) && \
                [ -n "$dec_val" ] && [ "$dec_val" != "$clean_serial" ] && printf '%s\n' "$dec_val"
        fi
    done
}

is_serial_revoked() {
    local s="$1"
    [ -n "$s" ] || return 1

    if is_offline_blacklisted "$s"; then
        return 0
    fi

    if [ -f "$crl_cache" ] && [ -s "$crl_cache" ]; then
        if grep -qiE "\"${s}\"[[:space:]]*:" "$crl_cache"; then
            return 0
        fi
    fi
    return 1
}

unwrap_payload() {
    local src="$1" dst="$2"
    [ -f "$src" ] || return 1

    mkdir -p "$work"
    local cur="$work/unw_cur.$$" nxt="$work/unw_nxt.$$"
    cp -f "$src" "$cur"

    local iter=0
    while [ "$iter" -lt 15 ]; do
        iter=$((iter + 1))

        if grep -q '<AndroidAttestation' "$cur" 2>/dev/null || grep -q '<Keybox' "$cur" 2>/dev/null; then
            cp -f "$cur" "$dst"
            rm -f "$cur" "$nxt"
            return 0
        fi

        if tr 'A-Za-z' 'N-ZA-Mn-za-m' < "$cur" 2>/dev/null | grep -q '<AndroidAttestation' 2>/dev/null; then
            tr 'A-Za-z' 'N-ZA-Mn-za-m' < "$cur" > "$dst" 2>/dev/null
            rm -f "$cur" "$nxt"
            return 0
        fi

        if command -v xxd >/dev/null 2>&1; then
            if xxd -r -p "$cur" > "$nxt" 2>/dev/null && [ -s "$nxt" ] && [ "$(wc -c < "$nxt" 2>/dev/null || echo 0)" -ge 80 ]; then
                if grep -q '<AndroidAttestation' "$nxt" 2>/dev/null || grep -q '<Keybox' "$nxt" 2>/dev/null; then
                    cp -f "$nxt" "$dst"
                    rm -f "$cur" "$nxt"
                    return 0
                fi
                mv -f "$nxt" "$cur"
                continue
            fi
            rm -f "$nxt"
        fi

        if (tr -d '\r\n ' < "$cur" | base64 -d > "$nxt" 2>/dev/null || base64 -d "$cur" > "$nxt" 2>/dev/null) && [ -s "$nxt" ]; then
            if grep -q '<AndroidAttestation' "$nxt" 2>/dev/null || grep -q '<Keybox' "$nxt" 2>/dev/null; then
                cp -f "$nxt" "$dst"
                rm -f "$cur" "$nxt"
                return 0
            fi
            mv -f "$nxt" "$cur"
            continue
        fi
        rm -f "$nxt"

        if tr '1dgWnocayqxU3r6vA5lCIPYfHmkV08b4tz+KMsp2NQ9LRXihODwSj7BEFJ/ZuGTe' \
              'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/' < "$cur" 2>/dev/null | \
              tr -d '\r\n ' | base64 -d > "$nxt" 2>/dev/null && [ -s "$nxt" ]; then
            if grep -q '<AndroidAttestation' "$nxt" 2>/dev/null || grep -q '<Keybox' "$nxt" 2>/dev/null; then
                cp -f "$nxt" "$dst"
                rm -f "$cur" "$nxt"
                return 0
            fi
            mv -f "$nxt" "$cur"
            continue
        fi
        rm -f "$nxt"

        break
    done

    rm -f "$cur" "$nxt"
    return 1
}

sanitize_keybox() {
    local src="$1" dst="$2"
    [ -f "$src" ] || return 1
    mkdir -p "$work"
    local raw_unwrapped="$work/san_raw.$$"

    if ! unwrap_payload "$src" "$raw_unwrapped"; then
        cp -f "$src" "$raw_unwrapped"
    fi

    sed -e '/^[[:space:]]*<[?]xml[^>]*[?]>[[:space:]]*$/d' \
        -e 's/<Private>/<PrivateKey format="pem">/g' \
        -e 's/<\/Private>/<\/PrivateKey>/g' \
        -e 's/<Certificate>/<Certificate format="pem">/g' "$raw_unwrapped" | \
        tr -d '\r' > "$dst" 2>/dev/null || { rm -f "$raw_unwrapped"; return 1; }
    rm -f "$raw_unwrapped"

    if grep -Fq '<!' "$dst" 2>/dev/null || grep -Fq '<?' "$dst" 2>/dev/null; then
        return 1
    fi
    return 0
}

check_keybox() {
    local file="$1"
    if [ ! -f "$file" ] || [ ! -s "$file" ]; then
        log_err "Keybox file does not exist or is empty: $file"
        return 2
    fi

    if ! grep -q '<AndroidAttestation>' "$file" || \
       ! grep -q '<NumberOfKeyboxes>' "$file" || \
       ! grep -q '<Keybox' "$file" || \
       ! grep -Eq '<Key algorithm="(ecdsa|rsa)"' "$file" || \
       ! grep -Eq -- '-----BEGIN (EC |RSA )?PRIVATE KEY-----' "$file" || \
       ! grep -q -- '-----BEGIN CERTIFICATE-----' "$file" || \
       ! grep -q -- '-----END CERTIFICATE-----' "$file"; then
        log_err "Keybox file has invalid XML schema: $file"
        return 2
    fi

    if grep -qiE '(Mock Google RKP|Mock RKP KeyMint|MOCK_RKP|MOCK|AOSP Software Attestation|CN=Mock)' "$file" 2>/dev/null; then
        log_err "Keybox $file uses AOSP/Mock test cert chain — cannot pass Strong Integrity."
        return 1
    fi

    local today_ymd
    today_ymd=$(date +%Y%m%d 2>/dev/null || echo 20261001)
    local leaf_score
    leaf_score=$(score_keybox "$file")

    if [ "${leaf_score:-1}" -eq 0 ] 2>/dev/null; then
        log_err "Keybox $file leaf certificate is expired — rejecting."
        return 1
    fi

    update_crl_cache 2>/dev/null || true
    local serials count=0 revoked=0
    serials=$(extract_serials "$file")
    if [ -z "$serials" ]; then
        log_err "Could not extract certificate serials from: $file"
        return 2
    fi

    for s in $serials; do
        count=$((count + 1))
        if is_serial_revoked "$s"; then
            log_err "Certificate serial $s is REVOKED by CRL!"
            revoked=$((revoked + 1))
        fi
    done

    if [ "$revoked" -gt 0 ]; then
        log_err "Keybox $file contains $revoked revoked certificate(s)!"
        return 1
    fi

    log "Keybox $file is VALID: $count certificate(s), 0 revoked."
    return 0
}

flush_gms_cache() {
    log "Flushing GMS and Play Integrity attestation caches..."
    am force-stop com.google.android.gms 2>/dev/null || true
    am force-stop com.google.android.gms.unstable 2>/dev/null || true
    am force-stop com.android.vending 2>/dev/null || true
    am force-stop gr.nikolasspyr.integritycheck 2>/dev/null || true
    am force-stop com.eltavine.duckdetector 2>/dev/null || true
    rm -rf "$tee_state/persistent_keys"/* 2>/dev/null || true
    find /data/data/com.google.android.gms/databases -name 'dg.db*' -delete 2>/dev/null || true
}

reload_tee() {
    log "Reloading TEESimulator..."
    local child sup start tee_run args
    tee_run="$root/tee-runtime"
    sup="$(cat "$tee_run/.pid" 2>/dev/null)"
    start="$(cat "$tee_run/.pid-start" 2>/dev/null)"
    if ! worker_is_current "$sup" "$start" "$(cat "$tee_run/.pid-boot" 2>/dev/null)"; then
        sup=""
        for child in $(pidof supervisor 2>/dev/null); do
            [ "$(readlink "/proc/$child/cwd" 2>/dev/null)" = "$tee_run" ] || continue
            args="$(tr '\000' ' ' < "/proc/$child/cmdline" 2>/dev/null)"
            case "$args" in *"./daemon $tee_run"*) sup="$child"; break ;; esac
        done
        start="$(worker_process_start "$sup")"
    fi
    if worker_is_current "$sup" "$start" "$boot_id"; then
        args="$(tr '\000' ' ' < "/proc/$sup/cmdline" 2>/dev/null)"
        case "$args" in *"./daemon $tee_run"*) worker_stop_tree "$sup" "$start" ;; esac
    fi

    sleep 1

    if [ -x "$tee_run/supervisor" ] && [ -x "$tee_run/daemon" ]; then

        (
            (cd "$tee_run" && exec ./supervisor ./daemon "$tee_run" </dev/null >/dev/null 2>&1) &
            sup=$!
            printf '%s\n' "$sup" > "$tee_run/.pid"
            worker_process_start "$sup" > "$tee_run/.pid-start"
            printf '%s\n' "$boot_id" > "$tee_run/.pid-boot"
        )
        sup="$(cat "$tee_run/.pid" 2>/dev/null)"
    elif [ -x "$runtime/service.sh" ]; then
        "$runtime/service.sh" </dev/null >/dev/null 2>&1 &
    fi

    local waited=0 alive=""
    while [ "$waited" -lt 6 ]; do
        alive=""
        for child in $(worker_children "$sup"); do
            [ "$(cat "/proc/$child/comm" 2>/dev/null)" != TEESimulator ] || alive="$child"
        done
        [ -n "$alive" ] && break
        sleep 1
        waited=$((waited + 1))
    done

    if [ -n "$alive" ]; then
        log "TEESimulator successfully restarted (PID: $alive)."
        return 0
    else
        log_err "Failed to restart TEESimulator."
        return 1
    fi
}

score_keybox() {
    local file="$1"
    [ -f "$file" ] || { printf '0'; return; }

    local b64
    b64=$(awk '
        /-----BEGIN CERTIFICATE-----/ { collecting=1; buf=""; next }
        /-----END CERTIFICATE-----/   { if (collecting) { print buf; exit } }
        collecting                    { gsub(/[ \r\n\t]/, "", $0); buf = buf $0 }
    ' "$file")
    [ -n "$b64" ] || { printf '0'; return; }

    local hex
    hex=$(printf '%s' "$b64" | base64 -d 2>/dev/null | xxd -p -c 256 2>/dev/null | tr -d '\r\n ')
    [ -n "$hex" ] || { printf '0'; return; }

    local base_score=0

    case "$hex" in
        *170d*)
            local prefix="${hex%%170d*}"
            local pos=${#prefix}
            local data_start=$(( pos + 4 ))
            local found_val
            found_val=$(printf '%s' "$hex" | cut -c$(( data_start + 1 ))-$(( data_start + 12 )))

            local y1 y2 m1 m2 d1 d2
            y1=$(printf '%s' "$found_val" | cut -c2)
            y2=$(printf '%s' "$found_val" | cut -c4)
            m1=$(printf '%s' "$found_val" | cut -c6)
            m2=$(printf '%s' "$found_val" | cut -c8)
            d1=$(printf '%s' "$found_val" | cut -c10)
            d2=$(printf '%s' "$found_val" | cut -c12)
            local yy="${y1}${y2}"
            local mm="${m1}${m2}"
            local dd="${d1}${d2}"
            local century="20"
            if [ "${yy:-99}" -ge 70 ] 2>/dev/null; then
                century="19"
            fi
            base_score="${century}${yy}${mm}${dd}"
            ;;
        *180f*)
            local prefix="${hex%%180f*}"
            local pos=${#prefix}
            local data_start=$(( pos + 4 ))
            local found_val
            found_val=$(printf '%s' "$hex" | cut -c$(( data_start + 1 ))-$(( data_start + 16 )))
            local y1 y2 y3 y4 m1 m2 d1 d2
            y1=$(printf '%s' "$found_val" | cut -c2)
            y2=$(printf '%s' "$found_val" | cut -c4)
            y3=$(printf '%s' "$found_val" | cut -c6)
            y4=$(printf '%s' "$found_val" | cut -c8)
            m1=$(printf '%s' "$found_val" | cut -c10)
            m2=$(printf '%s' "$found_val" | cut -c12)
            d1=$(printf '%s' "$found_val" | cut -c14)
            d2=$(printf '%s' "$found_val" | cut -c16)
            base_score="${y1}${y2}${y3}${y4}${m1}${m2}${d1}${d2}"
            ;;
        *)
            base_score=0
            ;;
    esac

    local not_after=""
    local rem="${hex#*170d}"
    case "$rem" in
        *170d*)
            local na_prefix="${rem%%170d*}"
            local na_pos=${#na_prefix}
            local na_start=$(( na_pos + 4 ))
            local na_val
            na_val=$(printf '%s' "$rem" | cut -c$(( na_start + 1 ))-$(( na_start + 12 )))
            local nay1 nay2 nam1 nam2 nad1 nad2
            nay1=$(printf '%s' "$na_val" | cut -c2)
            nay2=$(printf '%s' "$na_val" | cut -c4)
            nam1=$(printf '%s' "$na_val" | cut -c6)
            nam2=$(printf '%s' "$na_val" | cut -c8)
            nad1=$(printf '%s' "$na_val" | cut -c10)
            nad2=$(printf '%s' "$na_val" | cut -c12)
            local nayy="${nay1}${nay2}"
            local namm="${nam1}${nam2}"
            local nadd="${nad1}${nad2}"
            local nacentury="20"
            if [ "${nayy:-99}" -ge 70 ] 2>/dev/null; then
                nacentury="19"
            fi
            not_after="${nacentury}${nayy}${namm}${nadd}"
            ;;
    esac

    local today
    today=$(date +%Y%m%d 2>/dev/null || echo 20261001)
    if [ -n "$not_after" ] && [ "$not_after" -lt "$today" ] 2>/dev/null; then
        printf '0'
        return
    fi

    if grep -qiE '(MOCK|AOSP Software Attestation|CN=Mock)' "$file" 2>/dev/null; then
        printf '0'
        return
    fi
    local dev_id
    dev_id=$(sed -n 's/.*<Keybox DeviceID="\([^"]*\)".*/\1/p' "$file" 2>/dev/null | head -n 1)
    case "$dev_id" in
        *MOCK*|*mock*|*Test*|*test*)
            printf '0'
            return
            ;;
    esac

    local has_ecdsa=0 has_rsa=0
    grep -qi 'algorithm="ecdsa"' "$file" 2>/dev/null && has_ecdsa=1
    grep -qi 'algorithm="rsa"' "$file" 2>/dev/null && has_rsa=1

    if [ "$has_ecdsa" -eq 1 ] && [ "$has_rsa" -eq 1 ]; then
        base_score=$((base_score + 20000000))
    elif [ "$has_ecdsa" -eq 1 ]; then
        base_score=$((base_score + 10000000))
    fi

    printf '%s' "$base_score"
}

heal_keybox() {
    log "Starting keybox health evaluation and selection..."
    local active="$tee_state/keybox.xml"

    local active_valid=0 active_score=0
    if [ -f "$active" ]; then
        if check_keybox "$active"; then
            active_valid=1
            active_score=$(score_keybox "$active")
            log "Active keybox is valid (score=$active_score)."
        else
            log "Active keybox is invalid or revoked. Healing required."
        fi
    else
        log "Active keybox is missing. Healing required."
    fi

    rm -rf "$work"
    mkdir -p "$work" "$tee_state"
    chmod 700 "$work" "$tee_state"

    local healed=0
    local best_file="" best_score=0
    if [ "$active_valid" -eq 1 ]; then
        best_score="$active_score"
    fi

    local urls_file="$state/keybox_urls.conf"
    if [ ! -s "$urls_file" ] || ! grep -Eq '^(https?://)' "$urls_file" 2>/dev/null; then
        if [ -f "$runtime/defaults/keybox_urls.conf" ]; then
            cp -f "$runtime/defaults/keybox_urls.conf" "$state/keybox_urls.conf" 2>/dev/null || true
            chmod 600 "$state/keybox_urls.conf" 2>/dev/null || true
            urls_file="$runtime/defaults/keybox_urls.conf"
        fi
    fi

    if [ -f "$urls_file" ] && command -v curl >/dev/null 2>&1; then
        local candidate_url count=0
        while IFS= read -r candidate_url; do
            case "$candidate_url" in
                http://*|https://*) ;;
                *) continue ;;
            esac
            count=$((count + 1))
            [ "$count" -le 16 ] || break

            log "Downloading keybox candidate $count from: $candidate_url"
            local raw="$work/cand_$count.raw"
            local safe="$work/cand_$count.xml"

            fetch_candidate "$candidate_url" "$raw" || continue
            if [ -s "$raw" ] && sanitize_keybox "$raw" "$safe"; then
                if check_keybox "$safe"; then
                    local score
                    score=$(score_keybox "$safe")
                    log "Candidate $count validated (score=$score): $candidate_url"

                    if [ "${score:-0}" -gt "${best_score:-0}" ] 2>/dev/null; then
                        best_score="$score"
                        best_file="$safe"
                        log "New best candidate (score=$best_score) from: $candidate_url"
                    fi
                else
                    log_err "Candidate $count failed validation: $candidate_url"
                fi
            else
                log_err "Candidate $count download/parse failed: $candidate_url"
            fi
        done <<EOF
$(candidate_urls "$urls_file")
EOF
    fi

    if [ -n "$best_file" ] && [ -f "$best_file" ]; then
        log "Installing best keybox candidate (score=$best_score)..."
        local temp="$tee_state/.keybox.$$"
        if cp "$best_file" "$temp" && chmod 600 "$temp" && mv -f "$temp" "$active"; then
            healed=1
        else
            log_err "Failed to install best keybox candidate."
            rm -f "$temp"
            rm -rf "$work"
            return 1
        fi
    elif [ "$active_valid" -eq 1 ]; then
        log "Active keybox is already the best available (score=$active_score). Keeping it."
        healed=1
    fi

    if [ "$healed" -eq 0 ]; then
        local def="$runtime/defaults/keybox.xml"
        if [ -f "$def" ]; then
            log "No valid remote candidate found. Falling back to built-in default: $def"
            local safe_def="$work/default_safe.xml"
            if sanitize_keybox "$def" "$safe_def" && check_keybox "$safe_def"; then
                local temp="$tee_state/.keybox.$$"
                if cp "$safe_def" "$temp" && chmod 600 "$temp" && mv -f "$temp" "$active"; then
                    healed=1
                else
                    rm -f "$temp"
                    log_err "Failed to install built-in default keybox."
                fi
            else
                log_err "Built-in default keybox also failed validation!"
            fi
        fi
    fi

    rm -rf "$work"

    if [ -n "$best_file" ]; then
        chmod 600 "$active"
        reload_tee
        flush_gms_cache
        log "Keybox upgraded to newer/better candidate (score=$best_score)!"
        return 0
    elif [ "$active_valid" -eq 1 ]; then
        log "Keybox check passed: active keybox is optimal (score=$active_score)."
        return 0
    elif [ "$healed" -eq 1 ]; then
        chmod 600 "$active"
        reload_tee
        flush_gms_cache
        log "Keybox healing completed successfully (score=$best_score)!"
        return 0
    else
        log_err "Keybox healing failed: no valid keybox could be retrieved or installed."
        return 1
    fi
}

is_softbanned() {
    local hash="$1"
    [ -n "$hash" ] || return 1
    [ -f "$state/.softbanned_keys.txt" ] || return 1
    grep -qiF "$hash" "$state/.softbanned_keys.txt" 2>/dev/null
}

record_softban() {
    local hash="$1" reason="$2"
    [ -n "$hash" ] || return 1
    mkdir -p "$state"
    printf '%s # %s %s\n' "$hash" "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo '2026-10-03')" "$reason" >> "$state/.softbanned_keys.txt"
    chmod 600 "$state/.softbanned_keys.txt" 2>/dev/null || true
}

is_known_softbanned_serial() {
    case "$1" in
        *64deaa4d53885472afac267bdbd4a472*|\
        *9307822e6c6f88cd2f1ab20c37afeb1*|\
        *15510740886364958753*|\
        *6e918661b760821ec0d47876b3f1c241*|\
        *8ccba1b176a78ee67b5a39bfc798f3fd*|\
        *8be515f01eecb298004f45a791ef1e43*|\
        *8caffcd6e1e3cd4bb06e47874ba83ea3*|\
        *8d094e54b2d14cd8a839315bc47279e9*|\
        *dde22371480783b50a5a90edb00553e2*|\
        *ddd4b618906baf4debbc1972569c7e30*|\
        *8fc660e3ab0a2733d99ef591462522de*|\
        *8f514acd6f630ca5e0cc149864134d60*|\
        *7a7547fda5ef7ecd*|\
        *319a00569ae5722b0dc7a4527a7b7ce0*|\
        *7601d99a5a3593d5c530d5d7dbad28bf*|\
        *f0d42233b774002d43c5dd1bfcf443ad*|\
        *3439b76c89282dadba8d72bf6e1091b9*|\
        *53b74cd32bac807bcebe5a7b0106ccc6*|\
        *1820676521795154355*|\
        *e3f85159915212aa*)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

run_play_integrity_test() {
    verification_allowed || { printf 'DEFERRED'; return 0; }
    local dump="/data/local/tmp/pi_dump.$$.xml"
    rm -f "$dump"

    am force-stop gr.nikolasspyr.integritycheck 2>/dev/null || true
    am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n gr.nikolasspyr.integritycheck/.MainActivity >/dev/null 2>&1 || \
        monkey -p gr.nikolasspyr.integritycheck -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

    sleep 3
    verification_allowed || { printf 'DEFERRED'; return 0; }

    input tap 540 1513 >/dev/null 2>&1

    local waited=0 found=0
    while [ "$waited" -lt 14 ]; do
        sleep 2
        waited=$((waited + 2))
        verification_allowed || { rm -f "$dump"; printf 'DEFERRED'; return 0; }

        input tap 888 158 >/dev/null 2>&1
        sleep 1
        uiautomator dump "$dump" >/dev/null 2>&1
        if [ -s "$dump" ] && grep -q 'gr.nikolasspyr.integritycheck:id/message' "$dump" 2>/dev/null; then
            found=1
            break
        fi
    done

    local verdict="ERROR"
    if [ "$found" -eq 1 ] && [ -s "$dump" ]; then

        if grep -Fq '"MEETS_STRONG_INTEGRITY"' "$dump"; then
            verdict="STRONG"
        elif grep -Fq '"MEETS_DEVICE_INTEGRITY"' "$dump"; then
            verdict="DEVICE"
        elif grep -Fq '"MEETS_BASIC_INTEGRITY"' "$dump"; then
            verdict="BASIC"
        fi
    elif [ "$found" -eq 0 ]; then

        local win_info
        win_info=$(dumpsys window windows 2>/dev/null | grep -c 'gr.nikolasspyr.integritycheck' 2>/dev/null || echo 0)
        if [ "${win_info:-0}" -eq 0 ] 2>/dev/null; then

            verdict="LAUNCH_FAIL"
        fi

    fi

    rm -f "$dump"
    am force-stop gr.nikolasspyr.integritycheck >/dev/null 2>&1 || true
    printf '%s' "$verdict"
}

test_candidate_for_strong() {
    local cand_xml="$1" source_label="$2"
    [ -f "$cand_xml" ] || return 1
    verification_allowed || return 4

    local hash
    hash=$(sha256sum "$cand_xml" 2>/dev/null | awk '{print $1}')
    [ -n "$hash" ] || return 1
    if verification_cache_hit "$hash"; then
        [ "$cached_verdict" != DEVICE ] || return 2
        return 3
    fi

    if is_softbanned "$hash"; then
        log "[Hunter] Candidate from $source_label (${hash:0:12}) previously tested and softbanned. Skipping."
        return 2
    fi

    local serials s is_known_bad=0 bad_s=""
    serials=$(extract_serials "$cand_xml")
    for s in $serials; do
        if is_known_softbanned_serial "$s"; then
            is_known_bad=1
            bad_s="$s"
            break
        fi
    done
    if [ "$is_known_bad" -eq 1 ]; then
        log "[Hunter] Candidate from $source_label has known softbanned serial ($bad_s). Skipping test."
        record_softban "$hash" "KNOWN_SOFTBANNED_SERIAL_$bad_s from $source_label"
        return 2
    fi

    log "=========================================================="
    log "[Hunter] Testing candidate from: $source_label (hash: ${hash:0:12})"

    local temp="$tee_state/.keybox.$$" active_hash
    active_hash="$(sha256sum "$tee_state/keybox.xml" 2>/dev/null | awk '{print $1}')"
    if [ "$hash" = "$active_hash" ]; then
        :
    elif cp -f "$cand_xml" "$temp" && chmod 600 "$temp" && mv -f "$temp" "$tee_state/keybox.xml"; then
        reload_tee
        flush_gms_cache
        sleep 2
    else
        log_err "[Hunter] Failed to deploy candidate: $source_label"
        return 1
    fi

    log "[Hunter] Running on-device Play Integrity verification..."
    local verdict
    verdict=$(run_play_integrity_test)
    remember_verification "$hash" "$verdict"
    log "[Hunter] Play Integrity Verdict: $verdict"

    if [ "$verdict" = "STRONG" ]; then
        log "🎉🎉🎉 SUCCESS: MEETS_STRONG_INTEGRITY PASSED! 🎉🎉🎉"
        cp -f "$cand_xml" "$state/golden_keybox.xml" 2>/dev/null && chmod 600 "$state/golden_keybox.xml"
        cp -f "$cand_xml" "/sdcard/GOLDEN_KEYBOX.xml" 2>/dev/null || true
        touch "$state/.strong_locked"
        chmod 600 "$state/.strong_locked"
        cmd notification post -S bigtext -t "Reisenless Keybox Hunter" "reisen_hunter" "🎉 MEETS_STRONG_INTEGRITY achieved and locked!" >/dev/null 2>&1 || true
        return 0
    elif [ "$verdict" = "DEVICE" ]; then

        log "[Hunter] Candidate achieves MEETS_DEVICE_INTEGRITY only (not STRONG). Recording but NOT banning."
        printf '%s # %s DEVICE_ONLY from %s\n' "$hash" "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo '2026-10-03')" "$source_label" \
            >> "$state/.device_only_keys.txt" 2>/dev/null || true
        chmod 600 "$state/.device_only_keys.txt" 2>/dev/null || true
        log "[Hunter] Cooling down 15s to prevent API rate limits..."
        sleep 15
        return 2
    elif [ "$verdict" = "DEFERRED" ]; then
        return 4
    elif [ "$verdict" = "LAUNCH_FAIL" ] || [ "$verdict" = "ERROR" ]; then

        log "[Hunter] Play Integrity app failed to launch (system busy or binder error). NOT banning. Will retry next cycle."
        sleep 10
        return 3
    else

        log "[Hunter] Candidate failed verification ($verdict). Softbanning."
        record_softban "$hash" "FAILED_${verdict} from $source_label"
        sleep 30
        return 2
    fi
}

process_queue_directory() {
    local qdir="$1"
    [ -d "$qdir" ] || return 1
    local pdir="$qdir/processed"
    mkdir -p "$pdir" 2>/dev/null || true

    local file
    for file in "$qdir"/*; do
        [ -f "$file" ] || continue
        case "$file" in */processed/*) continue ;; esac

        log "[Hunter:Queue] Found candidate file: $file"
        local safe="$work/q_cand.$$.xml"
        if sanitize_keybox "$file" "$safe" && check_keybox "$safe"; then
            test_candidate_for_strong "$safe" "queue/$(basename "$file")"
            local ret=$?
            case "$ret" in
                3|4) rm -f "$safe"; return "$ret" ;;
            esac
            mv -f "$file" "$pdir/" 2>/dev/null || log_err "Failed to archive queued file: $file"
            rm -f "$safe"
            if [ "$ret" -eq 0 ]; then
                return 0
            fi
        else
            log_err "[Hunter:Queue] File failed validation: $file"
            mv -f "$file" "$pdir/" 2>/dev/null || log_err "Failed to archive queued file: $file"
            rm -f "$safe"
        fi
    done
    return 1
}

harvest_remote_candidates() {
    local urls_file="$state/keybox_urls.conf"
    [ -s "$urls_file" ] || urls_file="$runtime/defaults/keybox_urls.conf"
    [ -f "$urls_file" ] || return 1
    command -v curl >/dev/null 2>&1 || return 1

    mkdir -p "$work"
    local url count=0
    while IFS= read -r url; do
        case "$url" in
            http://*|https://*) ;;
            *) continue ;;
        esac
        count=$((count + 1))
        log "[Hunter:Remote] Fetching candidate $count from: $url"
        local raw="$work/rem_$count.raw"
        local safe="$work/rem_$count.xml"

        fetch_candidate "$url" "$raw" || continue
        if [ -s "$raw" ] && sanitize_keybox "$raw" "$safe"; then
            if check_keybox "$safe"; then
                test_candidate_for_strong "$safe" "$url"
                local ret=$?
                rm -f "$raw" "$safe"
                if [ "$ret" -eq 0 ]; then
                    return 0
                fi
                [ "$ret" -ne 4 ] || return 4
            else
                log_err "[Hunter:Remote] Candidate $count failed CRL / schema: $url"
            fi
        fi
        rm -f "$raw" "$safe"
    done <<EOF
$(candidate_urls "$urls_file")
EOF
    return 1
}

hunt_keybox() {
    crl_attempted=0
    verification_context=
    if [ -f "$state/.strong_locked" ] && [ -f "$tee_state/keybox.xml" ]; then
        if check_keybox "$tee_state/keybox.xml" >/dev/null 2>&1; then
            log "[Hunter] MEETS_STRONG_INTEGRITY is already locked with valid keybox. No hunt needed."
            return 0
        else
            log "[Hunter] Active keybox is no longer valid despite lock. Re-opening hunt."
            rm -f "$state/.strong_locked"
        fi
    fi

    mkdir -p "$work"

    local result
    process_queue_directory "/sdcard/keybox_queue"
    result=$?
    case "$result" in 0|4) return "$result" ;; esac
    process_queue_directory "$state/queue"
    result=$?
    case "$result" in 0|4) return "$result" ;; esac

    harvest_remote_candidates && return 0

    rm -rf "$work"
    log "[Hunter] Hunt cycle concluded. No Strong keybox found this round."
    return 1
}

hunt_daemon() {
    background_allowed || return 0
    worker_acquire hunter || return 0
    trap 'rm -rf "$work"; worker_release keybox; worker_release hunter' EXIT
    trap 'exit 0' INT TERM
    log "[Hunter] Starting background hunting daemon..."
    local interval=1800
    sleep 20
    while true; do
        background_allowed || break
        if [ -f "$state/.strong_locked" ]; then
            log "[Hunter] MEETS_STRONG_INTEGRITY achieved and locked. Halting daemon."
            break
        fi
        local result=1
        if worker_acquire keybox; then
            hunt_keybox
            result=$?
            worker_release keybox
        fi
        if [ "$result" -eq 0 ]; then
            log "[Hunter] Hunting succeeded and locked! Exiting daemon."
            break
        fi
        log "[Hunter] Sleeping ${interval}s until next hunt cycle..."
        sleep "$interval"
    done
    worker_release hunter
}

status_keybox() {
    local active="$tee_state/keybox.xml"
    printf '=== Keybox Status ===\n'
    printf 'Root directory   : %s\n' "$root"
    printf 'Active keybox    : %s\n' "$active"
    if [ ! -f "$active" ]; then
        printf 'Status           : MISSING\n'
        return 1
    fi

    local dev_id algo
    dev_id=$(sed -n 's/.*<Keybox DeviceID="\([^"]*\)".*/\1/p' "$active" | head -n 1)
    algo=$(sed -n 's/.*<Key algorithm="\([^"]*\)".*/\1/p' "$active" | head -n 1)
    printf 'DeviceID         : %s\n' "${dev_id:-Unknown}"
    printf 'Algorithm        : %s\n' "${algo:-Unknown}"

    printf 'Certificate Serials:\n'
    local serials
    serials=$(extract_serials "$active")
    for s in $serials; do
        if is_serial_revoked "$s"; then
            printf '  • %s [REVOKED]\n' "$s"
        else
            printf '  • %s [CLEAN]\n' "$s"
        fi
    done

    check_keybox "$active" >/dev/null 2>&1
    local ret=$?
    if [ "$ret" -eq 0 ]; then
        printf 'Overall Verdict  : [✔ CLEAR] VALID & UNREVOKED\n'
    elif [ "$ret" -eq 1 ]; then
        printf 'Overall Verdict  : [! DANGER] REVOKED BY CRL\n'
    else
        printf 'Overall Verdict  : [? ERROR] INVALID SCHEMA OR CORRUPTED\n'
    fi

    printf '\n=== Hunter Status ===\n'
    if [ -f "$state/.strong_locked" ]; then
        printf 'Strong Locked    : [✔ YES] (golden_keybox.xml active)\n'
    else
        printf 'Strong Locked    : [NO] (active hunting mode)\n'
    fi
    local softbanned_count=0
    [ ! -f "$state/.softbanned_keys.txt" ] || softbanned_count=$(wc -l < "$state/.softbanned_keys.txt" 2>/dev/null || echo 0)
    printf 'Softbanned Keys  : %s\n' "$softbanned_count"
    printf 'Golden Keybox    : %s\n' "$([ -f "$state/golden_keybox.xml" ] && echo 'Present' || echo 'Not generated yet')"
    return "$ret"
}

case "$1" in
    check)
        shift
        check_keybox "${1:-$tee_state/keybox.xml}"
        ;;
    heal)
        worker_acquire keybox || exit 1
        trap 'rm -rf "$work"; worker_release keybox' EXIT
        trap 'exit 1' INT TERM
        heal_keybox
        ;;
    hunt)
        worker_acquire keybox || exit 1
        trap 'rm -rf "$work"; worker_release keybox' EXIT
        trap 'exit 1' INT TERM
        hunt_keybox
        ;;
    hunt_daemon)
        hunt_daemon
        ;;
    stop_daemon)
        worker_stop hunter
        ;;
    test_strong)
        shift
        worker_acquire keybox || exit 1
        trap 'rm -rf "$work"; worker_release keybox' EXIT
        trap 'exit 1' INT TERM
        test_candidate_for_strong "${1:-$tee_state/keybox.xml}" "manual"
        ;;
    test_check)
        run_play_integrity_test
        printf '\n'
        ;;
    status|"")
        status_keybox
        ;;
    *)
        printf 'Usage: %s [check [file.xml] | heal | hunt | hunt_daemon | test_strong [file.xml] | test_check | status]\n' "$0"
        exit 1
        ;;
esac
