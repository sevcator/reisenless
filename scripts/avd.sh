#!/usr/bin/env bash

set -e
shopt -s extglob
. scripts/test_common.sh

emu="$ANDROID_HOME/emulator/emulator"
avd="$cmdline_tools/bin/avdmanager"

emu_args_base="-no-window -no-audio -no-boot-anim -gpu software -read-only -no-snapshot -cores $core_count"
log_args="-show-kernel -logcat '' -logcat-output logcat.log"
avd_name='magisk_avd'
emu_args=
emu_pid=

atd_min_api=30
atd_max_api=36
huge_ram_min_api=26

cleanup() {
  rm -f magisk-*.img
  "$avd" delete avd -n $avd_name > /dev/null 2>&1
}

test_error() {
  trap - EXIT
  print_error "! An error occurred"
  pkill -INT -P $$
  wait
  cleanup
  exit 1
}

wait_for_boot() {
  local trace=false
  if [[ $- == *x* ]]; then
    trace=true
    set +x
  fi

  local emu_pid=$1
  local elapsed=0

  while [ $elapsed -lt $boot_timeout ]; do
    if [ -n "$emu_pid" ] && ! kill -0 "$emu_pid" 2>/dev/null; then
      $trace && set -x
      print_error "! Emulator process died unexpectedly"
      return 1
    fi
    local result
    result="$(adb exec-out getprop sys.boot_completed 2>/dev/null || true)"
    if [ "$result" = "1" ]; then
      $trace && set -x
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done

  $trace && set -x
  print_error "! Timed out waiting for emulator to boot (${boot_timeout}s)"
  return 1
}

wait_emu() {
  local which_pid

  timeout $boot_timeout bash -c wait_for_boot &
  local wait_pid=$!


  wait -p which_pid -n $emu_pid $wait_pid
  [ $which_pid -eq $wait_pid ]
}

dump_vars() {
  local val
  for name in $@ emu_args; do
    eval val=\$$name
    echo $name=\"$val\"\;
  done
  # Always export AVD_TEST_LOG
  echo export AVD_TEST_LOG=\"$AVD_TEST_LOG\";
}

pkg_to_path() {
  echo "${1//;/\/}"
}

path_to_pkg() {
  echo "${1////;}"
}

resolve_vars() {
  set +x
  local arg_list="$1"
  local ver=$2
  local type=$3

  local ver=
  local type=
  local arch=
  OPTIND=1

  while getopts ":v:t:a:l" opt; do
    case $opt in
      v )
        ver="$OPTARG"
        ;;
      t )
        type="$OPTARG"
        ;;
      a )
        arch="$OPTARG"
        ;;
      l )
        AVD_TEST_LOG=1
        ;;
      \? )
        echo "Error: Invalid option: -$OPTARG" 1>&2
        exit 1
        ;;
      : )
        # Missing a required argument is fine as we perform validations later
        ;;
    esac
  done

  if [ -z $ver ]; then
    print_error "! No system image version specified"
    exit 1
  fi

  # Determine default arch
  if [ -z "$arch" ]; then
    case $(uname -m) in
      'arm64'|'aarch64')
        arch=arm64-v8a
        ;;
      *)
        arch=x86_64
        ;;
    esac
  fi

  # Determine API level
  local api
  case $ver in
    +([0-9])?(\.+([0-9]))*) api="${ver%%[^0-9.]*}";;
    TiramisuPrivacySandbox) api=33 ;;
    UpsideDownCakePrivacySandbox) api=34 ;;
    VanillaIceCream) api=35 ;;
    Baklava) api=36 ;;
    CinnamonBun) api=37 ;;
    *CANARY) api=10000 ;;
    *)
      print_error "! Unknown system image version '$ver'"
      exit 1
      ;;
  esac

  if [ -z $type ]; then
    if [ $(bc <<< "$api >= $atd_min_api && $api <= $atd_max_api") = 1 ]; then
      type='aosp_atd'
    elif [ $(bc <<< "$api > $atd_max_api") = 1 ]; then
      type='google_apis'
    else
      type='default'
    fi
  fi

  local memory
  if [ $(bc <<< "$api < $huge_ram_min_api") = 1 ]; then
    memory=3072
  else
    memory=8192
  fi

  emu_args="$emu_args_base -memory $memory"

  # System image variable and paths
  local avd_pkg="system-images/android-$ver/$type/$arch"
  local ramdisk="$ANDROID_HOME/$avd_pkg/ramdisk.img"

  dump_vars $arg_list
}

