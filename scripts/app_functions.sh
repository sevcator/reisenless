: SECURE_DIR_STUB
: BUILD_IDENTITY_STUB

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
    if [ -d "$item" ] && [ ! -L "$item" ]; then
      [ ! -L "$target" ] || return 1
      merge_missing_tree "$item" "$target" || return 1
    elif [ ! -e "$target" ] && [ ! -L "$target" ]; then
      cp -af "$item" "$target" || return 1
    fi
  done
  return 0
}

migration_hash_tree() {
  local source="$1" ephemeral="${2:-false}" relative name
  [ -e "$source" ] || return 0
  if [ -f "$source" ]; then
    sha256sum "$source" 2>/dev/null
    return $?
  fi
  (
    cd "$source" || exit 1
    find . -type f -print 2>/dev/null | sort | while IFS= read -r relative; do
      if $ephemeral; then
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
      fi
      sha256sum "$relative" 2>/dev/null || exit 1
    done
  )
}

copy_durable_tree() {
  local source="$1" destination="$2" ephemeral="${3:-false}" item name
  [ -d "$source" ] || return 0
  mkdir -p "$destination" || return 1
  for item in "$source"/* "$source"/.[!.]* "$source"/..?*; do
    [ -e "$item" ] || [ -L "$item" ] || continue
    name=${item##*/}
    if $ephemeral; then
      case "$name" in
        runtime|runtime.old|runtime.new|tee-runtime|tee-runtime.old|tee-runtime.new|\
        *.pid|.pid|.pid-start|.pid-boot|unloaded|pending-reboot|\
        .keybox-refresh|.keybox-checked|tee-unavailable|rom_keywords.conf|.rom-catalog-v2)
          continue
          ;;
      esac
    fi
    if [ -d "$item" ] && [ ! -L "$item" ]; then
      copy_durable_tree "$item" "$destination/$name" "$ephemeral" || return 1
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

rewrite_migration_paths() (
  local root="$1" source="$2" old_runtime="$3" destination="${4:-$SECURE_DIR}"
  local pattern replacement runtime file target temp
  [ -d "$root" ] || return 0
  pattern=$(printf '%s\n' "$source" | sed 's/[][\\.^$*|]/\\&/g') || return 1
  replacement=$(printf '%s\n' "$destination" | sed 's/[&|\\]/\\&/g') || return 1
  runtime=$(printf '%s\n' "$old_runtime" | sed 's/[][\\.^$*|]/\\&/g') || return 1
  if [ -n "${4:-}" ]; then
    set -- -e "s|$pattern\([/[:space:]\"';:)]\)|$replacement\1|g" -e "s|$pattern$|$replacement|g"
  else
    set -- -e "s|$pattern/$runtime/|$replacement/$UDONGE_DIR/|g" -e "s|$pattern/|$replacement/|g"
  fi
  find "$root" -type f | while IFS= read -r file; do
    grep -Fq "$source" "$file" || continue
    od -An -v -N 8192 -tx1 "$file" | grep -q ' 00' && continue
    temp="$file.migration-new.$$"
    cp -af "$file" "$temp" || exit 1
    if ! sed "$@" "$file" > "$temp" || ! mv -f "$temp" "$file"; then
      rm -f "$temp"
      exit 1
    fi
  done || return 1
  find "$root" -type l | while IFS= read -r file; do
    target=$(readlink "$file") || exit 1
    case "$target" in
      "$source") target="$destination";;
      "$source/$old_runtime"/*) target="$destination/$UDONGE_DIR/${target#"$source/$old_runtime"/}";;
      "$source"/*) target="$destination/${target#"$source"/}";;
      *) continue;;
    esac
    ln -snf "$target" "$file" || exit 1
  done
)

rewrite_installed_module_paths() {
  local source="$1" dir legacy_dir
  for dir in modules modules_update post-fs-data.d service.d; do
    for legacy_dir in modules modules_update; do
      rewrite_migration_paths "$SECURE_DIR/$dir" "$source/$legacy_dir" '' "$SECURE_DIR/$legacy_dir" || return 1
    done
  done
}

is_legacy_payload_dir() {
  local source="$1" directory="$2"
  [ -d "$directory" ] && [ ! -L "$directory" ] || return 1
  [ -f "$directory/util_functions.sh" ] || return 1
  grep -q '^MAGISK_VER_CODE=[0-9]' "$directory/util_functions.sh" || return 1
  if [ "$source" = /data/adb ]; then
    case "${directory##*/}" in magisk|ms) return 0;; *) return 1;; esac
  fi
  grep -xqF "SECURE_DIR='$source'" "$directory/util_functions.sh"
}

