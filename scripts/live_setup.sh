



















mount_tmpfs() {

  local source=${MAIN_BIN_NAME:-ms}
  [ -f "$source" ] && mv "$source" "$source.tmp"
  mount -t tmpfs -o 'mode=0755' "$source" $1
  [ -f "$source.tmp" ] && mv "$source.tmp" "$source"
}

mount_sbin() {
  mount_tmpfs /sbin
  chcon u:object_r:rootfs:s0 /sbin
}

if [ ! -f /system/build.prop ]; then

  echo 'please run `./build.py emulator` instead of directly executing the script!'
  exit 1
fi

cd /data/local/tmp
chmod 755 busybox

if [ -z "$FIRST_STAGE" ]; then
  export FIRST_STAGE=1
  export ASH_STANDALONE=1
  if [ $(./busybox id -u) -ne 0 ]; then

    exec /system/xbin/su 0 /data/local/tmp/busybox sh $0
  else

    exec ./busybox sh $0
  fi
fi

pm install -r -g $(pwd)/magisk.apk


unzip -oj magisk.apk 'assets/util_functions.sh'
. ./util_functions.sh
unzip -oj magisk.apk "assets/$STUB_NAME" "assets/$UDONGE_ARCHIVE"

api_level_arch_detect

unzip -oj magisk.apk "lib/$ABI/*" -x "lib/$ABI/libbusybox.so"
for file in lib*.so; do
  chmod 755 $file
  mv "$file" "${file:3:${#file}-6}"
done

if $IS64BIT && [ -e "/system/bin/linker" ]; then
  unzip -oj magisk.apk "lib/$ABI32/libmagisk.so"
  mv libmagisk.so "$BIN32_NAME"
  chmod 755 "$BIN32_NAME"
fi

[ -f magisk ] && mv magisk "$MAIN_BIN_NAME"
[ -f mpol ] && mv mpol "$POLICY_NAME"
[ -f init-ld ] && mv init-ld "$INIT_LD_NAME"


./$MAIN_BIN_NAME --stop 2>/dev/null
stop
if [ -d /debug_ramdisk ]; then
  umount -l /debug_ramdisk 2>/dev/null
fi


setprop sys.boot_completed 0


if ! grep -q ' /cache ' /proc/mounts; then
  mount -t tmpfs -o 'mode=0755' tmpfs /cache
fi

MAGISKTMP=/sbin


if mount | grep -q rootfs; then

  mount -o rw,remount /
  rm -rf /root
  mkdir /root /sbin 2>/dev/null
  chmod 750 /root /sbin
  ln /sbin/* /root
  mount -o ro,remount /
  mount_sbin
  ln -s /root/* /sbin
elif [ -e /sbin ]; then

  mount_sbin
  mkdir -p /dev/sysroot
  block=$(mount | grep ' / ' | awk '{ print $1 }')
  [ $block = "/dev/root" ] && block=/dev/block/vda1
  mount -o ro $block /dev/sysroot
  for file in /dev/sysroot/sbin/*; do
    [ ! -e $file ] && break
    if [ -L $file ]; then
      cp -af $file /sbin
    else
      sfile=/sbin/$(basename $file)
      touch $sfile
      mount -o bind $file $sfile
    fi
  done
  umount -l /dev/sysroot
  rm -rf /dev/sysroot
else

  MAGISKTMP=/debug_ramdisk
  mount_tmpfs /debug_ramdisk
fi


mkdir -p $MAGISKBIN 2>/dev/null
unzip -oj magisk.apk 'assets/*.sh' -d $MAGISKBIN
mkdir ${SECURE_DIR}/modules 2>/dev/null
mkdir ${SECURE_DIR}/post-fs-data.d 2>/dev/null
mkdir ${SECURE_DIR}/service.d 2>/dev/null

for file in "$MAIN_BIN_NAME" "$POLICY_NAME" "$STUB_NAME"; do
  [ -f "./$file" ] || continue
  chmod 755 ./$file
  cp -af ./$file $MAGISKTMP/$file
  cp -af ./$file $MAGISKBIN/$file
done
[ -f "./$BIN32_NAME" ] && {
  chmod 755 "./$BIN32_NAME"
  cp -af "./$BIN32_NAME" $MAGISKTMP/${MAIN_BIN_NAME}32
  cp -af "./$BIN32_NAME" "$MAGISKBIN/$BIN32_NAME"
}
cp -af ./mboot $MAGISKBIN/mboot
cp -af ./minit $MAGISKBIN/minit
cp -af ./busybox "$MAGISKBIN/$BUSYBOX_NAME"
[ -f "./$UDONGE_ARCHIVE" ] && {
  chmod 600 "./$UDONGE_ARCHIVE"
  cp -af "./$UDONGE_ARCHIVE" "$MAGISKTMP/$UDONGE_ARCHIVE"
  cp -af "./$UDONGE_ARCHIVE" "$MAGISKBIN/$UDONGE_ARCHIVE"
}

ln -s ./$MAIN_BIN_NAME $MAGISKTMP/su
ln -s ./$MAIN_BIN_NAME $MAGISKTMP/resetprop
ln -s "./$POLICY_NAME" $MAGISKTMP/supolicy

mkdir -p "$MAGISKTMP/$INTERNAL_DIR/device"
mkdir -p "$MAGISKTMP/$INTERNAL_DIR/worker"
mount_tmpfs "$MAGISKTMP/$INTERNAL_DIR/worker"
mount --make-private "$MAGISKTMP/$INTERNAL_DIR/worker"
touch "$MAGISKTMP/$INTERNAL_DIR/config"

export MAGISKTMP
MAKEDEV=1 $MAGISKTMP/$MAIN_BIN_NAME --preinit-device 2>&1

RULESCMD=""
rule="$MAGISKTMP/$INTERNAL_DIR/preinit/sepolicy.rule"
[ -f "$rule" ] && RULESCMD="--apply $rule"


if [ -d /sys/fs/selinux ]; then
  if [ -f /vendor/etc/selinux/precompiled_sepolicy ]; then
    ./mpol --load /vendor/etc/selinux/precompiled_sepolicy --live --rules $RULESCMD 2>&1
  elif [ -f /sepolicy ]; then
    ./mpol --load /sepolicy --live --rules $RULESCMD 2>&1
  else
    ./mpol --live --rules $RULESCMD 2>&1
  fi
fi


$MAGISKTMP/$MAIN_BIN_NAME --post-fs-data
start
$MAGISKTMP/$MAIN_BIN_NAME --service

sleep 2
$MAGISKTMP/$MAIN_BIN_NAME --boot-complete
