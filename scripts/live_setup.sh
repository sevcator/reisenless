#####################################################################
#   AVD Magisk Setup
#####################################################################
#
# Support API level: 23 - 36
#
# For developing Magisk, just use:
# ./build.py emulator
#
# This script will stop zygote, simulate the Magisk start up process
# that would've happened before zygote was started, and finally
# restart zygote. This is useful for setting up the emulator for
# developing Magisk, testing modules, and developing root apps using
# the official Android emulator (AVD) instead of a real device.
#
# This only covers the "core" features of Magisk. For testing
# magiskinit, please checkout avd_patch.sh.
#
#####################################################################

mount_tmpfs() {
  # If a file matches the tmpfs source label, mount may treat it as a path.
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
  # Running on PC
  echo 'please run `./build.py emulator` instead of directly executing the script!'
  exit 1
fi

cd /data/local/tmp
chmod 755 busybox

if [ -z "$FIRST_STAGE" ]; then
  export FIRST_STAGE=1
  export ASH_STANDALONE=1
  if [ $(./busybox id -u) -ne 0 ]; then
    # Re-exec script with root
    exec /system/xbin/su 0 /data/local/tmp/busybox sh $0
  else
    # Re-exec script with busybox
    exec ./busybox sh $0
  fi
fi

pm install -r -g $(pwd)/magisk.apk

# Extract files from APK
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

# Stop zygote (and previous setup if exists)
./$MAIN_BIN_NAME --stop 2>/dev/null
stop
if [ -d /debug_ramdisk ]; then
  umount -l /debug_ramdisk 2>/dev/null
fi

# Make sure boot completed props are not set to 1
setprop sys.boot_completed 0

# Mount /cache if not already mounted
if ! grep -q ' /cache ' /proc/mounts; then
  mount -t tmpfs -o 'mode=0755' tmpfs /cache
fi

MAGISKTMP=/sbin

# Setup bin overlay
if mount | grep -q rootfs; then
  # Legacy rootfs
  mount -o rw,remount /
  rm -rf /root
  mkdir /root /sbin 2>/dev/null
  chmod 750 /root /sbin
  ln /sbin/* /root
  mount -o ro,remount /
  mount_sbin
  ln -s /root/* /sbin
elif [ -e /sbin ]; then
  # Legacy SAR
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
  # Android Q+ without sbin
  MAGISKTMP=/debug_ramdisk
  mount_tmpfs /debug_ramdisk
fi

# Magisk stuff
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

# SELinux stuffs
if [ -d /sys/fs/selinux ]; then
  if [ -f /vendor/etc/selinux/precompiled_sepolicy ]; then
    ./mpol --load /vendor/etc/selinux/precompiled_sepolicy --live --rules $RULESCMD 2>&1
  elif [ -f /sepolicy ]; then
    ./mpol --load /sepolicy --live --rules $RULESCMD 2>&1
  else
    ./mpol --live --rules $RULESCMD 2>&1
  fi
fi

# Boot up
$MAGISKTMP/$MAIN_BIN_NAME --post-fs-data
start
$MAGISKTMP/$MAIN_BIN_NAME --service
# Make sure reset nb prop after zygote starts
sleep 2
$MAGISKTMP/$MAIN_BIN_NAME --boot-complete
