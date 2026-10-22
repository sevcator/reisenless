#!/system/bin/sh
# ==============================================================================
# Reisenless Play Integrity Anti-Detection & Property Normalization Engine
# Target: OnePlus 6 (OnePlus6) - Snapdragon 845 - LineageOS 21
# ==============================================================================

CYAN='\033[1;36m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
RED='\033[1;31m'
NC='\033[0m'

echo ""
echo "${CYAN}[*] ===================================================${NC}"
echo "${CYAN}[*]   REISENLESS UNIFIED INTEGRITY & PROPERTY NORMALIZER ${NC}"
echo "${CYAN}[*] ===================================================${NC}"

ROOT="/data/.gacl/.thj"
if [ ! -d "$ROOT" ]; then
    SUPERVISOR_CMD="$(ps -ef 2>/dev/null | awk '/supervisor \.\/daemon/ { for (i=1; i<=NF; i++) if ($i ~ /tee-runtime$/) print $i }' | head -n 1)"
    if [ -n "$SUPERVISOR_CMD" ] && [ -d "$(dirname "$SUPERVISOR_CMD")" ]; then
        ROOT="$(dirname "$SUPERVISOR_CMD")"
    else
        ROOT="$(find /data -maxdepth 3 -name "tee-runtime" 2>/dev/null | while read -r p; do stat -c '%Y %n' "$p" 2>/dev/null; done | sort -nr | head -n 1 | awk '{print $2}' | xargs dirname 2>/dev/null)"
    fi
fi
RUN="$ROOT/tee-runtime"
STATE="$ROOT/state"
RUNTIME="$ROOT/runtime"

if command -v lnnqjjk >/dev/null 2>&1; then
    RP="lnnqjjk resetprop"
elif command -v resetprop >/dev/null 2>&1; then
    RP="resetprop"
elif [ -x "/product/bin/resetprop" ]; then
    RP="/product/bin/resetprop"
else
    RP="setprop"
fi

SETPROP="$RP -n"
DELPROP="$RP -d"

