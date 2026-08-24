if [ -z $ANDROID_HOME ]; then
  export ANDROID_HOME=$ANDROID_SDK_ROOT
fi


export ANDROID_USER_HOME="$HOME/.android"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_EMULATOR_HOME/avd"
export PATH="$PATH:$ANDROID_HOME/platform-tools"

emu="$ANDROID_HOME/emulator/emulator"
sdk="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
avd="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

boot_timeout=100

core_count=$(nproc)
if [ $core_count -gt 8 ]; then
  core_count=8
fi

print_title() {
  echo -e "\n\033[44;39m${1}\033[0m\n"
}

print_error() {
  echo -e "\n\033[41;39m${1}\033[0m\n" >&2
}



am_instrument() {
  set +x
  local out=$(adb shell am instrument -w --user 0 -e class "$1" "$2")
  echo "$out"
  if grep -q 'OK (' <<< "$out"; then
    set -x
    return 0
  else
    set -x
    return 1
  fi
}


wait_for_pm() {
  sleep 5
  adb shell pm uninstall $1 || true
}

run_setup() {
  local variant=$1
  adb shell 'PATH=$PATH:/debug_ramdisk ms -v'


  adb install -r -g out/app-${variant}.apk


  adb install -r -g out/test.apk

  local app='com.topjohnwu.magisk.test/com.topjohnwu.magisk.test.AppTestRunner'


  am_instrument '.Environment#setupEnvironment' $app
}

run_tests() {
  local pkg='com.topjohnwu.magisk.test'
  local self="$pkg/$pkg.TestRunner"
  local app="$pkg/$pkg.AppTestRunner"
  local stub="repackaged.$pkg/$pkg.AppTestRunner"


  am_instrument '.MagiskAppTest,.AdditionalTest' $app


  am_instrument '.AppMigrationTest#testAppHide' $self


  am_instrument '.MagiskAppTest' $stub


  am_instrument '.AppMigrationTest#testAppRestore' $self


  am_instrument '.MagiskAppTest' $app
}
