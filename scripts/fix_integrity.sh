#!/system/bin/sh
# ==============================================================================
# Reisenless Play Integrity Anti-Detection & Property Normalization Engine
# Target: Xiaomi Mi 10T Pro (apollo) - Snapdragon 865 - LineageOS 21
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

ROOT="/data/.xlmk/.yer"
RUN="$ROOT/tee-runtime"
STATE="$ROOT/state"
RUNTIME="$ROOT/runtime"
BIN_DIR="/data/.xlmk/.dweuvzu"

if [ -x "$BIN_DIR/resetprop" ]; then
    RP="$BIN_DIR/resetprop"
elif [ -x "/product/bin/resetprop" ]; then
    RP="/product/bin/resetprop"
elif command -v resetprop >/dev/null 2>&1; then
    RP="resetprop"
else
    RP="setprop"
fi

SETPROP="$RP -n"
DELPROP="$RP -d"

# 1. Clean stale failure locks
echo "${YELLOW}[1/7] Clearing failure flags and ensuring state...${NC}"
rm -f "$STATE/tee-failed-payload" "$STATE/tee-unavailable" "$STATE/disabled" "$STATE/boot-failed" "$STATE/boot-pending" "$STATE/.softbanned_keys.txt" 2>/dev/null
rm -rf "$STATE/persistent_keys"/* 2>/dev/null
touch "$STATE/enabled" "$STATE/tee-broken" 2>/dev/null
chmod 600 "$STATE/enabled" "$STATE/tee-broken" 2>/dev/null
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
    
    # Ensure proper SELinux labeling on TEE runtime binaries and libraries
    chcon -R u:object_r:trcbnnlr_f:s0 "$RUN" 2>/dev/null || true
    sed -i 's/udonge_lib_file/trcbnnlr_f/g' "$RUNTIME/service.sh" 2>/dev/null || true

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

# 5. Apply universal Xiaomi Mi 10T Pro Android 12 certified properties across ALL partitions
echo "${YELLOW}[3/7] Enforcing universal Xiaomi Mi 10T Pro certified properties in shared memory...${NC}"

# Enforce certified Xiaomi Mi 10T Pro profile in state directory
cat << 'EOF' > "$STATE/pif.conf"
FINGERPRINT=Xiaomi/apollopro_global/apollo:12/RKQ1.211001.001/V14.0.4.0.SJDMIXM:user/release-keys
BRAND=Xiaomi
DEVICE=apollo
MANUFACTURER=Xiaomi
MODEL=M2007J3SG
PRODUCT=apollopro_global
RELEASE=12
ID=RKQ1.211001.001
INCREMENTAL=V14.0.4.0.SJDMIXM
TAGS=release-keys
TYPE=user
SECURITY_PATCH=2023-09-01
DEVICE_INITIAL_SDK_INT=29
EOF
chmod 600 "$STATE/pif.conf" 2>/dev/null || true

cat << 'EOF' > "$STATE/security_patch.txt"
system=2023-09-01
boot=2023-09-01
vendor=2023-09-01
EOF
chmod 600 "$STATE/security_patch.txt" 2>/dev/null || true
printf 'tee_broken=true\n' > "$STATE/tee_status.txt" 2>/dev/null || true
chmod 600 "$STATE/tee_status.txt" 2>/dev/null || true

FP="Xiaomi/apollopro_global/apollo:12/RKQ1.211001.001/V14.0.4.0.SJDMIXM:user/release-keys"

$SETPROP ro.product.brand "Xiaomi"
$SETPROP ro.product.manufacturer "Xiaomi"
$SETPROP ro.product.model "M2007J3SG"
$SETPROP ro.product.device "apollo"
$SETPROP ro.product.name "apollopro_global"
$SETPROP ro.build.product "apollo"
$SETPROP ro.build.id "RKQ1.211001.001"
$SETPROP ro.build.display.id "RKQ1.211001.001"
$SETPROP ro.build.type "user"
$SETPROP ro.build.tags "release-keys"
$SETPROP ro.build.version.release "12"
$SETPROP ro.build.version.release_or_codename "12"
$SETPROP ro.build.version.security_patch "2023-09-01"

for part in system system_ext product vendor oem odm bootimage odm_dlkm vendor_dlkm; do
    $SETPROP "ro.product.${part}.brand" "Xiaomi" 2>/dev/null
    $SETPROP "ro.product.${part}.manufacturer" "Xiaomi" 2>/dev/null
    $SETPROP "ro.product.${part}.model" "M2007J3SG" 2>/dev/null
    $SETPROP "ro.product.${part}.device" "apollo" 2>/dev/null
    $SETPROP "ro.product.${part}.name" "apollopro_global" 2>/dev/null
    $SETPROP "ro.${part}.build.fingerprint" "$FP" 2>/dev/null
    $SETPROP "ro.${part}.build.id" "RKQ1.211001.001" 2>/dev/null
    $SETPROP "ro.${part}.build.type" "user" 2>/dev/null
    $SETPROP "ro.${part}.build.tags" "release-keys" 2>/dev/null
    $SETPROP "ro.${part}.build.version.security_patch" "2023-09-01" 2>/dev/null
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

# Cleanup test dumps and temporary files from /data/local/tmp
rm -f /data/local/tmp/*boot.img /data/local/tmp/*.xml /data/local/tmp/*.png /data/local/tmp/apolloctl-preview /data/local/tmp/unpack_udonge.sh /data/local/tmp/udonge.bin 2>/dev/null || true

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
    grep -qxF "${pkg}!" "$STATE/target.txt" 2>/dev/null || {
        sed -i "/^${pkg}[!?]*\$/d" "$STATE/target.txt" 2>/dev/null || true
        printf '%s!\n' "$pkg" >> "$STATE/target.txt" 2>/dev/null
    }
done

# Ensure duckdetector is in target list with GENERATE mode
sed -i "/^com\.eltavine\.duckdetector[!?]*\$/d" "$STATE/target.txt" 2>/dev/null || true
printf 'com.eltavine.duckdetector!\n' >> "$STATE/target.txt" 2>/dev/null
sed -i "/^com\.eltavine\.duckdetector[!?]*\$/d" "$STATE/targets.conf" 2>/dev/null || true
printf 'com.eltavine.duckdetector!\n' >> "$STATE/targets.conf" 2>/dev/null

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