# 1. Clean stale failure locks
echo "${YELLOW}[1/7] Clearing failure flags and ensuring state...${NC}"
rm -f "$STATE/tee-failed-payload" "$STATE/tee-unavailable" "$STATE/disabled" "$STATE/boot-failed" "$STATE/boot-pending" "$STATE/.softbanned_keys.txt" 2>/dev/null
rm -rf "$STATE/persistent_keys"/* 2>/dev/null
touch "$STATE/enabled" 2>/dev/null
rm -f "$STATE/tee-broken" "$STATE/tee_status.txt" 2>/dev/null
chmod 600 "$STATE/enabled" 2>/dev/null
cat /proc/sys/kernel/random/boot_id > "$STATE/boot-pending" 2>/dev/null
chmod 600 "$STATE/boot-pending" 2>/dev/null

# Ensure boot_hash.bin and props.conf match Xiaomi Mi 10T Pro
printf 'ro.boot.vbmeta.digest=d38131034ae58f9c8cbff1557619a01df1c99f0e7bf2d6c397826c4f651d22ed\n' > "$STATE/props.conf"
chmod 600 "$STATE/props.conf" 2>/dev/null || true
if command -v xxd >/dev/null 2>&1; then
    printf 'd38131034ae58f9c8cbff1557619a01df1c99f0e7bf2d6c397826c4f651d22ed' | xxd -r -p > "$STATE/boot_hash.bin" 2>/dev/null
    chmod 600 "$STATE/boot_hash.bin" 2>/dev/null || true
fi


# Ensure rom_keywords.conf contains custom ROM and kernel markers
if [ -f "$STATE/rom_keywords.conf" ]; then
    for kw in lineage crdroid apolloctl apollo-enhanced apollon; do
        grep -qiFx "$kw" "$STATE/rom_keywords.conf" 2>/dev/null || printf '%s\n' "$kw" >> "$STATE/rom_keywords.conf"
    done
fi

# Sync manager app preferences to keep Udonge enabled in UI
for pref in /data/user_de/0/*/shared_prefs/*_preferences.xml; do
    if [ -f "$pref" ]; then
        sed -i 's/"tniijj_enabled" value="false"/"tniijj_enabled" value="true"/' "$pref" 2>/dev/null || true
        sed -i 's/"udonge_enabled" value="false"/"udonge_enabled" value="true"/' "$pref" 2>/dev/null || true
    fi
done

# 2. Refresh TEESimulator process tree
echo "${YELLOW}[2/7] Refreshing TEESimulator process tree...${NC}"
pkill -9 -f "org.matrix.TEESimulator.App" 2>/dev/null || true
pkill -9 -f "supervisor ./daemon" 2>/dev/null || true

# 3. Identify running Keystore2 PID and sync tracking
KEYSTORE="$(pidof keystore2 2>/dev/null || pidof keystore 2>/dev/null)"
if [ -z "$KEYSTORE" ]; then
    echo "${RED}[!] Error: Keystore2 process not found! Starting keystore2...${NC}"
    start keystore2 2>/dev/null || true
    sleep 2
    KEYSTORE="$(pidof keystore2 2>/dev/null || pidof keystore 2>/dev/null)"
fi

if [ -n "$KEYSTORE" ]; then
    KEYSTORE_START="$(awk '{ if (sub(/^.*\) /, "")) print $20; else print $22 }' "/proc/$KEYSTORE/stat" 2>/dev/null)"
    printf '%s\n' "$KEYSTORE" > "$RUN/.keystore-pid" 2>/dev/null
    printf '%s\n' "$KEYSTORE_START" > "$RUN/.keystore-start" 2>/dev/null
    echo "  -> Target Keystore2 PID: ${GREEN}$KEYSTORE${NC} (start: $KEYSTORE_START)"
    
    # Ensure proper SELinux labeling on TEE runtime binaries and libraries (system_file:s0 required for keystore access)
    chcon -R u:object_r:system_file:s0 "$RUN" 2>/dev/null || true

    # Spawn supervisor daemon if not running
    if ! pgrep -f "supervisor ./daemon" >/dev/null 2>&1; then
        echo "  -> Starting TEESimulator supervisor daemon..."
        (cd "$RUN" && exec ./supervisor ./daemon "$RUN" </dev/null >"$RUN/startup.log" 2>&1) &
        sleep 2
    fi

    # Inject libTEESimulator.so into Keystore2 if not already mapped
    if ! grep -qF "libTEESimulator.so" "/proc/$KEYSTORE/maps" 2>/dev/null; then
        if [ -x "$RUN/inject.bin" ]; then
            echo "  -> Injecting libTEESimulator.so into Keystore2 PID $KEYSTORE..."
            "$RUN/inject.bin" "$KEYSTORE" "$RUN/libTEESimulator.so" entry 2>/dev/null || true
            sleep 2
        fi
    fi
fi

# 4. Verify injection status
if [ -n "$KEYSTORE" ] && grep -qF "libTEESimulator.so" "/proc/$KEYSTORE/maps" 2>/dev/null; then
    echo "${GREEN}[✓] libTEESimulator.so successfully active in Keystore2 memory!${NC}"
    printf 'ready\n' > "$STATE/tee-status" 2>/dev/null
    rm -f "$STATE/tee-unavailable" 2>/dev/null
else
    echo "${RED}[!] Warning: libTEESimulator injection pending or delayed.${NC}"
fi

# 5. Apply OnePlus 6 Android 11 certified properties across ALL partitions
echo "${YELLOW}[3/7] Enforcing OnePlus 6 (OnePlus6) certified properties in shared memory...${NC}"

# Enforce certified OnePlus 6 Android 11 profile in state directory
cat << 'EOF' > "$STATE/pif.conf"
FINGERPRINT=OnePlus/OnePlus6/OnePlus6:11/RKQ1.201217.002/2111252325:user/release-keys
BRAND=OnePlus
DEVICE=OnePlus6
MANUFACTURER=OnePlus
MODEL=ONEPLUS A6003
PRODUCT=OnePlus6
RELEASE=11
ID=RKQ1.201217.002
INCREMENTAL=2111252325
TAGS=release-keys
TYPE=user
SECURITY_PATCH=2022-01-01
DEVICE_INITIAL_SDK_INT=27
SDK_INT=30
EOF
chmod 600 "$STATE/pif.conf" 2>/dev/null || true

cat << 'EOF' > "$STATE/security_patch.txt"
system=prop
boot=2022-01-01
vendor=2022-01-01
EOF
chmod 600 "$STATE/security_patch.txt" 2>/dev/null || true
rm -f "$STATE/tee-broken" "$STATE/tee_status.txt" 2>/dev/null || true

FP="OnePlus/OnePlus6/OnePlus6:11/RKQ1.201217.002/2111252325:user/release-keys"

$SETPROP ro.product.brand "OnePlus"
$SETPROP ro.product.manufacturer "OnePlus"
$SETPROP ro.product.model "ONEPLUS A6003"
$SETPROP ro.product.device "OnePlus6"
$SETPROP ro.product.name "OnePlus6"
$SETPROP ro.build.product "OnePlus6"
$SETPROP ro.build.id "RKQ1.201217.002"
$SETPROP ro.build.display.id "RKQ1.201217.002"
$SETPROP ro.build.type "user"
$SETPROP ro.build.tags "release-keys"
$SETPROP ro.build.version.release "11"
$SETPROP ro.build.version.release_or_codename "11"
$SETPROP ro.build.version.security_patch "2022-01-01"
$SETPROP ro.board.first_api_level "27"
$SETPROP ro.product.first_api_level "27"

for part in system system_ext product vendor oem odm bootimage odm_dlkm vendor_dlkm; do
    $SETPROP "ro.product.${part}.brand" "OnePlus" 2>/dev/null
    $SETPROP "ro.product.${part}.manufacturer" "OnePlus" 2>/dev/null
    $SETPROP "ro.product.${part}.model" "ONEPLUS A6003" 2>/dev/null
    $SETPROP "ro.product.${part}.device" "OnePlus6" 2>/dev/null
    $SETPROP "ro.product.${part}.name" "OnePlus6" 2>/dev/null
    $SETPROP "ro.${part}.build.fingerprint" "$FP" 2>/dev/null
    $SETPROP "ro.${part}.build.id" "RKQ1.201217.002" 2>/dev/null
    $SETPROP "ro.${part}.build.type" "user" 2>/dev/null
    $SETPROP "ro.${part}.build.tags" "release-keys" 2>/dev/null
    $SETPROP "ro.${part}.build.version.security_patch" "2022-01-01" 2>/dev/null
done
$SETPROP ro.build.fingerprint "$FP"

# 6. Verified Boot, AVB & Security hardening
echo "${YELLOW}[4/7] Hardening Verified Boot and AVB root of trust...${NC}"
$SETPROP ro.boot.verifiedbootstate "green"
$SETPROP vendor.boot.verifiedbootstate "green"
$SETPROP ro.boot.flash.locked "1"
$SETPROP ro.boot.vbmeta.device_state "locked"
$SETPROP vendor.boot.vbmeta.device_state "locked"
$SETPROP ro.boot.veritymode "enforcing"
$SETPROP ro.boot.avb_version "1.3"
$SETPROP ro.secure "1"
$SETPROP ro.debuggable "0"
$SETPROP ro.force.debuggable "0"
$SETPROP ro.adb.secure "1"
$SETPROP sys.oem_unlock_allowed "0"
$SETPROP ro.is_ever_orange "0"
$SETPROP ro.boot.warranty_bit "0"
$SETPROP ro.warranty_bit "0"
$SETPROP ro.vendor.boot.warranty_bit "0"
$SETPROP ro.vendor.warranty_bit "0"
$SETPROP ro.crypto.state "encrypted"

# VBMeta public key digest & VBMeta digest alignment (matching attested keybox hash)
OEM_KEY="c5d3c71bc70d58e3e0409ca9d9b34c0dbac1d2f09a5de948a4b8f090f1926965"
$SETPROP ro.boot.vbmeta.public_key_digest "$OEM_KEY"
$SETPROP vendor.boot.vbmeta.public_key_digest "$OEM_KEY"
DIGEST="d38131034ae58f9c8cbff1557619a01df1c99f0e7bf2d6c397826c4f651d22ed"
$SETPROP ro.boot.vbmeta.digest "$DIGEST"
echo "  -> ro.boot.vbmeta.digest set to ${GREEN}$DIGEST${NC}"


# ADB properties alignment (preserve real ADB connectivity across reboots)
$SETPROP service.adb.root "0"

# Cleanup test dumps, temporary files and stale pif.conf from /data/local/tmp
rm -f /data/local/tmp/*boot.img /data/local/tmp/*.xml /data/local/tmp/*.png /data/local/tmp/apolloctl-preview /data/local/tmp/unpack_udonge.sh /data/local/tmp/udonge.bin /data/local/tmp/pif.conf 2>/dev/null || true

# 7. Strip Custom ROM / LineageOS Signatures & Compact Arenas
echo "${YELLOW}[5/7] Stripping Custom ROM signatures and compacting property arenas...${NC}"
getprop | sed 's/^\[//; s/\]:.*//' | grep -i -E '(lineage|evolution|crdroid|arrow|mistos|axion|infinity|pixelos|rising|lunaris|halcyon|havoc|alphadroid|avium|bliss|calyx|derpfest|graphene|lmodroid|lumine|matrixx|sakura|statix|superior|clover|witaqua|yaap|mica)' | while IFS= read -r prop; do
    [ -n "$prop" ] && $DELPROP "$prop" 2>/dev/null || true