legacy_stage_names() {
  local source="$1" directory name
  for directory in "$source"/* "$source"/.[!.]*; do
    is_legacy_payload_dir "$source" "$directory" || continue
    name=$(sed -n "s/^STAGE_SCRIPT='\([^']*\)'$/\1/p" "$directory/util_functions.sh" | head -n 1)
    case "$name" in ''|.|..|*[!A-Za-z0-9._-]*) continue;; esac
    printf '%s\n' "$name"
  done
}

remove_migrated_stage_hooks() {
  local root="$1" source="$2" names name
  names=$(legacy_stage_names "$source") || return 1
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    rm -f "$root/post-fs-data.d/$name" "$root/service.d/$name" || return 1
  done <<EOF
$names
EOF
}

transactional_migrate_layout() {
  local source="$1" source_db="$2" source_udonge="$3" marker_name="$4"
  local marker="$SECURE_DIR/$marker_name" stage="$SECURE_DIR/.migration-stage.$$"
  local manifest="$stage/source.sha256" existing="$SECURE_DIR/.migration-source.tmp.$$"
  local dir

  [ "$source" != "$SECURE_DIR" ] || return 0
  [ -d "$source" ] || return 0
  [ ! -L "$source" ] || return 1
  [ -n "$source_db" ] || return 1
  [ -n "$source_udonge" ] || return 1
  for dir in modules modules_update post-fs-data.d service.d "$source_udonge"; do
    [ ! -L "$source/$dir" ] || return 1
  done
  [ ! -L "$source/$source_udonge/state" ] && [ ! -L "$source/$source_udonge/tee-state" ] || return 1
  mkdir -p "$SECURE_DIR" || return 1
  remove_migrated_stage_hooks "$SECURE_DIR" "$source" || return 1

  rm -rf "$stage"
  mkdir -p "$stage" || return 1
  (
    migration_hash_tree "$source/$source_db" || exit 1
    for dir in modules modules_update post-fs-data.d service.d; do
      migration_hash_tree "$source/$dir" || exit 1
    done
    migration_hash_tree "$source/$source_udonge/state" true || exit 1
    migration_hash_tree "$source/$source_udonge/tee-state" true || exit 1
  ) > "$manifest" || { rm -rf "$stage"; return 1; }

  if [ -f "$marker" ]; then
    sed '1d' "$marker" > "$existing" 2>/dev/null || true
    if cmp -s "$manifest" "$existing"; then
      rm -f "$existing"
      rm -rf "$stage"
      rewrite_installed_module_paths "$source" || return 1
      return 0
    fi
    rm -f "$existing"
  fi

  if [ -f "$source/$source_db" ]; then
    cp -af "$source/$source_db" "$stage/$DB_NAME" || { rm -rf "$stage"; return 1; }
    if [ -f "$source/$source_db-wal" ]; then
      cp -af "$source/$source_db-wal" "$stage/$DB_NAME-wal" || { rm -rf "$stage"; return 1; }
    fi
    validate_migration_db "$stage/$DB_NAME" || { rm -rf "$stage"; return 1; }
  fi
  for dir in modules modules_update post-fs-data.d service.d; do
    [ -d "$source/$dir" ] || continue
    copy_durable_tree "$source/$dir" "$stage/$dir" || { rm -rf "$stage"; return 1; }
  done
  validate_migration_modules "$stage/modules" || { rm -rf "$stage"; return 1; }
  validate_migration_modules "$stage/modules_update" || { rm -rf "$stage"; return 1; }
  copy_durable_tree "$source/$source_udonge/state" "$stage/$UDONGE_DIR/state" true || {
    rm -rf "$stage"; return 1;
  }
  copy_durable_tree "$source/$source_udonge/tee-state" "$stage/$UDONGE_DIR/tee-state" true || {
    rm -rf "$stage"; return 1;
  }
  for dir in modules modules_update post-fs-data.d service.d "$UDONGE_DIR"; do
    rewrite_migration_paths "$stage/$dir" "$source" "$source_udonge" || { rm -rf "$stage"; return 1; }
  done
  remove_migrated_stage_hooks "$stage" "$source" || { rm -rf "$stage"; return 1; }

  if [ -f "$stage/$DB_NAME" ] && [ ! -f "$SECURE_DIR/$DB_NAME" ]; then
    if [ -f "$stage/$DB_NAME-wal" ]; then
      mv "$stage/$DB_NAME-wal" "$SECURE_DIR/$DB_NAME-wal" || { rm -rf "$stage"; return 1; }
    fi
    mv "$stage/$DB_NAME" "$SECURE_DIR/$DB_NAME" || { rm -rf "$stage"; return 1; }
  fi
  for dir in modules modules_update post-fs-data.d service.d "$UDONGE_DIR"; do
    [ -d "$stage/$dir" ] || continue
    merge_missing_tree "$stage/$dir" "$SECURE_DIR/$dir" || { rm -rf "$stage"; return 1; }
  done
  rewrite_installed_module_paths "$source" || { rm -rf "$stage"; return 1; }
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
  for file in "$MAIN_BIN_NAME" "$BUSYBOX_NAME" mboot minit util_functions.sh app_functions.sh boot_patch.sh "$UDONGE_ARCHIVE"; do
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
  if [ -z "$2" ]; then
    cd "$1" || return 1
  else
    cp -af "$1/." "$2" || return 1
    cd "$2" || return 1
  fi
  for file in * .[!.]* ..?*; do
    if [ -L "$file" ]; then
      local full=$(readlink -f "$file")
      [ -n "$full" ] || return 1
      rm "$file" || return 1
      cp -af "$full" "$file" || return 1
    fi
  done
  chmod -R 755 . || return 1
  cd / || return 1
}

fix_env() (
  local source="$1" next="$MAGISKBIN.new" old="$MAGISKBIN.old"
  [ -d "$source" ] && [ "$source" != "$MAGISKBIN" ] || return 1
  trap 'status=$?; if [ ! -d "$MAGISKBIN" ] && [ -d "$old" ]; then mv "$old" "$MAGISKBIN" || true; fi; rm -rf "$next"; exit "$status"' EXIT
  if [ ! -d "$MAGISKBIN" ] && [ -d "$old" ]; then
    mv "$old" "$MAGISKBIN" || return 1
  fi
  rm -rf "$next" || return 1
  mkdir -p "$next" || return 1
  chmod 700 "$SECURE_DIR" || return 1
  cp_readlink "$source" "$next" || return 1
  chown -R 0:0 "$next" || return 1
  preserve_upgrade_boot_files "$old" "$SECURE_DIR/.upgrade-preserved/boot/current" || return 1
  rm -rf "$old" || return 1
  [ ! -d "$MAGISKBIN" ] || mv "$MAGISKBIN" "$old" || return 1
  mv "$next" "$MAGISKBIN" || return 1
  preserve_upgrade_boot_files "$old" "$SECURE_DIR/.upgrade-preserved/boot/current" || return 1
  rm -rf "$old" || return 1
  rm -rf "$source"
)

migrate_legacy_layout() {
  local legacy=/data/a''db database=ms.db
  [ -f "$legacy/$database" ] || database=magisk.db
  transactional_migrate_layout "$legacy" "$database" udonge .migration-canonical.complete || return 1
  rm -f "$SECURE_DIR/post-fs-data.d/udonge.sh" "$SECURE_DIR/service.d/udonge.sh"
  rm -f "$SECURE_DIR/post-fs-data.d/$STAGE_SCRIPT" "$SECURE_DIR/service.d/$STAGE_SCRIPT"
  return 0
}

preserve_upgrade_file() {
  local source="$1" target="$2" backup="$3"
  [ -e "$source" ] || [ -L "$source" ] || return 0
  if [ -L "$source" ]; then
    [ -L "$target" ] && [ "$(readlink "$source")" = "$(readlink "$target")" ] && return 0
    [ ! -e "$backup" ] && [ ! -L "$backup" ] || {
      [ -L "$backup" ] && [ "$(readlink "$source")" = "$(readlink "$backup")" ]
      return $?
    }
  else
    [ -f "$target" ] && [ ! -L "$target" ] && cmp -s "$source" "$target" && return 0
    [ ! -e "$backup" ] && [ ! -L "$backup" ] || {
      [ -f "$backup" ] && [ ! -L "$backup" ] && cmp -s "$source" "$backup"
      return $?
    }
  fi
  [ ! -L "${backup%/*}" ] || return 1
  mkdir -p "${backup%/*}" || return 1
  cp -af "$source" "$backup" || return 1
  if [ -L "$source" ]; then
    [ -L "$backup" ] && [ "$(readlink "$source")" = "$(readlink "$backup")" ]
  else
    cmp -s "$source" "$backup"
  fi
}

preserve_upgrade_tree() {
  local source="$1" target="$2" backup="$3" ephemeral="${4:-false}" item name
  [ -e "$source" ] || [ -L "$source" ] || return 0
  [ -d "$source" ] && [ ! -L "$source" ] || {
    preserve_upgrade_file "$source" "$target" "$backup"
    return $?
  }
  [ ! -L "$backup" ] || return 1
  for item in "$source"/* "$source"/.[!.]* "$source"/..?*; do
    [ -e "$item" ] || [ -L "$item" ] || continue
    name=${item##*/}
    if $ephemeral; then
      case "$name" in
        runtime|runtime.old|runtime.new|tee-runtime|tee-runtime.old|tee-runtime.new|\
        *.pid|.pid|.pid-start|.pid-boot|unloaded|pending-reboot|\
        .keybox-refresh|.keybox-checked|tee-unavailable|rom_keywords.conf|.rom-catalog-v2)
          continue;;
      esac
    fi
    case "$source/$name" in
      */post-fs-data.d/udonge.sh|*/service.d/udonge.sh|\
      */post-fs-data.d/"$STAGE_SCRIPT"|*/service.d/"$STAGE_SCRIPT") continue;;
    esac
    case "$source" in
      */post-fs-data.d|*/service.d)
        legacy_stage_names "${source%/*}" | grep -xqF "$name" && continue;;
    esac
    preserve_upgrade_tree "$item" "$target/$name" "$backup/$name" "$ephemeral" || return 1
  done
}

