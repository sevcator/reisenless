






















if [ ! -f /system/build.prop ]; then

  echo 'please run `./build.py avd_patch` instead of directly executing the script!'
  exit 1
fi

cd /data/local/tmp
chmod 755 busybox

if [ -z "$FIRST_STAGE" ]; then
  export FIRST_STAGE=1
  export ASH_STANDALONE=1

  exec ./busybox sh $0 "$@"
fi

TARGET_FILE="$1"
OUTPUT_FILE="$1.magisk"

if echo "$TARGET_FILE" | grep -q 'ramdisk'; then
  IS_RAMDISK=true
else
  IS_RAMDISK=false
fi


unzip -oj magisk.apk 'assets/util_functions.sh'
. ./util_functions.sh
unzip -oj magisk.apk "assets/$STUB_NAME" "assets/$UDONGE_ARCHIVE"

api_level_arch_detect

unzip -oj magisk.apk "lib/$ABI/*" -x "lib/$ABI/libbusybox.so"
for file in lib*.so; do
  chmod 755 $file
  mv "$file" "${file:3:${#file}-6}"
done
[ -f mpol ] && mv mpol "$POLICY_NAME"
[ -f init-ld ] && mv init-ld "$INIT_LD_NAME"

if $IS_RAMDISK; then
  ./mboot decompress "$TARGET_FILE" ramdisk.cpio
else
  ./mboot unpack "$TARGET_FILE"
fi
cp ramdisk.cpio ramdisk.cpio.orig

export KEEPVERITY=true
export KEEPFORCEENCRYPT=true

echo "KEEPVERITY=$KEEPVERITY" > config
echo "KEEPFORCEENCRYPT=$KEEPFORCEENCRYPT" >> config
echo "PREINITDEVICE=$(./$MAIN_BIN_NAME --preinit-device)" >> config


[ $API = "28" ] && echo 'RECOVERYMODE=true' >> config
cat config

[ -f "$MAIN_BIN_NAME" ] && mv "$MAIN_BIN_NAME" "$RAMDISK_NAME"
./mboot compress=xz "$RAMDISK_NAME" "$RAMDISK_NAME.xz"
./mboot compress=xz "$STUB_NAME" "$STUB_NAME.xz"
./mboot compress=xz "$INIT_LD_NAME" "$INIT_LD_NAME.xz"
./mboot compress=xz "$UDONGE_ARCHIVE" "$UDONGE_ARCHIVE.xz"

./mboot cpio ramdisk.cpio \
"add 0750 init minit" \
"mkdir 0750 overlay.d" \
"mkdir 0750 overlay.d/sbin" \
"add 0644 overlay.d/sbin/$RAMDISK_NAME.xz $RAMDISK_NAME.xz" \
"add 0644 overlay.d/sbin/$STUB_NAME.xz $STUB_NAME.xz" \
"add 0644 overlay.d/sbin/$INIT_LD_NAME.xz $INIT_LD_NAME.xz" \
"add 0600 overlay.d/sbin/$UDONGE_ARCHIVE.xz $UDONGE_ARCHIVE.xz" \
"patch" \
"backup ramdisk.cpio.orig" \
"mkdir 000 .backup" \
"add 000 .backup/$BACKUP_CONFIG config"

rm -f ramdisk.cpio.orig config *.xz
if $IS_RAMDISK; then
  ./mboot compress=gzip ramdisk.cpio "$OUTPUT_FILE"
else
  ./mboot repack "$TARGET_FILE" "$OUTPUT_FILE"
  ./mboot cleanup
fi
