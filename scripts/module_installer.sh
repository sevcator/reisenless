#!/sbin/sh

: SECURE_DIR_STUB
: BUILD_IDENTITY_STUB








umask 022


ui_print() { echo "$1"; }

require_new_magisk() {
  ui_print "*******************************"
  ui_print " please install a compatible manager v20.4+! "
  ui_print "*******************************"
  exit 1
}





OUTFD=$2
ZIPFILE=$3

mount /data 2>/dev/null

UTIL_FUNCS="${SECURE_DIR}/${DATA_DIR}/util_functions.sh"
[ -f "$UTIL_FUNCS" ] || UTIL_FUNCS="/data/adb/magisk/util_functions.sh"
[ -f "$UTIL_FUNCS" ] || require_new_magisk
. "$UTIL_FUNCS"
[ $MAGISK_VER_CODE -lt 20400 ] && require_new_magisk

install_module
exit 0