cleanup_migrated_layout() {
  local source="$1" database="$2" runtime="$3" marker_name="$4" label="$5"
  local marker="$SECURE_DIR/$marker_name" backup="$SECURE_DIR/.upgrade-preserved/$label"
  local dir item
  [ "$source" != "$SECURE_DIR" ] || return 0
  [ -d "$source" ] || return 0
  case "$source" in /data/adb|"$LEGACY_SECURE_DIR") ;; *) return 1;; esac
  [ ! -L "$source" ] && [ "$(readlink -f "$source")" = "$source" ] || return 1
  awk -v root="$source" '$2 == root || index($2, root "/") == 1 { mounted=1 }
    END { exit mounted ? 1 : 0 }' /proc/mounts || return 1
  [ "${SECURE_DIR#"$source"/}" = "$SECURE_DIR" ] || return 1
  [ "${source#"$SECURE_DIR"/}" = "$source" ] || return 1
  [ -f "$marker" ] && [ "$(head -n 1 "$marker")" = "source=$source" ] || return 1
  [ ! -L "$SECURE_DIR/.upgrade-preserved" ] && [ ! -L "$backup" ] || return 1
  for dir in modules modules_update post-fs-data.d service.d; do
    preserve_upgrade_tree "$source/$dir" "$SECURE_DIR/$dir" "$backup/$dir" || return 1
  done
  preserve_upgrade_tree "$source/$runtime" "$SECURE_DIR/$UDONGE_DIR" "$backup/$runtime" true || return 1
  preserve_upgrade_file "$source/$database" "$SECURE_DIR/$DB_NAME" "$backup/$database" || return 1
  preserve_upgrade_file "$source/$database-wal" "$SECURE_DIR/$DB_NAME-wal" "$backup/$database-wal" || return 1
  if [ "$source" = /data/adb ]; then
    for item in ms.db magisk.db; do
      [ "$item" != "$database" ] || continue
      preserve_upgrade_file "$source/$item" "$SECURE_DIR/$DB_NAME" "$backup/$item" || return 1
      preserve_upgrade_file "$source/$item-wal" "$SECURE_DIR/$DB_NAME-wal" "$backup/$item-wal" || return 1
    done
  fi
  for dir in modules modules_update post-fs-data.d service.d "$runtime"; do
    rm -rf "$source/$dir" || return 1
  done
  rm -f "$source/$database" "$source/$database-wal" "$source/$database-shm" || return 1
  if [ "$source" = /data/adb ]; then
    rm -f "$source/ms.db" "$source/ms.db-wal" "$source/ms.db-shm" \
      "$source/magisk.db" "$source/magisk.db-wal" "$source/magisk.db-shm" || return 1
  fi
  for item in "$source"/* "$source"/.[!.]*; do
    is_legacy_payload_dir "$source" "$item" || continue
    preserve_upgrade_boot_files "$item" "$backup/boot" || return 1
    rm -rf "$item" || return 1
  done
  if [ "$source" = "$LEGACY_SECURE_DIR" ] && [ "$source" != /data/adb ]; then
    preserve_upgrade_tree "$source" "$SECURE_DIR" "$backup/other" true || return 1
    rm -rf "$source" || return 1
  else
    rmdir "$source" 2>/dev/null || true
  fi
  rm -f "$marker"
}

preserve_upgrade_boot_files() {
  local source="$1" backup="$2" item checksum target parent="$2"
  case "$backup" in "$SECURE_DIR"/.upgrade-preserved/*) ;; *) return 1;; esac
  while [ "$parent" != "$SECURE_DIR" ]; do
    [ ! -L "$parent" ] || return 1
    parent=${parent%/*}
  done
  [ ! -L "$SECURE_DIR" ] || return 1
  for item in "$source"/stock_boot* "$source"/stock_dtb* "$source"/stock_dtbo*; do
    [ -e "$item" ] || [ -L "$item" ] || continue
    if [ -f "$item" ]; then
      checksum=$(sha256sum "$item") || return 1
      checksum=${checksum%% *}
      [ ${#checksum} -eq 64 ] || return 1
      case "$checksum" in *[!0-9a-f]*) return 1;; esac
      target="$backup/$checksum/${item##*/}"
      [ ! -L "$backup" ] && [ ! -L "$backup/$checksum" ] && [ ! -L "$target" ] || return 1
      mkdir -p "$backup/$checksum" || return 1
      [ -f "$target" ] || cp -L "$item" "$target" || return 1
      cmp -s "$item" "$target" || return 1
      continue
    fi
    preserve_upgrade_tree "$item" "$backup/${item##*/}" "$backup/${item##*/}" || return 1
  done
}

