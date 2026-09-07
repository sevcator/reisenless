









umask 022

OUTFD=$2
COMMONDIR=$INSTALLER/assets
CHROMEDIR=$INSTALLER/assets/chromeos

if [ ! -f $COMMONDIR/util_functions.sh ]; then
  echo "! unable to extract zip file!"
  exit 1
fi


. $COMMONDIR/util_functions.sh
. $COMMONDIR/app_functions.sh

setup_flashable





if echo $MAGISK_VER | grep -q '\.'; then
  PRETTY_VER=$MAGISK_VER
else
  PRETTY_VER="$MAGISK_VER($MAGISK_VER_CODE)"
fi
print_title "system component $PRETTY_VER installer"

is_mounted /data || mount /data || is_mounted /cache || mount /cache
mount_partitions
[ -z "$PATCH_ONLY_OUTPUT" ] && check_data
get_flags
find_boot_image

[ -z $BOOTIMAGE ] && abort "! unable to detect target image"
ui_print "- target image: $BOOTIMAGE"


api_level_arch_detect

[ $API -lt 23 ] && abort "! android 6.0 or newer is required"

ui_print "- device platform: $ABI"

BINDIR=$INSTALLER/lib/$ABI
cd $BINDIR
for file in lib*.so; do mv "$file" "${file:3:${#file}-6}"; done
cd /
cp -af "$INSTALLER/lib/$ABI32/lib$PACKAGED_MAIN_LIB.so" "$BINDIR/$BIN32_NAME" 2>/dev/null
[ -f "$BINDIR/$PACKAGED_MAIN_LIB" ] && mv "$BINDIR/$PACKAGED_MAIN_LIB" "$BINDIR/$MAIN_BIN_NAME"
[ -f "$BINDIR/$PACKAGED_BUSYBOX_LIB" ] && mv "$BINDIR/$PACKAGED_BUSYBOX_LIB" "$BINDIR/$BUSYBOX_NAME"
[ -f "$BINDIR/$PACKAGED_POLICY_LIB" ] && mv "$BINDIR/$PACKAGED_POLICY_LIB" "$BINDIR/$POLICY_NAME"
[ -f "$BINDIR/$PACKAGED_INIT_LD_LIB" ] && mv "$BINDIR/$PACKAGED_INIT_LD_LIB" "$BINDIR/$INIT_LD_NAME"
[ -f "$BINDIR/$PACKAGED_BOOT_LIB" ] && mv "$BINDIR/$PACKAGED_BOOT_LIB" "$BINDIR/mboot"
[ -f "$BINDIR/$PACKAGED_INIT_LIB" ] && mv "$BINDIR/$PACKAGED_INIT_LIB" "$BINDIR/minit"
[ -f "$BINDIR/$PACKAGED_BOOTCTL_LIB" ] && mv "$BINDIR/$PACKAGED_BOOTCTL_LIB" "$BINDIR/bootctl"


# Candidate generation must not replace the live payload, migrate durable
# state, write addon.d, or install runtime hooks. Keep all work in INSTALLER.
if [ -n "$PATCH_ONLY_OUTPUT" ]; then
  $BOOTMODE || abort "! candidate generation requires a booted device"
  MAGISKBIN="$INSTALLER/staged-payload"
  mkdir -p "$MAGISKBIN" || abort "! unable to create candidate workspace"
  cp -af "$BINDIR/." "$COMMONDIR/." "$BBBIN" "$MAGISKBIN" || abort "! unable to stage payload"
  chmod -R 755 "$MAGISKBIN"
  install_magisk
  cd /
  rm -rf "$TMPDIR"
  ui_print "- candidate ready; live root state unchanged"
  exit 0
fi

$BOOTMODE || remove_system_su





ui_print "- constructing environment"


rm -rf $MAGISKBIN 2>/dev/null
mkdir -p $MAGISKBIN 2>/dev/null
cp -af $BINDIR/. $COMMONDIR/. $BBBIN $MAGISKBIN


rm -f $MAGISKBIN/bootctl $MAGISKBIN/main.jar \
  $MAGISKBIN/module_installer.sh $MAGISKBIN/uninstaller.sh

chmod -R 755 $MAGISKBIN


if [ -d /system/addon.d ]; then
  ui_print "- adding addon.d survival script"
  blockdev --setrw /dev/block/mapper/system$SLOT 2>/dev/null
  mount -o rw,remount /system || mount -o rw,remount /
  ADDOND=/system/addon.d/99-ms.sh
  cp -af $COMMONDIR/addon.d.sh $ADDOND
  chmod 755 $ADDOND
fi




migrate_private_layout || abort "! unable to migrate existing root state"
migrate_legacy_layout || abort "! unable to migrate legacy root state"
install_magisk
refresh_udonge_runtime || abort "! unable to install protection runtime"


$BOOTMODE || recovery_cleanup
rm -rf $TMPDIR

ui_print "- done"
exit 0
