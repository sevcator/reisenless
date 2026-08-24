









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
print_title "reisenless $PRETTY_VER uninstaller"

is_mounted /data || mount /data || abort "! unable to mount /data, please uninstall with the magisk app"
mount_partitions
check_data
$DATA_DE || abort "! cannot access /data, please uninstall with the magisk app"
get_flags
find_boot_image

[ -z $BOOTIMAGE ] && abort "! unable to detect target image"
ui_print "- target image: $BOOTIMAGE"


api_level_arch_detect

ui_print "- device platform: $ABI"

BINDIR=$INSTALLER/lib/$ABI
cd $BINDIR
for file in lib*.so; do mv "$file" "${file:3:${#file}-6}"; done
cd /
[ -f $BINDIR/magisk ] && mv $BINDIR/magisk $BINDIR/$MAIN_BIN_NAME
cp -af $CHROMEDIR/. $BINDIR/chromeos
chmod -R 755 $BINDIR





cd $BINDIR

CHROMEOS=false

ui_print "- unpacking boot image"

if [ -c $BOOTIMAGE ]; then
  nanddump -f boot.img $BOOTIMAGE
  BOOTNAND=$BOOTIMAGE
  BOOTIMAGE=boot.img
fi
./mboot unpack "$BOOTIMAGE"

case $? in
  1 )
    abort "! unsupported/unknown image format"
    ;;
  2 )
    ui_print "- chromeos boot image detected"
    CHROMEOS=true
    ;;
esac


[ "$BOOTNAND" ] && BOOTIMAGE=$BOOTNAND


ui_print "- checking ramdisk status"
if [ -e ramdisk.cpio ]; then
  ./mboot cpio ramdisk.cpio test
  STATUS=$?
else

  STATUS=0
fi
case $((STATUS & 3)) in
  0 )
    ui_print "- stock boot image detected"
    ;;
  1 )
    ui_print "- reisenless patched image detected"

      ./mboot cpio ramdisk.cpio "extract .backup/$BACKUP_CONFIG config.orig" 2>/dev/null || \
      ./mboot cpio ramdisk.cpio "extract .backup/.magisk config.orig" 2>/dev/null
    if [ -f config.orig ]; then
      chmod 0644 config.orig
      SHA1=$(grep_prop SHA1 config.orig)
      rm config.orig
    fi
    BACKUPDIR=${BACKUP_PREFIX}${SHA1}
    if [ -d $BACKUPDIR ]; then
      ui_print "- restoring stock boot image"
      flash_image $BACKUPDIR/boot.img.gz $BOOTIMAGE
      for name in dtb dtbo dtbs; do
        [ -f $BACKUPDIR/${name}.img.gz ] || continue
        IMAGE=$(find_block $name$SLOT)
        [ -z $IMAGE ] && continue
        ui_print "- restoring stock $name image"
        flash_image $BACKUPDIR/${name}.img.gz $IMAGE
      done
    else
      ui_print "! boot image backup unavailable"
      ui_print "- restoring ramdisk with internal backup"
      ./mboot cpio ramdisk.cpio restore
      if ! ./mboot cpio ramdisk.cpio "exists init"; then

        rm -f ramdisk.cpio
      fi
      ./mboot repack $BOOTIMAGE

      $CHROMEOS && sign_chromeos
      ui_print "- flashing restored boot image"
      flash_image new-boot.img $BOOTIMAGE || abort "! insufficient partition size"
    fi
    ;;
  2 )
    ui_print "! boot image patched by unsupported programs"
    abort "! cannot uninstall"
    ;;
esac

if $BOOTMODE; then
  ui_print "- removing modules"
  $MAIN_BIN_NAME --remove-modules -n
fi

ui_print "- removing files"
rm -rf \
/cache/*ma''gisk* /cache/unblock /data/*ma''gisk* /data/cache/*ma''gisk* /data/property/*ma''gisk* \
/data/Ma''gisk.apk /data/bu''sybox /data/custom_ramdisk_patch.sh /data/a''db/*ma''gisk* \
${SECURE_DIR}/${DATA_DIR} ${SECURE_DIR}/${DB_NAME} ${SECURE_DIR}/${UDONGE_DIR} ${SECURE_DIR}/udonge ${SECURE_DIR}/post-fs-data.d ${SECURE_DIR}/service.d ${SECURE_DIR}/modules* \
/data/a''db/ms /data/a''db/ms.db /data/a''db/udonge /data/a''db/post-fs-data.d /data/a''db/service.d /data/a''db/modules* \
/data/unencrypted/ma''gisk /data/unencrypted/.mnt \
/metadata/ma''gisk /metadata/watchdog/ma''gisk /metadata/watchdog/.mnt \
/persist/ma''gisk /persist/.mnt /mnt/vendor/persist/ma''gisk /mnt/vendor/persist/.mnt

ADDOND=/system/addon.d/99-ms.sh
if [ -f $ADDOND ]; then
  blockdev --setrw /dev/block/mapper/system$SLOT 2>/dev/null
  mount -o rw,remount /system || mount -o rw,remount /
  rm -f $ADDOND
fi

cd /

if $BOOTMODE; then
  ui_print "********************************************"
  ui_print " the reisenless app will uninstall itself, and"
  ui_print " the device will reboot after a few seconds"
  ui_print "********************************************"
  (sleep 8; /system/bin/reboot)&
else
  ui_print "********************************************"
  ui_print " the reisenless app will not be uninstalled"
  ui_print " please uninstall it manually after reboot"
  ui_print "********************************************"
  recovery_cleanup
  ui_print "- done"
fi

rm -rf $TMPDIR
exit 0