cleanup_obsolete_managers() {
  local current_package="$1" current_code="$2" packages line apk package version failed=0
  [ -n "$current_package" ] || return 1
  case "$current_package" in *[!A-Za-z0-9_.]*) return 1;; esac
  case "$current_code" in ''|*[!0-9]*) return 1;; esac
  packages=$(pm list packages -f) || return 1
  while IFS= read -r line; do
    case "$line" in package:*=*) ;; *) continue;; esac
    package=${line##*=}
    [ "$package" != "$current_package" ] || continue
    case "$package" in ''|*[!A-Za-z0-9_.]*) continue;; esac
    apk=${line#package:}
    apk=${apk%=*}
    [ -f "$apk" ] || continue
    unzip -l "$apk" assets/util_functions.sh assets/boot_patch.sh 2>/dev/null |
      awk '$4 == "assets/util_functions.sh" || $4 == "assets/boot_patch.sh" {
        if ($1 < 1 || $1 > 262144) exit 1
        count++
      } END { if (count != 2) exit 1 }' || continue
    unzip -p "$apk" assets/util_functions.sh 2>/dev/null |
      grep -q '^MAGISK_VER_CODE=[0-9][0-9]*$' || continue
    unzip -p "$apk" assets/boot_patch.sh 2>/dev/null |
      grep -qF 'ramdisk.cpio' || continue
    version=$(dumpsys package "$package" | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1)
    case "$version" in ''|*[!0-9]*) failed=1; continue;; esac
    [ "$version" -le "$current_code" ] || continue
    pm uninstall "$package" 2>/dev/null | grep -xq 'Success' || failed=1
  done <<EOF
