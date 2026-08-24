









umask 022

OUTFD=$2
COMMONDIR=$INSTALLER/assets
CHROMEDIR=$INSTALLER/assets/chromeos

if [ ! -f $COMMONDIR/util_functions.sh ]; then
  echo "! unable to extract zip file!"
  exit 1
fi


. $COMMONDIR/util_functions.sh

setup_flashable





if echo $MAGISK_VER | grep -q '\.'; then
  PRETTY_VER=$MAGISK_VER
else
  PRETTY_VER="$MAGISK_VER($MAGISK_VER_CODE)"
fi
print_title "reisenless $PRETTY_VER installer"

is_mounted /data || mount /data || is_mounted /cache || mount /cache
mount_partitions
check_data
get_flags
find_boot_image

[ -z $BOOTIMAGE ] && abort "! unable to detect target image"
ui_print "- target image: $BOOTIMAGE"


api_level_arch_detect

[ $API -lt 23 ] && abort "! magisk only support android 6.0 and above"

ui_print "- device platform: $ABI"

BINDIR=$INSTALLER/lib/$ABI
cd $BINDIR
for file in lib*.so; do mv "$file" "${file:3:${#file}-6}"; done
cd /
cp -af $INSTALLER/lib/$ABI32/libmagisk.so $BINDIR/$BIN32_NAME 2>/dev/null
[ -f "$BINDIR/magisk" ] && mv "$BINDIR/magisk" "$BINDIR/$MAIN_BIN_NAME"
[ -f "$BINDIR/busybox" ] && mv "$BINDIR/busybox" "$BINDIR/$BUSYBOX_NAME"
[ -f "$BINDIR/mpol" ] && mv "$BINDIR/mpol" "$BINDIR/$POLICY_NAME"
[ -f "$BINDIR/init-ld" ] && mv "$BINDIR/init-ld" "$BINDIR/$INIT_LD_NAME"


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





install_magisk


$BOOTMODE || recovery_cleanup
rm -rf $TMPDIR

ui_print "- done"
exit 0
