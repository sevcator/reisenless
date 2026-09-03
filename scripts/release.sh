#!/usr/bin/env bash
set -e






CONFIG=config.prop
NOTES=notes.md


GCONFIG=app/gradle.properties
BUILDCMD="./build.py -c $CONFIG"
CWD=$(pwd)

grep_prop() {
  local REGEX="s/^$1=//p"
  shift
  local FILES=$@
  sed -n "$REGEX" $FILES | head -n 1
}

ensure_config() {

  sed -i "s:^# version=:version=:g" $CONFIG
  if ! grep -qE '^version=' $CONFIG; then
    echo 'version=' >> $CONFIG
  fi

  sed -i "s:^abiList=:# abiList=:g" $CONFIG
}

disable_version_config() {

  sed -i "s:^version=:# version=:g" $CONFIG
}


set_version() {
  local ver=$1
  local code=$(echo - | awk "{ print $ver * 1000 }")
  local tag="v$ver"

  sed -i "s:versionCode=.*:versionCode=${code}:g" $GCONFIG
  sed -i "s:version=.*:version=${ver}:g" $CONFIG


  git add -u .
  git status
  git commit -m "Release Magisk v$ver" -m "[skip ci]"
}


build() {
  [ -z $1 ] && exit 1
  local ver=$1
  git pull
  set_version $ver
  $BUILDCMD clean
  $BUILDCMD all
  $BUILDCMD -r all
  $BUILDCMD -r app-legacy
}

upload() {
  gh auth status

  local code=$(grep_prop magisk.versionCode $GCONFIG)
  local ver=$(echo - | awk "{ print $code / 1000 }")
  local tag="v$ver"
  local title="Magisk v$ver"

  local out=$(grep_prop outdir $CONFIG)
  if [ -z $out ]; then
    out=out
  fi

  git tag $tag
  git push origin master
  git push --tags

  # Publish release
  local release_apk="Magisk-v${ver}.apk"
  local legacy_apk="Magisk-v${ver}-legacy.apk"
  cp $out/app-release.apk $release_apk
  cp $out/apk-legacy-release.apk $legacy_apk
  gh release create --verify-tag $tag -d -t "$title" -F $NOTES $release_apk $legacy_apk $out/app-debug.apk

  rm -f $release_apk $legacy_apk
}


if command -v gsed >/dev/null; then
  function sed() { gsed "$@"; }
  export -f sed
fi

trap disable_version_config EXIT
ensure_config
case $1 in
  build ) build $2 ;;
  upload ) upload ;;
  * ) exit 1 ;;
esac
