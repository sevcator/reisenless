#!/sbin/sh

: PACKAGED_LIBS_STUB
: "${TMPDIR:=/dev/tmp}"
: "${PACKAGED_BUSYBOX_LIB:=busybox}"
if [ "$(getprop sys.boot_completed 2>/dev/null)" = 1 ] && [ -d /data/local/tmp ]; then
  TMPDIR=/data/local/tmp/${TMPDIR##*/}
fi
rm -rf $TMPDIR
mkdir -p $TMPDIR 2>/dev/null

export BBBIN=$TMPDIR/busybox
for arch in "x86_64" "x86" "arm64-v8a" "armeabi-v7a"; do
  unzip -o "$3" "lib/$arch/lib$PACKAGED_BUSYBOX_LIB.so" -d $TMPDIR >&2
  libpath="$TMPDIR/lib/$arch/lib$PACKAGED_BUSYBOX_LIB.so"
  [ -f "$libpath" ] || continue
  chmod 755 "$libpath"
  mv -f "$libpath" "$BBBIN"
  if [ -x "$BBBIN" ] && "$BBBIN" true >/dev/null 2>&1; then
    break
  fi
  rm -f "$BBBIN"
done
[ -x "$BBBIN" ] || exit 1
$BBBIN rm -rf $TMPDIR/lib

export INSTALLER=$TMPDIR/install
$BBBIN mkdir -p $INSTALLER
# Extract a fresh copy of every packaged library into the installer tree. The
# bootstrap copy above is deliberately named "busybox" so its applet dispatch
# works before generated build variables are sourced; the installer still needs
# the original packaged filename so it can promote it to $BUSYBOX_NAME.
$BBBIN unzip -o "$3" "assets/*" "lib/*" "META-INF/com/google/*" -d $INSTALLER >&2
export ASH_STANDALONE=1
if echo "$3" | $BBBIN grep -q "uninstall"; then
  exec $BBBIN sh "$INSTALLER/assets/uninstaller.sh" "$@"
else
  exec $BBBIN sh "$INSTALLER/META-INF/com/google/android/updater-script" "$@"
fi