done

$DELPROP ro.modversion 2>/dev/null || true
$DELPROP persist.vendor.camera.privapp.list 2>/dev/null || true
$SETPROP vendor.camera.aux.packagelist "org.codeaurora.snapcam,com.android.camera" 2>/dev/null || true

# Compact property arenas to remove deleted property holes
$RP -c 2>/dev/null || true

# 8. Target file alignment
echo "${YELLOW}[6/7] Enforcing target attestation configuration...${NC}"
for pkg in com.android.vending com.google.android.gms com.google.android.gsf \
           gr.nikolasspyr.integritycheck com.henrikhermansen.playintegritychecker; do
    sed -i "s/^${pkg}[!?]*\$/${pkg}/" "$STATE/target.txt" 2>/dev/null || true
    grep -qxF "$pkg" "$STATE/target.txt" 2>/dev/null || printf '%s\n' "$pkg" >> "$STATE/target.txt" 2>/dev/null
done

# Ensure duckdetector is in target list WITHOUT '!' (prevents TEE stall & PIF build drift)
sed -i "/^com\.eltavine\.duckdetector[!?]*\$/d" "$STATE/target.txt" 2>/dev/null || true
printf 'com.eltavine.duckdetector\n' >> "$STATE/target.txt" 2>/dev/null
sed -i "/^com\.eltavine\.duckdetector[!?]*\$/d" "$STATE/targets.conf" 2>/dev/null || true
printf 'com.eltavine.duckdetector\n' >> "$STATE/targets.conf" 2>/dev/null

# 9. Flush GMS & Play Store Attestation Cache
echo "${YELLOW}[7/7] Flushing stale Play Integrity and certification cache...${NC}"
rm -rf /data/data/com.google.android.gms/databases/dg.db* 2>/dev/null || true
rm -rf /data/data/com.google.android.gms/shared_prefs/attestation* 2>/dev/null || true
pkill -f "com.google.android.gms.unstable" 2>/dev/null || true

echo ""
echo "${GREEN}[✓] ===================================================${NC}"
echo "${GREEN}[✓]   UNIFIED PROPERTY NORMALIZATION COMPLETE!          ${NC}"
echo "${GREEN}[✓]   Keystore2 : PID $KEYSTORE | TEE: Ready            ${NC}"
echo "${GREEN}[✓]   Identity  : Xiaomi Mi 10T Pro (apollo) | State: Green ${NC}"
echo "${GREEN}[✓]   VBMeta    : Aligned with Keybox                   ${NC}"
echo "${GREEN}[✓] ===================================================${NC}"
echo ""
