#!/sbin/sh

: SECURE_DIR_STUB
: BUILD_IDENTITY_STUB










trampoline() {
  mount /data 2>/dev/null
  if [ -f $MAGISKBIN/addon.d.sh ]; then
    exec sh $MAGISKBIN/addon.d.sh "$@"
    exit $?
  elif [ "$1" = post-restore ]; then
    BOOTMODE=false
    ps | grep zygote | grep -v grep >/dev/null && BOOTMODE=true
    $BOOTMODE || ps -A 2>/dev/null | grep zygote | grep -v grep >/dev/null && BOOTMODE=true

    if ! $BOOTMODE; then

      OUTFD=$(ps | grep -v 'grep' | grep -oE 'update(.*) 3 [0-9]+' | cut -d" " -f3)
      [ -z $OUTFD ] && OUTFD=$(ps -Af | grep -v 'grep' | grep -oE 'update(.*) 3 [0-9]+' | cut -d" " -f3)

      [ -z $OUTFD ] && OUTFD=$(ps | grep -v 'grep' | grep -oE 'status_fd=[0-9]+' | cut -d= -f2)
      [ -z $OUTFD ] && OUTFD=$(ps -Af | grep -v 'grep' | grep -oE 'status_fd=[0-9]+' | cut -d= -f2)
    fi
    ui_print() {
      if $BOOTMODE; then
        echo "$1"
      else
        echo -e "ui_print $1\nui_print" >> /proc/self/fd/$OUTFD
      fi
    }

    ui_print "***********************"
    ui_print " reisenless addon.d failed"
    ui_print "***********************"
    ui_print "! cannot find reisenless binaries - was data wiped or not decrypted?"
    ui_print "! reflash ota from decrypted recovery or reflash reisenless"
  fi
  exit 1
}


MAGISKBIN=${SECURE_DIR}/${DATA_DIR}
[ "$0" = $MAGISKBIN/addon.d.sh ] || trampoline "$@"

V1_FUNCS=/tmp/backuptool.functions
V2_FUNCS=/postinstall/tmp/backuptool.functions

if [ -f $V1_FUNCS ]; then
  . $V1_FUNCS
  backuptool_ab=false
elif [ -f $V2_FUNCS ]; then
  . $V2_FUNCS
else
  return 1
fi

initialize() {

  . $MAGISKBIN/util_functions.sh

  if $BOOTMODE; then

    ui_print() { echo "$1"; }
  fi
  OUTFD=
  setup_flashable
}

main() {
  if ! $backuptool_ab; then

    if [ -f config.orig ]; then
      PREINITDEVICE=$(grep_prop PREINITDEVICE config.orig)
      rm config.orig
    fi


    sleep 5
  fi


  mkdir -p $TMPDIR
  cd $TMPDIR

  if echo $MAGISK_VER | grep -q '\.'; then
    PRETTY_VER=$MAGISK_VER
  else
    PRETTY_VER="$MAGISK_VER($MAGISK_VER_CODE)"
  fi
  print_title "reisenless $PRETTY_VER addon.d"

  mount_partitions
  check_data
  get_flags

  if $backuptool_ab; then

    if [ ! -z $SLOT ]; then
      case $SLOT in
        _a) SLOT=_b;;
        _b) SLOT=_a;;
      esac
    fi
  fi

  find_boot_image
  [ -z $BOOTIMAGE ] && abort "! unable to detect target image"
  ui_print "- target image: $BOOTIMAGE"

  api_level_arch_detect
  ui_print "- device platform: $ABI"

  remove_system_su
  install_magisk


  cd /
  $BOOTMODE || recovery_cleanup
  rm -rf $TMPDIR

  ui_print "- done"
  exit 0
}

case "$1" in
  backup)

  ;;
  restore)

  ;;
  pre-backup)

    if ! $backuptool_ab; then
      initialize

      ui_print() { return; }
      get_flags
      find_boot_image
      $MAGISKBIN/mboot unpack "$BOOTIMAGE"
      $MAGISKBIN/mboot cpio ramdisk.cpio "extract .backup/$BACKUP_CONFIG config.orig" 2>/dev/null || \
        $MAGISKBIN/mboot cpio ramdisk.cpio "extract .backup/.magisk config.orig"
      $MAGISKBIN/mboot cleanup
    fi
  ;;
  post-backup)

  ;;
  pre-restore)

  ;;
  post-restore)
    initialize
    if $backuptool_ab; then
      su=sh
      $BOOTMODE && su=su
      exec $su -c "sh $0 addond-v2"
    else

      (main) &
    fi
  ;;
  addond-v2)
    initialize
    main
  ;;
esac
