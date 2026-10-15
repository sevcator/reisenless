#!/system/bin/sh
# ==============================================================================
# Reisenless Play Integrity Watchdog Daemon
# Runs continuously in background to auto-heal TEE injection on framework crashes
# Location: /data/.xlmk/service.d/integrity_watchdog.sh
# ==============================================================================

LOG_FILE="/data/.xlmk/integrity_watchdog.log"
FIX_SCRIPT="/data/.xlmk/fix_integrity.sh"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE" 2>/dev/null
}

# Wait for boot completion before active monitoring
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 3
done

sleep 5
log "Integrity Watchdog daemon started. Monitoring Keystore2 and TEE state..."

LAST_KEYSTORE_PID=""

while true; do
    sleep 15

    # Check Keystore2 PID
    CUR_KEYSTORE="$(pidof keystore2 2>/dev/null || pidof keystore 2>/dev/null)"
    
    if [ -z "$CUR_KEYSTORE" ]; then
        # Keystore2 is restarting, wait a bit
        sleep 3
        continue
    fi

    NEED_FIX=0

    # 1. Did keystore2 restart?
    if [ -n "$LAST_KEYSTORE_PID" ] && [ "$CUR_KEYSTORE" != "$LAST_KEYSTORE_PID" ]; then
        log "[ALERT] Keystore2 PID changed ($LAST_KEYSTORE_PID -> $CUR_KEYSTORE)! Framework crash or soft reboot detected."
        NEED_FIX=1
    fi

    # 2. Is libTEESimulator.so injected?
    if ! grep -qF "libTEESimulator.so" "/proc/$CUR_KEYSTORE/maps" 2>/dev/null; then
        log "[ALERT] libTEESimulator.so missing in Keystore2 ($CUR_KEYSTORE) memory maps!"
        NEED_FIX=1
    fi

    # 3. Was failure or safe-mode flag touched?
    if [ -f "/data/.xlmk/.yer/state/tee-failed-payload" ] || \
       [ -f "/data/.xlmk/.yer/state/tee-unavailable" ] || \
       [ -f "/data/.xlmk/.yer/state/disabled" ] || \
       [ -f "/data/.xlmk/.yer/state/boot-failed" ] || \
       { [ "$(getprop sys.boot_completed)" = "1" ] && [ -f "/data/.xlmk/.yer/state/boot-pending" ]; }; then
        log "[ALERT] Udonge safe-mode or failure lockfile detected!"
        NEED_FIX=1
    fi

    if [ "$NEED_FIX" = 1 ]; then
        log "[ACTION] Running integrity fix sequence..."
        if [ -x "$FIX_SCRIPT" ]; then
            sh "$FIX_SCRIPT" >> "$LOG_FILE" 2>&1
        fi
        
        # Verify result
        if grep -qF "libTEESimulator.so" "/proc/$CUR_KEYSTORE/maps" 2>/dev/null; then
            log "[✓] Recovery successful! libTEESimulator.so resident in Keystore2 ($CUR_KEYSTORE)."
        else
            log "[!] Warning: Recovery attempt did not immediately bind. Will recheck next cycle."
        fi
        
        LAST_KEYSTORE_PID="$CUR_KEYSTORE"
    else
        LAST_KEYSTORE_PID="$CUR_KEYSTORE"
    fi

    # Trim log if over 500 lines
    if [ -f "$LOG_FILE" ]; then
        LINE_COUNT=$(wc -l < "$LOG_FILE" 2>/dev/null || echo 0)
        if [ "$LINE_COUNT" -gt 500 ]; then
            tail -n 250 "$LOG_FILE" > "$LOG_FILE.tmp" 2>/dev/null && mv -f "$LOG_FILE.tmp" "$LOG_FILE" 2>/dev/null
        fi
    fi
done
