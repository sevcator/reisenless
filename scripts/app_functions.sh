##################################
# Magisk app internal scripts
##################################

#SECURE_DIR_STUB
#BUILD_IDENTITY_STUB

# BusyBox uses argv[0] to select multicall mode. Its filename is randomized
# per build, so provide the expected argv[0] without exposing a stable path.
run_busybox() (
  local binary="$1"
  shift
  exec -a busybox "$binary" "$@"
)

merge_missing_tree() {
  local source="$1"
  local destination="$2"
  local item name target

  mkdir -p "$destination" || return 1
  for item in "$source"/* "$source"/.[!.]* "$source"/..?*; do
    [ -e "$item" ] || [ -L "$item" ] || continue
    name=${item##*/}
    target="$destination/$name"
    if [ -d "$item" ]; then
      merge_missing_tree "$item" "$target" || return 1
    elif [ ! -e "$target" ] && [ ! -L "$target" ]; then
      cp -af "$item" "$target" || return 1
    fi
  done
  return 0
}

# $1 = delay
# $2 = command
run_delay() {
  (sleep $1; $2)&
}

# $1 = version string
# $2 = version code
env_check() {
  for file in "$MAIN_BIN_NAME" "$BUSYBOX_NAME" mboot minit util_functions.sh boot_patch.sh "$UDONGE_ARCHIVE"; do
    [ -f "$MAGISKBIN/$file" ] || return 1
  done
  if [ "$2" -ge 25000 ]; then
    [ -f "$MAGISKBIN/$POLICY_NAME" ] || return 1
  fi
  if [ "$2" -ge 25210 ]; then
    [ -b "$MAGISKTMP/$INTERNAL_DIR/device/preinit" ] || [ -b "$MAGISKTMP/$INTERNAL_DIR/block/preinit" ] || return 2
  fi
  grep -xqF "MAGISK_VER='$1'" "$MAGISKBIN/util_functions.sh" || return 3
  grep -xqF "MAGISK_VER_CODE=$2" "$MAGISKBIN/util_functions.sh" || return 3
  return 0
}

# $1 = dir to copy
# $2 = destination (optional)
cp_readlink() {
  if [ -z $2 ]; then
    cd $1
  else
    cp -af $1/. $2
    cd $2
  fi
  for file in *; do
    if [ -L $file ]; then
      local full=$(readlink -f $file)
      rm $file
      cp -af $full $file
    fi
  done
  chmod -R 755 .
  cd /
}