dl_emu() {
  local avd_pkg=$1
  ensure_android_cli
  "$android" sdk install --canary platform-tools emulator "$avd_pkg"
}

setup_emu() {
  local avd_pkg=$1
  local ver=$2
  dl_emu $avd_pkg
  echo no | "$avd" create avd -f -n $avd_name -k "$(path_to_pkg "$1")"
}

test_emu() {
  local variant=$1

  local magisk_args="-ramdisk magisk_${variant}.img -feature -SystemAsRoot"

  if [ -n "$AVD_TEST_LOG" ]; then
    rm -f logcat.log
    "$emu" "@${avd_name}" $emu_args $log_args $magisk_args > kernel.log 2>&1 &
  else
    "$emu" "@${avd_name}" $emu_args $magisk_args > /dev/null 2>&1 &
  fi
  local emu_pid=$!
  wait_for_boot $emu_pid

  emu_pid=$!
  wait_emu

  run_setup $variant

  adb reboot
  wait_for_boot $emu_pid

  run_tests

  kill -INT $emu_pid
  wait $emu_pid
}

test_main() {
  local ver avd_pkg ramdisk
  eval $(resolve_vars "ver avd_pkg ramdisk" $1 $2)

  local emu_port=5682
  emu_args="$emu_args -port $emu_port"
  export ANDROID_SERIAL="emulator-$emu_port"

  setup_emu "$avd_pkg" $ver

  adb kill-server
  adb start-server

  print_title "* Launching $avd_pkg"
  "$emu" "@${avd_name}" $emu_args > /dev/null 2>&1 &
  wait_for_boot $!

  if [ -z "$AVD_TEST_SKIP_DEBUG" ]; then
    ./build.py -v avd_patch "$ramdisk" magisk_debug.img
  fi
  if [ -z "$AVD_TEST_SKIP_RELEASE" ]; then
    ./build.py -vr avd_patch "$ramdisk" magisk_release.img
  fi

  kill -INT $emu_pid
  wait $emu_pid

  if [ -z "$AVD_TEST_SKIP_DEBUG" ]; then
    print_title "* Testing $avd_pkg (debug)"
    test_emu debug
  fi

  if [ -z "$AVD_TEST_SKIP_RELEASE" ]; then
    print_title "* Testing $avd_pkg (release)"
    test_emu release
  fi

  cleanup
}

run_main() {
  local ver avd_pkg
  eval $(resolve_vars "ver avd_pkg" $1 $2)
  setup_emu "$avd_pkg" $ver
  print_title "* Launching $avd_pkg"
  local emu_log=$(mktemp)
  "$emu" "@${avd_name}" $emu_args > "$emu_log" 2>&1 &
  local emu_pid=$!

  if ! wait_for_boot "$emu_pid"; then
    echo "--- Emulator Output ---"
    cat "$emu_log"
    rm -f "$emu_log"
    exit 1
  fi
  rm -f "$emu_log"
  cleanup
}

dl_main() {
  local avd_pkg
  eval $(resolve_vars "avd_pkg" $1 $2)
  print_title "* Downloading $avd_pkg"
  dl_emu "$avd_pkg"
}

live_test_main() {
  local apks=($(print_apks "$@"))
  for apk in "${apks[@]}"; do
    # Cleanup
    adb shell pm uninstall com.topjohnwu.magisk || true
    adb shell pm uninstall repackaged.com.topjohnwu.magisk.test || true
    adb shell /system/xbin/su 0 rm -rf /data/adb/modules

    # "Install" Magisk
    ./build.py -v emulator $apk
    wait_for_boot

    run_setup $apk

    # Trigger Magisk soft reboot
    ./build.py -v emulator $apk
    wait_for_boot

    run_tests
  done
}

case "$1" in
  test )
    shift
    trap test_error EXIT
    set -x
    test_main "$@"
    ;;
  live-test )
    shift
    set -x
    live_test_main "$@"
    ;;
  run )
    shift
    trap cleanup EXIT
    run_main "$@"
    ;;
  dl )
    shift
    dl_main "$@"
    ;;
  * )
    print_error "Unknown argument '$1'"
    exit 1
    ;;
esac

trap - EXIT
