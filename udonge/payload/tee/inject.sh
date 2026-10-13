#!/system/bin/sh

# The bundled service also attempts Soter injection. Keep all OEM processes intact.
[ "$#" = 3 ] && [ "$2" = libTEESimulator.so ] && [ "$3" = entry ] || exit 1
case "$1" in ''|*[!0-9]*) exit 1 ;; esac
run="$(cd "$(dirname "$0")" && pwd)" || exit 1
[ "$1" = "$(cat "$run/.keystore-pid" 2>/dev/null)" ] || exit 1
[ "$(awk '{ if (sub(/^.*\) /, "")) print $20; else print $22 }' "/proc/$1/stat" 2>/dev/null)" = "$(cat "$run/.keystore-start" 2>/dev/null)" ] || exit 1
case "$(readlink "/proc/$1/exe" 2>/dev/null)" in /system/bin/keystore|/system/bin/keystore2) ;; *) exit 1 ;; esac
exec "$run/inject.bin" "$@"
