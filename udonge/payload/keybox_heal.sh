#!/system/bin/sh
#
# Reisenless Udonge Keybox Checker & Healing Engine
# Validates keybox XML schema, extracts certificate serials,
# verifies against Google Attestation CRL / offline blacklist,
# and heals compromised keyboxes automatically.
#

umask 077

find_root() {
    # 1. If called from within runtime or root
    local parent
    parent="$(cd "$(dirname "$0")/.." && pwd 2>/dev/null)"
    if [ -d "$parent/state" ] && [ -f "$parent/state/keybox.xml" ]; then
        printf '%s' "$parent"
        return 0
    fi
    # 2. Check running TEESimulator / supervisor cwd
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
    # 3. Check nested directories (both visible and hidden)
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
work="$root/keybox-heal-work"
crl_cache="$state/.crl_status.json"

log() {
    printf '[KeyboxHeal] %s\n' "$*"
}

log_err() {
    printf '[KeyboxHeal:ERROR] %s\n' "$*" >&2
}

# Offline blacklist of known compromised / revoked keybox certificate serials
# (lowercase hex without leading zeros, and decimal)
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
    # Update cache if older than 24 hours (86400s) or missing
    local now last_mod
    now=$(date +%s 2>/dev/null || echo 0)
    if [ -f "$crl_cache" ] && [ -s "$crl_cache" ]; then
        last_mod=$(stat -c %Y "$crl_cache" 2>/dev/null || echo 0)
        if [ "$now" -gt 0 ] && [ "$last_mod" -gt 0 ] && [ $((now - last_mod)) -lt 86400 ]; then
            return 0
        fi
    fi
    
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

# Extract certificate serial numbers from an XML keybox file
# Output: list of serial numbers in hex and decimal
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
        
        # Parse DER hex string:
        # byte 0 (chars 1-2): 30 (SEQUENCE)
        idx=3
        b=$(printf '%s' "$hex" | cut -c $idx-$((idx+1)))
        b_val=$((0x$b))
        if [ $((b_val & 0x80)) -ne 0 ]; then
            num_bytes=$((b_val & 0x7f))
            idx=$((idx + 2 + num_bytes * 2))
        else
            idx=$((idx + 2))
        fi
        
        # TBSCertificate SEQUENCE (tag 30)
        idx=$((idx + 2))
        b=$(printf '%s' "$hex" | cut -c $idx-$((idx+1)))
        b_val=$((0x$b))
        if [ $((b_val & 0x80)) -ne 0 ]; then
            num_bytes=$((b_val & 0x7f))
            idx=$((idx + 2 + num_bytes * 2))
        else
            idx=$((idx + 2))
        fi
        
        # Optional version [0] EXPLICIT tag a0
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
        
        # SerialNumber tag (must be 02)
        if [ "$tag" = "02" ]; then
            idx=$((idx + 2))
            slen_hex=$(printf '%s' "$hex" | cut -c $idx-$((idx+1)))
            slen=$((0x$slen_hex))
            idx=$((idx + 2))
            serial=$(printf '%s' "$hex" | cut -c $idx-$((idx + slen * 2 - 1)))
            clean_serial=$(printf '%s' "$serial" | sed 's/^00*//')
            [ -n "$clean_serial" ] || clean_serial="0"
            printf '%s\n' "$clean_serial"
            # Also emit decimal form so is_serial_revoked() matches decimal CRL entries.
            # Google CRL has ~979 decimal-format entries vs ~780 hex-format.
            local dec_val
            dec_val=$(printf '%d' "0x${clean_serial}" 2>/dev/null) && \
                [ -n "$dec_val" ] && printf '%s\n' "$dec_val"
        fi
    done
}

# Checks if a serial is revoked in CRL or blacklist
is_serial_revoked() {
    local s="$1"
    [ -n "$s" ] || return 1
    
    if is_offline_blacklisted "$s"; then
        return 0
    fi
    
    if [ -f "$crl_cache" ] && [ -s "$crl_cache" ]; then
        if grep -qiE "\"${s}\"[[:space:]]*:[[:space:]]*\{[^}]*\"status\"[[:space:]]*:[[:space:]]*\"REVOKED\"" "$crl_cache"; then
            return 0
        fi
    fi
    return 1
}

