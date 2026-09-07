: SECURE_DIR_STUB
: BUILD_IDENTITY_STUB

# The app uses a neutral transport variable so DEX identity rewriting cannot
# rename one half of this interface. Retain the canonical name for scripts and
# modules, and leave standalone installer environments unchanged.
[ -n "$ROOT_TMP" ] && export MAGISKTMP="$ROOT_TMP"










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

migration_hash_tree() {
  local source="$1" relative name
  [ -e "$source" ] || return 0
  if [ -f "$source" ]; then
    sha256sum "$source" 2>/dev/null
    return $?
  fi
  (
    cd "$source" || exit 1
    find . -type f -print 2>/dev/null | sort | while IFS= read -r relative; do
      case "${relative#./}" in
        runtime/*|runtime.old/*|runtime.new/*|tee-runtime/*|tee-runtime.old/*|tee-runtime.new/*)
          continue
          ;;
      esac
      name=${relative##*/}
      case "$name" in
        *.pid|.pid|.pid-start|.pid-boot|unloaded|pending-reboot|\
        .keybox-refresh|.keybox-checked|tee-unavailable|rom_keywords.conf|.rom-catalog-v2)
          continue
          ;;
      esac
      sha256sum "$relative" 2>/dev/null || exit 1
    done
  )
}

copy_durable_tree() {
  local source="$1" destination="$2" item name
  [ -d "$source" ] || return 0
  mkdir -p "$destination" || return 1
  for item in "$source"/* "$source"/.[!.]* "$source"/..?*; do
    [ -e "$item" ] || [ -L "$item" ] || continue
    name=${item##*/}
    case "$name" in
      runtime|runtime.old|runtime.new|tee-runtime|tee-runtime.old|tee-runtime.new|\
      *.pid|.pid|.pid-start|.pid-boot|unloaded|pending-reboot|\
      .keybox-refresh|.keybox-checked|tee-unavailable|rom_keywords.conf|.rom-catalog-v2)
        continue
        ;;
    esac
    if [ -d "$item" ]; then
      copy_durable_tree "$item" "$destination/$name" || return 1
    else
      cp -af "$item" "$destination/$name" || return 1
    fi
  done
}

validate_migration_db() {
  local database="$1" result
  [ -f "$database" ] || return 0
  [ "$(head -c 15 "$database" 2>/dev/null)" = "SQLite format 3" ] || return 1
  if command -v sqlite3 >/dev/null 2>&1; then
    result=$(sqlite3 "$database" 'PRAGMA quick_check;' 2>/dev/null) || return 1
    [ "$result" = "ok" ] || return 1
  fi
  return 0
}

validate_migration_modules() {
  local root="$1" module module_id
  [ -d "$root" ] || return 0
  for module in "$root"/*; do
    [ -d "$module" ] || continue
    [ -f "$module/module.prop" ] || return 1
    module_id=$(sed -n 's/^id=//p' "$module/module.prop" | head -n 1)
    [ -n "$module_id" ] || return 1
    [ "$module_id" = "${module##*/}" ] || return 1
    case "$module_id" in *[!A-Za-z0-9._-]*) return 1;; esac
  done
  return 0
}