# $1 = install dir
fix_env() {
  # Cleanup and make dirs
  rm -rf $MAGISKBIN/*
  mkdir -p $MAGISKBIN 2>/dev/null
  chmod 700 ${SECURE_DIR}
  cp_readlink $1 $MAGISKBIN
  rm -rf $1
  chown -R 0:0 $MAGISKBIN
}

migrate_legacy_layout() {
  local legacy=/data/a''db
  local legacy_udonge=${SECURE_DIR}/udonge
  local current_udonge=${SECURE_DIR}/${UDONGE_DIR}

  # Builds predating per-build Udonge paths stored user state under the
  # recognizable "udonge" directory even when the main secure root was
  # already randomized. Merge only files absent from the current location,
  # then remove the stale path so it cannot remain as a detection signal.
  if [ "$UDONGE_DIR" != "udonge" ] && [ -d "$legacy_udonge" ]; then
    merge_missing_tree "$legacy_udonge" "$current_udonge" || return 1
    rm -rf "$legacy_udonge" || return 1
  fi

  [ "$SECURE_DIR" = "$legacy" ] && return 0
  [ -d "$legacy" ] || return 0

  mkdir -p "$SECURE_DIR" || return 1
  if [ -f "$legacy/ms.db" ] && [ ! -f "$SECURE_DIR/$DB_NAME" ]; then
    cp -af "$legacy/ms.db" "$SECURE_DIR/$DB_NAME" || return 1
  fi
  for dir in modules modules_update post-fs-data.d service.d; do
    if [ -d "$legacy/$dir" ]; then
      mkdir -p "$SECURE_DIR/$dir" || return 1
      cp -af "$legacy/$dir/." "$SECURE_DIR/$dir/" || return 1
    fi
  done
  if [ -d "$legacy/udonge" ] && [ ! -d "$SECURE_DIR/$UDONGE_DIR" ]; then
    cp -af "$legacy/udonge" "$SECURE_DIR/$UDONGE_DIR" || return 1
  fi
  rm -f "$SECURE_DIR/post-fs-data.d/udonge.sh" "$SECURE_DIR/service.d/udonge.sh"
  rm -f "$SECURE_DIR/post-fs-data.d/$STAGE_SCRIPT" "$SECURE_DIR/service.d/$STAGE_SCRIPT"

  for backup in /data/ms_''backup_*; do
    [ -d "$backup" ] || continue
    local suffix=${backup#/data/ms_backup_}
    [ -d "${BACKUP_PREFIX}${suffix}" ] || mv "$backup" "${BACKUP_PREFIX}${suffix}"
  done

  rm -rf "$legacy/ms" "$legacy/ms.db" "$legacy/udonge" \
    "$legacy/modules" "$legacy/modules_update" \
    "$legacy/post-fs-data.d" "$legacy/service.d"
  return 0
}

refresh_udonge_runtime() {
  local root=${SECURE_DIR}/${UDONGE_DIR}
  local runtime=$root/runtime
  local next=$root/runtime.new
  local old=$root/runtime.old
  local archive=$MAGISKBIN/$UDONGE_ARCHIVE
  local version required

  [ -f "$archive" ] || return 0
  version=$(run_busybox "$MAGISKBIN/$BUSYBOX_NAME" unzip -p "$archive" version 2>/dev/null | tr -d '\r\n')
  [ -n "$version" ] || return 1

  rm -rf "$next"
  mkdir -p "$next" || return 1
  run_busybox "$MAGISKBIN/$BUSYBOX_NAME" unzip -oq "$archive" -d "$next" || {
    rm -rf "$next"
    return 1
  }

  required="version hideapps.dex post-fs-data.sh service.sh defaults/keybox.xml defaults/keybox_urls.conf defaults/pif.conf defaults/props.conf defaults/targets.conf"
  case "$ARCH" in
    arm64)
      required="$required zygisk/arm64-v8a.so tee/arm64-v8a/inject tee/arm64-v8a/libTEESimulator.so tee/arm64-v8a/libcertgen.so tee/arm64-v8a/supervisor tee/classes.dex tee/daemon"
      ;;
    arm)
      required="$required zygisk/armeabi-v7a.so tee/armeabi-v7a/inject tee/armeabi-v7a/libTEESimulator.so tee/armeabi-v7a/supervisor tee/classes.dex tee/daemon"
      ;;
    x64)
      required="$required zygisk/x86_64.so"
      ;;
    x86)
      required="$required zygisk/x86.so"
      ;;
  esac
  for file in $required; do
    [ -f "$next/$file" ] || {
      rm -rf "$next"
      return 1
    }
  done
  [ "$(cat "$next/version" 2>/dev/null)" = "$version" ] || {
    rm -rf "$next"
    return 1
  }

  mkdir -p "$root" || return 1
  rm -rf "$old"
  [ ! -d "$runtime" ] || mv "$runtime" "$old" || {
    rm -rf "$next"
    return 1
  }
  if mv "$next" "$runtime"; then
    rm -rf "$old"
    chmod -R 600 "$runtime"
    find "$runtime" -type d -exec chmod 700 {} \;
    chmod 700 "$runtime/post-fs-data.sh" "$runtime/service.sh"
    return 0
  fi
  [ -d "$runtime" ] || [ ! -d "$old" ] || mv "$old" "$runtime"
  rm -rf "$next"
  return 1
}

# $1 = install dir
# $2 = boot partition
direct_install() {
  echo "- flashing new boot image"
  flash_image $1/new-boot.img $2
  case $? in
    1)
      echo "! insufficient partition size"
      return 1
      ;;
    2)
      echo "! $2 is read only"
      return 2
      ;;
  esac

  rm -f $1/new-boot.img
  migrate_legacy_layout || return 3
  fix_env $1
  refresh_udonge_runtime || return 3
  # Udonge is invoked directly by the native boot-stage handler. Remove
  # launchers from older releases so each stage executes exactly once.
  rm -f "$SECURE_DIR/post-fs-data.d/udonge.sh" "$SECURE_DIR/service.d/udonge.sh"
  rm -f "$SECURE_DIR/post-fs-data.d/$STAGE_SCRIPT" "$SECURE_DIR/service.d/$STAGE_SCRIPT"
  run_migrations

  return 0
}

# $1 = uninstaller zip
run_uninstaller() {
  rm -rf "$BUILD_TMPDIR"
  mkdir -p "$BUILD_TMPDIR/install"
  unzip -o "$1" "assets/*" "lib/*" -d "$BUILD_TMPDIR/install"
  INSTALLER="$BUILD_TMPDIR/install" sh "$BUILD_TMPDIR/install/assets/uninstaller.sh" dummy 1 "$1"
}

# $1 = boot partition
restore_imgs() {
  local SHA1=$(grep_prop SHA1 $MAGISKTMP/$INTERNAL_DIR/config)
  local BACKUPDIR=${BACKUP_PREFIX}${SHA1}
  [ -d $BACKUPDIR ] || return 1
  [ -f $BACKUPDIR/boot.img.gz ] || return 1
  flash_image $BACKUPDIR/boot.img.gz $1
}

# $1 = path to bootctl executable
post_ota() {
  cd ${SECURE_DIR}
  cp -f $1 bootctl
  rm -f $1
  chmod 755 bootctl
  if ! ./bootctl hal-info; then
    rm -f bootctl
    return
  fi
  SLOT_NUM=0
  [ $(./bootctl get-current-slot) -eq 0 ] && SLOT_NUM=1
  ./bootctl set-active-boot-slot $SLOT_NUM
  cat << EOF > post-fs-data.d/post_ota.sh
${SECURE_DIR}/bootctl mark-boot-successful
rm -f ${SECURE_DIR}/bootctl
rm -f ${SECURE_DIR}/post-fs-data.d/post_ota.sh
EOF
  chmod 755 post-fs-data.d/post_ota.sh
  cd /
}

# $1 = APK
# $2 = package name
adb_pm_install() {
  local tmp=/data/local/tmp/temp.apk
  cp -f "$1" $tmp
  chmod 644 $tmp
  # Run the package manager as root first.  On current Android releases the
  # shell UID routes dynamically repackaged APKs through Play Protect and
  # leaves an interactive verification dialog instead of completing the
  # install.  AppMigration already runs this helper from a root shell, so the
  # direct root install is both silent and reliable.  Keep the old fallbacks
  # for non-root callers and older devices.
  pm install -g $tmp || su 2000 -c pm install -g $tmp || su 1000 -c pm install -g $tmp
  local res=$?
  rm -f $tmp
  if [ $res = 0 ]; then
    appops set "$2" REQUEST_INSTALL_PACKAGES allow
  fi
  return $res
}

check_boot_ramdisk() {
  # Create boolean ISAB
  ISAB=true
  [ -z $SLOT ] && ISAB=false

  # If we are A/B, then we must have ramdisk
  $ISAB && return 0

  # If we are using legacy SAR, but not A/B, assume we do not have ramdisk
  if $LEGACYSAR; then
    # Override recovery mode to true
    RECOVERYMODE=true
    return 1
  fi

  return 0
}

check_encryption() {
  if $ISENCRYPTED; then
    if [ $SDK_INT -lt 24 ]; then
      CRYPTOTYPE="block"
    else
      # First see what the system tells us
      CRYPTOTYPE=$(getprop ro.crypto.type)
      if [ -z $CRYPTOTYPE ]; then
        # If not mounting through device mapper, we are FBE
        if grep ' /data ' /proc/mounts | grep -qv 'dm-'; then
          CRYPTOTYPE="file"
        else
          # We are either FDE or metadata encryption (which is also FBE)
          CRYPTOTYPE="block"
          grep -q ' /metadata ' /proc/mounts && CRYPTOTYPE="file"
        fi
      fi
    fi
  else
    CRYPTOTYPE="N/A"
  fi
}

printvar() {
  eval echo $1=\$$1
}

run_action() {
  local MODID="$1"
  cd "${SECURE_DIR}/modules/$MODID"
  sh ./action.sh
  local RES=$?
  cd /
  return $RES
}

##########################
# Non-root util_functions
##########################

mount_partitions() {
  [ "$(getprop ro.build.ab_update)" = "true" ] && SLOT=$(getprop ro.boot.slot_suffix)
  # Check whether non rootfs root dir exists
  SYSTEM_AS_ROOT=false
  grep ' / ' /proc/mounts | grep -qv 'rootfs' && SYSTEM_AS_ROOT=true

  LEGACYSAR=false
  grep ' / ' /proc/mounts | grep -q '/dev/root' && LEGACYSAR=true
}

get_flags() {
  KEEPVERITY=$SYSTEM_AS_ROOT
  ISENCRYPTED=false
  [ "$(getprop ro.crypto.state)" = "encrypted" ] && ISENCRYPTED=true
  KEEPFORCEENCRYPT=$ISENCRYPTED
  if [ -n "$(getprop ro.boot.vbmeta.device)" -o -n "$(getprop ro.boot.vbmeta.size)" ]; then
    PATCHVBMETAFLAG=false
  elif getprop ro.product.ab_ota_partitions | grep -wq vbmeta; then
    PATCHVBMETAFLAG=false
  else
    PATCHVBMETAFLAG=true
  fi
  [ -z $RECOVERYMODE ] && RECOVERYMODE=false
  [ -z $VENDORBOOT ] && VENDORBOOT=false
}

run_migrations() { return; }

grep_prop() { return; }

#############
# Initialize
#############

app_init() {
  mount_partitions >/dev/null
  RAMDISKEXIST=false
  check_boot_ramdisk && RAMDISKEXIST=true
  get_flags >/dev/null
  run_migrations >/dev/null
  check_encryption

  # Dump variables
  printvar SLOT
  printvar SYSTEM_AS_ROOT
  printvar RAMDISKEXIST
  printvar ISAB
  printvar CRYPTOTYPE
  printvar PATCHVBMETAFLAG
  printvar LEGACYSAR
  printvar RECOVERYMODE
  printvar KEEPVERITY
  printvar KEEPFORCEENCRYPT
  printvar VENDORBOOT
}

export BOOTMODE=true
