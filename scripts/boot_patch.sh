#!/system/bin/sh

































getdir() {
  case "$1" in
    */*)
      dir=${1%/*}
      if [ -z $dir ]; then
        echo "/"
      else
        echo $dir
      fi
    ;;
    *) echo "." ;;
  esac
}





if [ -z $SOURCEDMODE ]; then

  cd "$(getdir "${BASH_SOURCE:-$0}")"

  . ./util_functions.sh

  api_level_arch_detect
fi

BOOTIMAGE="$1"
[ -e "$BOOTIMAGE" ] || abort "$BOOTIMAGE does not exist!"


if [ -c "$BOOTIMAGE" ]; then
  nanddump -f boot.img "$BOOTIMAGE"
  BOOTNAND="$BOOTIMAGE"
  BOOTIMAGE=boot.img
fi


[ -z $KEEPVERITY ] && KEEPVERITY=false
[ -z $KEEPFORCEENCRYPT ] && KEEPFORCEENCRYPT=false
[ -z $PATCHVBMETAFLAG ] && PATCHVBMETAFLAG=false
[ -z $RECOVERYMODE ] && RECOVERYMODE=false
[ -z $LEGACYSAR ] && LEGACYSAR=false
export KEEPVERITY
export KEEPFORCEENCRYPT
export PATCHVBMETAFLAG

chmod -R 755 .





CHROMEOS=false
VENDORBOOT=false

ui_print "- unpacking boot image"
./mboot unpack "$BOOTIMAGE"

case $? in
  0 ) ;;
  2 )
    ui_print "- chromeos boot image detected"
    CHROMEOS=true
    ;;
  3 )
    ui_print "- vendor boot image detected"
    VENDORBOOT=true
    ;;
  * )
    abort "! unable to unpack boot image"
    ;;
esac





unset RAMDISK
for path in ramdisk.cpio vendor_ramdisk/init_boot.cpio vendor_ramdisk/ramdisk.cpio; do
  if [ -e $path ]; then
    RAMDISK=$path
    break
  fi
done

ui_print "- checking ramdisk status"
if [ -n "$RAMDISK" ]; then
  ./mboot cpio $RAMDISK test
  STATUS=$?
  SKIP_BACKUP=""
else

  RAMDISK=ramdisk.cpio

  STATUS=0
  SKIP_BACKUP="#"
fi

case $STATUS in
  0 )

    ui_print "- stock boot image detected"
    SHA1=$(./mboot sha1 "$BOOTIMAGE" 2>/dev/null)
    cat $BOOTIMAGE > stock_boot.img
    cp -af $RAMDISK ramdisk.cpio.orig 2>/dev/null
    ;;
  1 )

    ui_print "- reisenless patched boot image detected"

    if ./mboot cpio $RAMDISK "exists .backup/$BACKUP_CONFIG" 2>/dev/null; then
      ./mboot cpio $RAMDISK "extract .backup/$BACKUP_CONFIG config.orig" "restore"
    else
      ./mboot cpio $RAMDISK "extract .backup/.magisk config.orig" "restore"
    fi
    cp -af $RAMDISK ramdisk.cpio.orig
    rm -f stock_boot.img
    ;;
  2 )

    ui_print "! boot image patched by unsupported programs"
    abort "! please restore back to stock boot image"
    ;;
esac

if [ -f config.orig ]; then

  chmod 0644 config.orig
  SHA1=$(grep_prop SHA1 config.orig)
  if ! $BOOTMODE; then

    PREINITDEVICE=$(grep_prop PREINITDEVICE config.orig)
  fi
  rm config.orig
fi





ui_print "- patching ramdisk"

$BOOTMODE && [ -z "$PREINITDEVICE" ] && PREINITDEVICE=$(./$MAIN_BIN_NAME --preinit-device)


if [ "$MAIN_BIN_NAME" != "$RAMDISK_NAME" ]; then
  cp "$MAIN_BIN_NAME" "$RAMDISK_NAME" || abort "! unable to prepare ramdisk payload"
fi

for file in "$RAMDISK_NAME" "$STUB_NAME" "$INIT_LD_NAME" "$UDONGE_ARCHIVE"; do
  [ -f "$file" ] || abort "! missing installer payload: $file"
done


for file in "$RAMDISK_NAME" "$STUB_NAME" "$INIT_LD_NAME" "$UDONGE_ARCHIVE"; do
  ./mboot compress=xz "$file" "$file.xz" || abort "! unable to compress installer payload: $file"
done

echo "KEEPVERITY=$KEEPVERITY" > config
echo "KEEPFORCEENCRYPT=$KEEPFORCEENCRYPT" >> config
echo "RECOVERYMODE=$RECOVERYMODE" >> config
echo "VENDORBOOT=$VENDORBOOT" >> config
if [ -n "$PREINITDEVICE" ]; then
  ui_print "- pre-init storage partition: $PREINITDEVICE"
  echo "PREINITDEVICE=$PREINITDEVICE" >> config
fi
[ -n "$SHA1" ] && echo "SHA1=$SHA1" >> config

./mboot cpio $RAMDISK \
"add 0750 init minit" \
"mkdir 0750 overlay.d" \
"mkdir 0750 overlay.d/sbin" \
"add 0644 overlay.d/sbin/$RAMDISK_NAME.xz $RAMDISK_NAME.xz" \
"add 0644 overlay.d/sbin/$STUB_NAME.xz $STUB_NAME.xz" \
"add 0644 overlay.d/sbin/$INIT_LD_NAME.xz $INIT_LD_NAME.xz" \
"add 0600 overlay.d/sbin/$UDONGE_ARCHIVE.xz $UDONGE_ARCHIVE.xz" \
"patch" \
"$SKIP_BACKUP backup ramdisk.cpio.orig" \
"mkdir 000 .backup" \
"add 000 .backup/$BACKUP_CONFIG config" \
|| abort "! unable to patch ramdisk"

rm -f ramdisk.cpio.orig config *.xz "$RAMDISK_NAME"





for dt in dtb kernel_dtb extra; do
  if [ -f $dt ]; then
    if ! ./mboot dtb $dt test; then
      ui_print "! boot image $dt was patched by old unsupported root software"
      abort "! please try again with *unpatched* boot image"
    fi
    if ./mboot dtb $dt patch; then
      ui_print "- patch fstab in boot image $dt"
    fi
  fi
done

if [ -f kernel ]; then
  PATCHEDKERNEL=false

  ./mboot hexpatch kernel \
  49010054011440B93FA00F71E9000054010840B93FA00F7189000054001840B91FA00F7188010054 \
  A1020054011440B93FA00F7140020054010840B93FA00F71E0010054001840B91FA00F7181010054 \
  && PATCHEDKERNEL=true




  ./mboot hexpatch kernel 821B8012 E2FF8F12 && PATCHEDKERNEL=true



  ./mboot hexpatch kernel \
  70726F63615F636F6E66696700 \
  70726F63615F6D616769736B00 \
  && PATCHEDKERNEL=true



  $LEGACYSAR && ./mboot hexpatch kernel \
  736B69705F696E697472616D667300 \
  77616E745F696E697472616D667300 \
  && PATCHEDKERNEL=true



  $PATCHEDKERNEL || rm -f kernel
fi





ui_print "- repacking boot image"
./mboot repack "$BOOTIMAGE" || abort "! unable to repack boot image"


$CHROMEOS && sign_chromeos


[ -e "$BOOTNAND" ] && BOOTIMAGE="$BOOTNAND"


true