# Multi-layer de-obfuscator for nested base64, Specter cipher, MeowDump, and raw XML
unwrap_payload() {
    local src="$1" dst="$2"
    [ -f "$src" ] || return 1
    
    mkdir -p "$work"
    local cur="$work/unw_cur.$$" nxt="$work/unw_nxt.$$"
    cp -f "$src" "$cur"
    
    local iter=0
    while [ "$iter" -lt 15 ]; do
        iter=$((iter + 1))
        
        # 1. Direct XML match
        if grep -q '<AndroidAttestation' "$cur" 2>/dev/null || grep -q '<Keybox' "$cur" 2>/dev/null; then
            cp -f "$cur" "$dst"
            rm -f "$cur" "$nxt"
            return 0
        fi
        
        # 2. Check for ROT13 XML
        if tr 'A-Za-z' 'N-ZA-Mn-za-m' < "$cur" 2>/dev/null | grep -q '<AndroidAttestation' 2>/dev/null; then
            tr 'A-Za-z' 'N-ZA-Mn-za-m' < "$cur" > "$dst" 2>/dev/null
            rm -f "$cur" "$nxt"
            return 0
        fi
        
        # 3. Try Hex decode
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
        
        # 4. Try standard Base64 decode
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
        
        # 5. Try Specter substitution cipher
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

# Sanitize XML keybox content (supports raw XML, nested Base64, Specter, MeowDump)
sanitize_keybox() {
    local src="$1" dst="$2"
    [ -f "$src" ] || return 1
    mkdir -p "$work"
    local raw_unwrapped="$work/san_raw.$$"
    
    if ! unwrap_payload "$src" "$raw_unwrapped"; then
        cp -f "$src" "$raw_unwrapped"
    fi
    
    # Remove XML declaration and comments/directives, normalize variant tags, normalize CRLF to LF
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

# Validate keybox file:
# Returns:
#   0: VALID (clean schema, valid keys, unrevoked certs)
#   1: REVOKED (one or more certificate serials in CRL)
#   2: CORRUPTED / INVALID SCHEMA
check_keybox() {
    local file="$1"
    if [ ! -f "$file" ] || [ ! -s "$file" ]; then
        log_err "Keybox file does not exist or is empty: $file"
        return 2
    fi
    
    # 1. Structural checks
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

    # 1b. Reject AOSP/Mock test cert chains — they never pass Strong Integrity.
    # Google's Play Integrity validation requires the root CA to be a real hardware
    # attestation CA, not an AOSP test or synthetic mock root.
    if grep -qE '(Mock Google RKP|Mock RKP KeyMint|AOSP Software Attestation|CN=Mock)' "$file" 2>/dev/null; then
        log_err "Keybox $file uses AOSP/Mock test cert chain — cannot pass Strong Integrity."
        return 1
    fi

    # 1c. Fast expiration check on leaf cert (most-common failure after 2026-09-28 mass expiry).
    # Avoids wasting a Play Integrity API call on obviously expired certs.
    local today_ymd
    today_ymd=$(date +%Y%m%d 2>/dev/null || echo 20261001)
    local leaf_score
    leaf_score=$(score_keybox "$file")
    # score_keybox returns 0 when expired (see score_keybox implementation)
    if [ "${leaf_score:-1}" -eq 0 ] 2>/dev/null; then
        log_err "Keybox $file leaf certificate is expired — rejecting."
        return 1
    fi

    # 2. Check for revoked certificates
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

# Flush GMS and Play Integrity attestation caches
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

# Reload TEESimulator process cleanly
reload_tee() {
    log "Reloading TEESimulator..."
    local child sup
    for child in $(pidof TEESimulator 2>/dev/null); do
        kill -9 "$child" 2>/dev/null || true
    done
    for sup in $(pidof supervisor 2>/dev/null); do
        kill -9 "$sup" 2>/dev/null || true
    done
    
    # Wait for processes to exit
    sleep 1
    
    # Relaunch supervisor/daemon directly from tee-runtime if available
    local tee_run="$root/tee-runtime"
    if [ -x "$tee_run/supervisor" ] && [ -x "$tee_run/daemon" ]; then
        (cd "$tee_run" && exec ./supervisor ./daemon "$tee_run" </dev/null >/dev/null 2>&1) &
    elif [ -x "$runtime/service.sh" ]; then
        rm -rf "$root/.service-lock" 2>/dev/null || true
        "$runtime/service.sh" </dev/null >/dev/null 2>&1 &
    fi
    
    # Poll up to 6s for TEESimulator process to spawn
    local waited=0 alive=""
    while [ "$waited" -lt 6 ]; do
        alive=$(pidof TEESimulator 2>/dev/null)
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

# Score a keybox by the NotBefore date of its leaf certificate.
# Returns an integer YYYYMMDD (higher = newer cert = preferred).
# Real hardware keys are given priority over synthetic MOCK_RKP keys.
# Falls back to 0 if parsing fails so any valid box still wins over nothing.
score_keybox() {
    local file="$1"
    [ -f "$file" ] || { printf '0'; return; }

    # Extract the FIRST certificate's base64 from the XML
    local b64
    b64=$(awk '
        /-----BEGIN CERTIFICATE-----/ { collecting=1; buf=""; next }
        /-----END CERTIFICATE-----/   { if (collecting) { print buf; exit } }
        collecting                    { gsub(/[ \r\n\t]/, "", $0); buf = buf $0 }
    ' "$file")
    [ -n "$b64" ] || { printf '0'; return; }

    # Decode to raw bytes then hex
    local hex
    hex=$(printf '%s' "$b64" | base64 -d 2>/dev/null | xxd -p -c 256 2>/dev/null | tr -d '\r\n ')
    [ -n "$hex" ] || { printf '0'; return; }

    local base_score=0
    # Search for UTCTIME (tag 17, len 0d -> "170d") or GENERALIZEDTIME (tag 18, len 0f -> "180f")
    case "$hex" in
        *170d*)
            local prefix="${hex%%170d*}"
            local pos=${#prefix}
            local data_start=$(( pos + 4 ))
            local found_val
            found_val=$(printf '%s' "$hex" | cut -c$(( data_start + 1 ))-$(( data_start + 12 )))
            # In ASCII hex: '0'=30, '1'=31 ... '9'=39.
            # Digits are at even positions: 2, 4, 6, 8, 10, 12
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

    # Extract notAfter from subsequent 170d tag to detect expired certificates
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

    # Discard expired certificates (score = 0)
    local today
    today=$(date +%Y%m%d 2>/dev/null || echo 20261001)
    if [ -n "$not_after" ] && [ "$not_after" -lt "$today" ] 2>/dev/null; then
        printf '0'
        return
    fi

    # Real hardware keyboxes (e.g. from real manufacturers) pass Strong Integrity.
    # Synthetic MOCK_RKP / test keyboxes fail Strong Integrity on Google servers.
    # Penalize mock/test keys so genuine hardware keys are strongly prioritized.
    local dev_id
    dev_id=$(sed -n 's/.*<Keybox DeviceID="\([^"]*\)".*/\1/p' "$file" 2>/dev/null | head -n 1)
    case "$dev_id" in
        *MOCK*|*mock*|*Test*|*test*)
            base_score=$((base_score - 100000000))
            ;;
    esac

    # Prioritize keyboxes with ECDSA (or dual ECDSA + RSA), needed by Android 15 DroidGuard
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

# Main healing logic
heal_keybox() {
    log "Starting keybox health evaluation and selection..."
    local active="$tee_state/keybox.xml"
    
    # 1. Check active keybox
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
    if [ ! -s "$urls_file" ] || ! grep -q '^https://' "$urls_file" 2>/dev/null; then
        if [ -f "$runtime/defaults/keybox_urls.conf" ]; then
            cp -f "$runtime/defaults/keybox_urls.conf" "$state/keybox_urls.conf" 2>/dev/null || true
            chmod 600 "$state/keybox_urls.conf" 2>/dev/null || true
            urls_file="$runtime/defaults/keybox_urls.conf"
        fi
    fi

    # 2. Download all candidates, validate each, select the one with the freshest cert
    if [ -f "$urls_file" ] && command -v curl >/dev/null 2>&1; then
        local candidate_url count=0
        while IFS= read -r candidate_url; do
            case "$candidate_url" in
                https://*) ;;
                *) continue ;;
            esac
            count=$((count + 1))
            [ "$count" -le 16 ] || break

            log "Downloading keybox candidate $count from: $candidate_url"
            local raw="$work/cand_$count.raw"
            local safe="$work/cand_$count.xml"

            curl -sSL --connect-timeout 8 -m 12 -o "$raw" "$candidate_url" 2>/dev/null
            if [ -s "$raw" ] && sanitize_keybox "$raw" "$safe"; then
                if check_keybox "$safe"; then
                    local score
                    score=$(score_keybox "$safe")
                    log "Candidate $count validated (score=$score): $candidate_url"
                    # Pick this candidate if it has a higher score (fresher cert)
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
        done < "$urls_file"
    fi

    # 3. Install the best candidate if a newer/better one was found
    if [ -n "$best_file" ] && [ -f "$best_file" ]; then
        log "Installing best keybox candidate (score=$best_score)..."
        local temp="$tee_state/.keybox.$$"
        if cp "$best_file" "$temp" && chmod 600 "$temp" && mv -f "$temp" "$active"; then
            healed=1
        else
            log_err "Failed to install best keybox candidate."
        fi
    elif [ "$active_valid" -eq 1 ]; then
        log "Active keybox is already the best available (score=$active_score). Keeping it."
        healed=1
    fi

    # 4. Fallback to clean built-in default if active was invalid and no remote candidate was valid
    if [ "$healed" -eq 0 ]; then
        local def="$runtime/defaults/keybox.xml"
        if [ -f "$def" ]; then
            log "No valid remote candidate found. Falling back to built-in default: $def"
            local safe_def="$work/default_safe.xml"
            if sanitize_keybox "$def" "$safe_def" && check_keybox "$safe_def"; then
                local temp="$tee_state/.keybox.$$"
                cp "$safe_def" "$temp" && chmod 600 "$temp" && mv -f "$temp" "$active"
                healed=1
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


# --- Keybox Hunter & Strong Integrity Verification Engine ---

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
    local dump="/data/local/tmp/pi_dump.$$.xml"
    rm -f "$dump"
    
    # Ensure device is awake and keyguard dismissed
    input keyevent KEYCODE_WAKEUP 2>/dev/null || true
    wm dismiss-keyguard 2>/dev/null || true
    
    # Stop existing instance and start Play Integrity check app cleanly
    am force-stop gr.nikolasspyr.integritycheck 2>/dev/null || true
    am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n gr.nikolasspyr.integritycheck/.MainActivity >/dev/null 2>&1 || \
        monkey -p gr.nikolasspyr.integritycheck -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    
    sleep 3
    # Tap "CHECK" button at center coordinates (540, 1513)
    input tap 540 1513 >/dev/null 2>&1
    
    # Wait for Play Integrity network request to complete and poll JSON modal
    local waited=0 found=0
    while [ "$waited" -lt 14 ]; do
        sleep 2
        waited=$((waited + 2))
        # Tap JSON code view icon in toolbar
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
        # Search specifically inside the JSON text (which has literal double quotes)
        if grep -Fq '"MEETS_STRONG_INTEGRITY"' "$dump"; then
            verdict="STRONG"
        elif grep -Fq '"MEETS_DEVICE_INTEGRITY"' "$dump"; then
            verdict="DEVICE"
        elif grep -Fq '"MEETS_BASIC_INTEGRITY"' "$dump"; then
            verdict="BASIC"
        fi
    elif [ "$found" -eq 0 ]; then
        # The Play Integrity app never showed a result. Distinguish two cases:
        # LAUNCH_FAIL = app never appeared in foreground (Android binder crash / system busy)
        # ERROR       = app launched but network/attestation call failed
        local win_info
        win_info=$(dumpsys window windows 2>/dev/null | grep -c 'gr.nikolasspyr.integritycheck' 2>/dev/null || echo 0)
        if [ "${win_info:-0}" -eq 0 ] 2>/dev/null; then
            # App window never appeared — this is a system/binder failure, not a keybox issue
            verdict="LAUNCH_FAIL"
        fi
        # else: app was visible but no result appeared → genuine ERROR (network/attestation failure)
    fi

    rm -f "$dump"
    am force-stop gr.nikolasspyr.integritycheck >/dev/null 2>&1 || true
    printf '%s' "$verdict"
}

test_candidate_for_strong() {
    local cand_xml="$1" source_label="$2"
    [ -f "$cand_xml" ] || return 1
    
    local hash
    hash=$(sha256sum "$cand_xml" 2>/dev/null | awk '{print $1}')
    [ -n "$hash" ] || return 1
    
    if is_softbanned "$hash"; then
        log "[Hunter] Candidate from $source_label (${hash:0:12}) previously tested and softbanned. Skipping."
        return 2
    fi

    # Pre-check serials against known community softban list
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
    
    # Deploy to active keybox
    local temp="$tee_state/.keybox.$$"
    if cp -f "$cand_xml" "$temp" && chmod 600 "$temp" && mv -f "$temp" "$tee_state/keybox.xml"; then
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
        # DEVICE means the keybox is valid (not revoked/expired) but does NOT achieve STRONG.
        # This is usually because the cert chain root isn't a Google hardware attestation CA.
        # We do NOT permanently ban it — it stays usable for DEVICE-level integrity.
        log "[Hunter] Candidate achieves MEETS_DEVICE_INTEGRITY only (not STRONG). Recording but NOT banning."
        printf '%s # %s DEVICE_ONLY from %s\n' "$hash" "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo '2026-10-03')" "$source_label" \
            >> "$state/.device_only_keys.txt" 2>/dev/null || true
        chmod 600 "$state/.device_only_keys.txt" 2>/dev/null || true
        log "[Hunter] Cooling down 15s to prevent API rate limits..."
        sleep 15
        return 2
    elif [ "$verdict" = "LAUNCH_FAIL" ]; then
        # App launch failed due to Android binder crash / system busy — not a keybox problem.
        # Do NOT softban. Just log and exit so the daemon retries next cycle.
        log "[Hunter] Play Integrity app failed to launch (system busy or binder error). NOT banning. Will retry next cycle."
        sleep 10
        return 3
    else
        # Genuine ERROR or BASIC: cert was rejected by Google (expired, revoked, invalid chain).
        log "[Hunter] Candidate failed verification ($verdict). Softbanning."
        record_softban "$hash" "FAILED_${verdict} from $source_label"
        sleep 30
        return 3
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
            mv -f "$file" "$pdir/" 2>/dev/null || rm -f "$file"
            rm -f "$safe"
            if [ "$ret" -eq 0 ]; then
                return 0
            fi
        else
            log_err "[Hunter:Queue] File failed validation: $file"
            mv -f "$file" "$pdir/" 2>/dev/null || rm -f "$file"
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
        
        curl -sSL --connect-timeout 8 -m 15 -o "$raw" "$url" 2>/dev/null
        if [ -s "$raw" ] && sanitize_keybox "$raw" "$safe"; then
            if check_keybox "$safe"; then
                test_candidate_for_strong "$safe" "$url"
                local ret=$?
                rm -f "$raw" "$safe"
                if [ "$ret" -eq 0 ]; then
                    return 0
                fi
            else
                log_err "[Hunter:Remote] Candidate $count failed CRL / schema: $url"
            fi
        fi
        rm -f "$raw" "$safe"
    done < "$urls_file"
    return 1
}

hunt_keybox() {
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
    # 1. Process local queues
    process_queue_directory "/sdcard/keybox_queue" && return 0
    process_queue_directory "$state/queue" && return 0
    
    # 2. Process remote sources
    harvest_remote_candidates && return 0
    
    rm -rf "$work"
    log "[Hunter] Hunt cycle concluded. No Strong keybox found this round."
    return 1
}

hunt_daemon() {
    log "[Hunter] Starting background hunting daemon..."
    local interval=1800 # 30 minutes
    while true; do
        if [ -f "$state/.strong_locked" ]; then
            log "[Hunter] MEETS_STRONG_INTEGRITY achieved and locked. Halting daemon."
            break
        fi
        hunt_keybox
        if [ $? -eq 0 ]; then
            log "[Hunter] Hunting succeeded and locked! Exiting daemon."
            break
        fi
        log "[Hunter] Sleeping ${interval}s until next hunt cycle..."
        sleep "$interval"
    done
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
        heal_keybox
        ;;
    hunt)
        hunt_keybox
        ;;
    hunt_daemon)
        hunt_daemon
        ;;
    test_strong)
        shift
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