$packages
EOF
  return "$failed"
}

record_upgrade_cleanup() {
  local version="$1" current_code="$2" current_boot="$3" current_package="$4"
  printf '%s\n%s\n%s\n' "$version" "$current_boot" "$current_code" > "$SECURE_DIR/.upgrade-cleanup.complete.new" || return 1
  mv "$SECURE_DIR/.upgrade-cleanup.complete.new" "$SECURE_DIR/.upgrade-cleanup.complete" || return 1
  chmod 600 "$SECURE_DIR/.upgrade-cleanup.complete" || return 1
  [ -n "$current_package" ] || return 0
  [ "$(cat "$SECURE_DIR/.upgrade-managers.complete" 2>/dev/null)" != "$version:$current_code" ] || return 0
  cleanup_obsolete_managers "$current_package" "$current_code" || return 1
  printf '%s:%s\n' "$version" "$current_code" > "$SECURE_DIR/.upgrade-managers.complete.new" || return 1
  mv "$SECURE_DIR/.upgrade-managers.complete.new" "$SECURE_DIR/.upgrade-managers.complete" || return 1
  chmod 600 "$SECURE_DIR/.upgrade-managers.complete"
}

cleanup_upgrade() {
  local version="$1" current_package="$2" current_code="$3" current_boot old backup suffix database=ms.db
  [ -n "$version" ] && [ "$version" = "$MAGISK_VER" ] || return 1
  [ "$(getprop sys.boot_completed)" = 1 ] || return 1
  current_boot=$(cat /proc/sys/kernel/random/boot_id) || return 1
  [ -n "$current_boot" ] || return 1
  [ -f "$MAGISKBIN/$MAIN_BIN_NAME" ] && [ -x "$MAGISKBIN/$BUSYBOX_NAME" ] || return 1
  [ ! -L "$SECURE_DIR" ] && [ "$(readlink -f "$SECURE_DIR")" = "$SECURE_DIR" ] || return 1
  if [ "$(sed -n '1p' "$SECURE_DIR/.upgrade-cleanup.complete" 2>/dev/null)" = "$version" ] &&
      [ "$(sed -n '3p' "$SECURE_DIR/.upgrade-cleanup.complete" 2>/dev/null)" = "$current_code" ]; then
    record_upgrade_cleanup "$version" "$current_code" "$current_boot" "$current_package"
    return $?
  fi
  migrate_private_layout && migrate_legacy_layout || return 1
  if [ -n "$LEGACY_SECURE_DIR" ] && [ "$LEGACY_SECURE_DIR" != "$SECURE_DIR" ]; then
    cleanup_migrated_layout "$LEGACY_SECURE_DIR" "$LEGACY_DB_NAME" \
      "$LEGACY_UDONGE_DIR" .migration-private.complete private || return 1
  fi
  if [ "$SECURE_DIR" != /data/adb ]; then
    [ -f /data/adb/ms.db ] || database=magisk.db
    cleanup_migrated_layout /data/adb "$database" udonge .migration-canonical.complete canonical || return 1
  fi
  for old in /data/magisk /data/ms /cache/data_adb/magisk /cache/data_adb/ms; do
    [ -d "$old" ] && [ ! -L "$old" ] || continue
    [ -f "$old/util_functions.sh" ] || continue
    grep -q '^MAGISK_VER_CODE=[0-9]' "$old/util_functions.sh" || continue
    preserve_upgrade_boot_files "$old" "$SECURE_DIR/.upgrade-preserved/boot/${old##*/}" || return 1
    rm -rf "$old" || return 1
  done
  rmdir /cache/data_adb 2>/dev/null || true
  for old in /data/magisk_backup_* /data/ms_backup_*; do
    [ -d "$old" ] && [ ! -L "$old" ] || continue
    suffix=${old##*_}
    [ ${#suffix} -eq 40 ] || continue
    case "$suffix" in *[!0-9a-f]*) continue;; esac
    backup="$BACKUP_PREFIX$suffix"
    [ "$old" != "$backup" ] || continue
    [ ! -L "$backup" ] || return 1
    merge_missing_tree "$old" "$backup" || return 1
    preserve_upgrade_tree "$old" "$backup" "$SECURE_DIR/.upgrade-preserved/boot/$suffix" || return 1
    rm -rf "$old" || return 1
  done
  rm -f "$SECURE_DIR/.migration-canonical.complete" "$SECURE_DIR/.migration-magisk.complete" \
    "$SECURE_DIR/.migration-private.complete" || return 1
  preserve_upgrade_boot_files "$MAGISKBIN.old" "$SECURE_DIR/.upgrade-preserved/boot/current" || return 1
  preserve_upgrade_boot_files "$MAGISKBIN.new" "$SECURE_DIR/.upgrade-preserved/boot/current" || return 1
  for old in "$SECURE_DIR"/.migration-stage.* "$SECURE_DIR"/.migration-source.tmp.*; do
    suffix=${old##*.}
    case "$suffix" in ''|*[!0-9]*) continue;; esac
    [ ! -d "/proc/$suffix" ] || continue
    rm -rf "$old" || return 1
  done
  rm -rf "$MAGISKBIN.old" "$MAGISKBIN.new" \
    "$SECURE_DIR/$UDONGE_DIR/runtime.old" "$SECURE_DIR/$UDONGE_DIR/runtime.new" || return 1
  record_upgrade_cleanup "$version" "$current_code" "$current_boot" "$current_package"
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
  local image="$1/new-boot.img" image_size status file
  for file in "$MAIN_BIN_NAME" "$BUSYBOX_NAME" mboot minit util_functions.sh app_functions.sh boot_patch.sh "$UDONGE_ARCHIVE"; do
    if [ ! -s "$1/$file" ]; then
      echo "! missing installation payload: $file"
      return 3
    fi
  done
  image_size=$(stat -c '%s' "$image") || return 3
  [ "$image_size" -gt 0 ] || return 3
  echo "- flashing new boot image"
  flash_image "$image" "$2"
  status=$?
  case "$status" in
    0) ;;
    1)
      echo "! insufficient partition size"
      return 1
      ;;
    2)
      echo "! $2 is read only"
      return 2
      ;;
    *)
      echo "! unable to flash $2"
      return 3
      ;;
  esac

  if [ ! -c "$2" ] && ! cmp -s -n "$image_size" "$image" "$2"; then
    echo "! flashed boot image verification failed"
    return 3
  fi

  rm -f "$image" || return 3
  migrate_private_layout || return 3
  migrate_legacy_layout || return 3
  fix_env "$1" || return 3
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