transactional_migrate_layout() {
  local source="$1" source_db="$2" source_udonge="$3" marker_name="$4"
  local marker="$SECURE_DIR/$marker_name" stage="$SECURE_DIR/.migration-stage.$$"
  local manifest="$stage/source.sha256" existing="$SECURE_DIR/.migration-source.tmp.$$"
  local dir

  [ "$source" != "$SECURE_DIR" ] || return 0
  [ -d "$source" ] || return 0
  [ -n "$source_db" ] || return 1
  [ -n "$source_udonge" ] || return 1
  mkdir -p "$SECURE_DIR" || return 1

  rm -rf "$stage"
  mkdir -p "$stage" || return 1
  {
    migration_hash_tree "$source/$source_db" || exit 1
    for dir in modules modules_update post-fs-data.d service.d; do
      migration_hash_tree "$source/$dir" || exit 1
    done
    migration_hash_tree "$source/$source_udonge/state" || exit 1
    migration_hash_tree "$source/$source_udonge/tee-state" || exit 1
  } > "$manifest" || { rm -rf "$stage"; return 1; }

  if [ -f "$marker" ]; then
    sed '1d' "$marker" > "$existing" 2>/dev/null || true
    if cmp -s "$manifest" "$existing"; then
      rm -f "$existing"
      rm -rf "$stage"
      return 0
    fi
    rm -f "$existing"
  fi

  if [ -f "$source/$source_db" ]; then
    cp -af "$source/$source_db" "$stage/$DB_NAME" || { rm -rf "$stage"; return 1; }
    validate_migration_db "$stage/$DB_NAME" || { rm -rf "$stage"; return 1; }
  fi
  for dir in modules modules_update post-fs-data.d service.d; do
    [ -d "$source/$dir" ] || continue
    copy_durable_tree "$source/$dir" "$stage/$dir" || { rm -rf "$stage"; return 1; }
  done
  validate_migration_modules "$stage/modules" || { rm -rf "$stage"; return 1; }
  validate_migration_modules "$stage/modules_update" || { rm -rf "$stage"; return 1; }
  copy_durable_tree "$source/$source_udonge/state" "$stage/$UDONGE_DIR/state" || {
    rm -rf "$stage"; return 1;
  }
  copy_durable_tree "$source/$source_udonge/tee-state" "$stage/$UDONGE_DIR/tee-state" || {
    rm -rf "$stage"; return 1;
  }

  if [ -f "$stage/$DB_NAME" ] && [ ! -f "$SECURE_DIR/$DB_NAME" ]; then
    mv "$stage/$DB_NAME" "$SECURE_DIR/$DB_NAME" || { rm -rf "$stage"; return 1; }
  fi
  for dir in modules modules_update post-fs-data.d service.d "$UDONGE_DIR"; do
    [ -d "$stage/$dir" ] || continue
    merge_missing_tree "$stage/$dir" "$SECURE_DIR/$dir" || { rm -rf "$stage"; return 1; }
  done
  {
    printf 'source=%s\n' "$source"
    cat "$manifest"
  } > "$marker.new" || { rm -rf "$stage"; return 1; }
  mv "$marker.new" "$marker" || { rm -rf "$stage"; return 1; }
  chmod 600 "$marker"
  rm -rf "$stage"
  return 0
}



run_delay() {
  (sleep $1; $2)&
}



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


