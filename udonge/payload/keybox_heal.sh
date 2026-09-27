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

# Sanitize XML keybox content
sanitize_keybox() {
    local src="$1" dst="$2"
    [ -f "$src" ] || return 1
    # Remove XML declaration and comments/directives, normalize CRLF to LF
    sed '/^[[:space:]]*<[?]xml[^>]*[?]>[[:space:]]*$/d' "$src" | \
        tr -d '\r' > "$dst" || return 1
    if grep -Fq '<!' "$dst" || grep -Fq '<?' "$dst"; then
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

# Reload TEESimulator process cleanly
reload_tee() {
    log "Reloading TEESimulator..."
    local supervisor child
    for child in $(pidof TEESimulator 2>/dev/null); do
        kill -TERM "$child" 2>/dev/null || true
    done
    
    # Allow supervisor to restart TEESimulator with new keybox
    sleep 1
    local alive
    alive=$(pidof TEESimulator 2>/dev/null)
    if [ -z "$alive" ]; then
        # If supervisor didn't respawn, run service.sh in background to restart
        if [ -x "$runtime/service.sh" ]; then
            "$runtime/service.sh" </dev/null >/dev/null 2>&1 &
        fi
        sleep 2
    fi
    
    alive=$(pidof TEESimulator 2>/dev/null)
    if [ -n "$alive" ]; then
        log "TEESimulator successfully restarted (PID: $alive)."
        return 0
    else
        log_err "Failed to restart TEESimulator."
        return 1
    fi
}

# Main healing logic
heal_keybox() {
    log "Starting keybox health evaluation..."
    local active="$tee_state/keybox.xml"
    
    # 1. Check active keybox
    if [ -f "$active" ]; then
        check_keybox "$active"
        local ret=$?
        if [ "$ret" -eq 0 ]; then
            log "Active keybox is already valid and unrevoked. No healing needed."
            return 0
        fi
        log "Active keybox is invalid or revoked (code $ret). Healing required."
    else
        log "Active keybox is missing. Healing required."
    fi
    
    rm -rf "$work"
    mkdir -p "$work" "$tee_state"
    chmod 700 "$work" "$tee_state"
    
    local healed=0
    local urls_file="$state/keybox_urls.conf"
    [ -f "$urls_file" ] || urls_file="$runtime/defaults/keybox_urls.conf"
    
    # 2. Try remote candidate URLs
    if [ -f "$urls_file" ] && command -v curl >/dev/null 2>&1; then
        local candidate_url count=0
        while IFS= read -r candidate_url; do
            case "$candidate_url" in
                https://*) ;;
                *) continue ;;
            esac
            count=$((count + 1))
            [ "$count" -le 16 ] || break
            
            log "Downloading keybox candidate from: $candidate_url"
            local raw="$work/cand_$count.raw"
            local safe="$work/cand_$count.xml"
            
            curl -sSL --connect-timeout 8 -m 12 -o "$raw" "$candidate_url" 2>/dev/null
            if [ -s "$raw" ] && sanitize_keybox "$raw" "$safe"; then
                if check_keybox "$safe"; then
                    log "Successfully verified candidate from: $candidate_url"
                    local temp="$tee_state/.keybox.$$"
                    cp "$safe" "$temp" && chmod 600 "$temp" && mv -f "$temp" "$active"
                    healed=1
                    break
                else
                    log_err "Candidate from $candidate_url failed validation."
                fi
            fi
        done < "$urls_file"
    fi
    
    # 3. Fallback to clean built-in default
    if [ "$healed" -eq 0 ]; then
        local def="$runtime/defaults/keybox.xml"
        if [ -f "$def" ]; then
            log "Falling back to built-in default: $def"
            local safe_def="$work/default_safe.xml"
            if sanitize_keybox "$def" "$safe_def" && check_keybox "$safe_def"; then
                local temp="$tee_state/.keybox.$$"
                cp "$safe_def" "$temp" && chmod 600 "$temp" && mv -f "$temp" "$active"
                healed=1
            else
                log_err "Built-in default keybox failed validation!"
            fi
        fi
    fi
    
    rm -rf "$work"
    
    if [ "$healed" -eq 1 ]; then
        chmod 600 "$active"
        reload_tee
        log "Keybox healing completed successfully!"
        return 0
    else
        log_err "Keybox healing failed: no valid keybox could be retrieved or installed."
        return 1
    fi
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
    status|"")
        status_keybox
        ;;
    *)
        printf 'Usage: %s [check [file.xml] | heal | status]\n' "$0"
        exit 1
        ;;
esac