fix_env() {

  rm -rf $MAGISKBIN/*
  mkdir -p $MAGISKBIN 2>/dev/null
  chmod 700 ${SECURE_DIR}
  cp_readlink $1 $MAGISKBIN
  rm -rf $1
  chown -R 0:0 $MAGISKBIN
}

migrate_legacy_layout() {
  local legacy=/data/a''db
  transactional_migrate_layout "$legacy" ms.db udonge .migration-canonical.complete || return 1
  rm -f "$SECURE_DIR/post-fs-data.d/udonge.sh" "$SECURE_DIR/service.d/udonge.sh"
  rm -f "$SECURE_DIR/post-fs-data.d/$STAGE_SCRIPT" "$SECURE_DIR/service.d/$STAGE_SCRIPT"
  return 0
}

migrate_private_layout() {
  [ -n "$LEGACY_SECURE_DIR" ] || return 0
  [ "$LEGACY_SECURE_DIR" != "$SECURE_DIR" ] || return 0
  [ -d "$LEGACY_SECURE_DIR" ] || return 0
  [ -n "$LEGACY_DB_NAME" ] || return 1
  [ -n "$LEGACY_UDONGE_DIR" ] || return 1

  transactional_migrate_layout "$LEGACY_SECURE_DIR" "$LEGACY_DB_NAME" \
    "$LEGACY_UDONGE_DIR" .migration-private.complete
}

refresh_udonge_runtime() {
  local root=${SECURE_DIR}/${UDONGE_DIR}
  local runtime=$root/runtime
  local next=$root/runtime.new
  local old=$root/runtime.old
  local archive=$MAGISKBIN/$UDONGE_ARCHIVE
  local version required

  [ -f "$archive" ] || {
    ui_print "! protection payload is missing"
    return 1
  }
  [ -x "$MAGISKBIN/$BUSYBOX_NAME" ] || {
    ui_print "! installer utility is missing"
    return 1
  }
  version=$(run_busybox "$MAGISKBIN/$BUSYBOX_NAME" unzip -p "$archive" version 2>/dev/null | tr -d '\r\n')
  [ -n "$version" ] || {
    ui_print "! protection payload has no version"
    return 1
  }

  rm -rf "$next"
  mkdir -p "$next" || return 1
  run_busybox "$MAGISKBIN/$BUSYBOX_NAME" unzip -oq "$archive" -d "$next" || {
    ui_print "! protection payload extraction failed"
    rm -rf "$next"
    return 1
  }

  required="version hideapps.dex post-fs-data.sh service.sh stop.sh defaults/keybox.xml defaults/keybox_urls.conf defaults/pif.conf defaults/props.conf defaults/targets.conf"
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
      ui_print "! protection payload is incomplete: $file"
      rm -rf "$next"
      return 1
    }
  done
  [ "$(cat "$next/version" 2>/dev/null)" = "$version" ] || {
    ui_print "! protection payload version mismatch"
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
    chmod 700 "$runtime/post-fs-data.sh" "$runtime/service.sh" "$runtime/stop.sh"
    return 0
  fi
  [ -d "$runtime" ] || [ ! -d "$old" ] || mv "$old" "$runtime"
  rm -rf "$next"
  return 1
}



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
  migrate_private_layout || return 3
  migrate_legacy_layout || return 3
  fix_env $1
  refresh_udonge_runtime || return 3


  rm -f "$SECURE_DIR/post-fs-data.d/udonge.sh" "$SECURE_DIR/service.d/udonge.sh"
  rm -f "$SECURE_DIR/post-fs-data.d/$STAGE_SCRIPT" "$SECURE_DIR/service.d/$STAGE_SCRIPT"
  run_migrations

  return 0
}


run_uninstaller() {
  rm -rf "$BUILD_TMPDIR"
  mkdir -p "$BUILD_TMPDIR/install"
  unzip -o "$1" "assets/*" "lib/*" -d "$BUILD_TMPDIR/install"
  INSTALLER="$BUILD_TMPDIR/install" sh "$BUILD_TMPDIR/install/assets/uninstaller.sh" dummy 1 "$1"
}


restore_imgs() {
  local SHA1=$(grep_prop SHA1 $MAGISKTMP/$INTERNAL_DIR/config)
  local BACKUPDIR=${BACKUP_PREFIX}${SHA1}
  [ -d $BACKUPDIR ] || return 1
  [ -f $BACKUPDIR/boot.img.gz ] || return 1
  flash_image $BACKUPDIR/boot.img.gz $1
}

post_ota() {
  cd /data/adb
  cp -f $MAGISKBIN/bootctl bootctl
  rm -f $MAGISKBIN/bootctl
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



adb_pm_install() {
  local tmp=/data/local/tmp/temp.apk
  cp -f "$1" $tmp
  chmod 644 $tmp






  pm install -g $tmp || su 2000 -c pm install -g $tmp || su 1000 -c pm install -g $tmp
  local res=$?
  rm -f $tmp
  if [ $res = 0 ]; then
    appops set "$2" REQUEST_INSTALL_PACKAGES allow
  fi
  return $res
}

check_boot_ramdisk() {

  ISAB=true
  [ -z $SLOT ] && ISAB=false


  $ISAB && return 0


  if $LEGACYSAR; then

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

      CRYPTOTYPE=$(getprop ro.crypto.type)
      if [ -z $CRYPTOTYPE ]; then

        if grep ' /data ' /proc/mounts | grep -qv 'dm-'; then
          CRYPTOTYPE="file"
        else

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





mount_partitions() {
  [ "$(getprop ro.build.ab_update)" = "true" ] && SLOT=$(getprop ro.boot.slot_suffix)

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





app_init() {
  mount_partitions >/dev/null
  RAMDISKEXIST=false
  check_boot_ramdisk && RAMDISKEXIST=true
  get_flags >/dev/null
  run_migrations >/dev/null
  check_encryption


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
