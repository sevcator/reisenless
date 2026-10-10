
import argparse
import os
from pathlib import Path
import subprocess
import re

ROOT = Path(__file__).resolve().parent.parent

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb.exe')
    remote = '/data/local/tmp/reisenless-worker-regression'
    def run(*command):
        return subprocess.run([adb, '-s', args.serial, *command], check=True, text=True, capture_output=True)
    run('shell', f'mkdir -p {remote}/runtime {remote}/state')
    for name in ('worker.sh', 'keybox_heal.sh'):
        run('push', str(ROOT / 'udonge/payload' / name), f'{remote}/runtime/{name}')
    sdk = Path(os.environ['ANDROID_HOME'])
    compiler = sdk / 'ndk/magisk/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android23-clang++.cmd'
    standin = ROOT / 'out/service-standin'
    subprocess.run([str(compiler), '-static-libstdc++',
                    str(ROOT / 'udonge/native/tests/service_standin.cpp'), '-o', str(standin)], check=True)
    run('shell', f'mkdir -p {remote}/tee-runtime')
    run('push', str(standin), f'{remote}/tee-runtime/supervisor')
    reload_function = re.search(r'(?ms)^reload_tee\(\) \{\n.*?^}',
                               (ROOT / 'udonge/payload/keybox_heal.sh').read_text(encoding='utf-8')).group()
    reload_function = reload_function.replace('>/dev/null 2>&1', '>"$root/native-error.log" 2>&1')
    fixture = ROOT / 'out/device-reload-fixture.sh'
    fixture.write_text(f'''#!/system/bin/sh
root={remote}
runtime=$root/runtime
state=$root/state
boot_id=$(cat /proc/sys/kernel/random/boot_id)
. "$runtime/worker.sh"
log() {{ echo "$*"; }}
log_err() {{ echo "$*" >&2; }}
{reload_function}
worker_acquire hunter || exit 1
trap 'worker_release hunter' EXIT
reload_tee || exit 1
touch "$root/reloaded"
sleep 30
''', encoding='utf-8', newline='\n')
    run('push', str(fixture), f'{remote}/runtime/reload-fixture.sh')
    script = fr'''#!/system/bin/sh
set -e
root={remote}
runtime=$root/runtime
state=$root/state
boot_id=$(cat /proc/sys/kernel/random/boot_id)
. "$runtime/worker.sh"
tee_sup=
tee_start=
trap 'set +e; worker_stop hunter; tee_sup=$(cat "$root/tee-runtime/.pid" 2>/dev/null); tee_start=$(cat "$root/tee-runtime/.pid-start" 2>/dev/null); [ -z "$tee_sup" ] || worker_stop_tree "$tee_sup" "$tee_start"; rm -rf "$root"' EXIT
chmod 700 "$runtime/worker.sh" "$runtime/keybox_heal.sh"
touch "$state/keybox.xml" "$state/enabled" "$state/background-updates"
"$runtime/keybox_heal.sh" hunt_daemon >/dev/null 2>&1 &
first=$!
"$runtime/keybox_heal.sh" hunt_daemon >/dev/null 2>&1 &
second=$!
sleep 1
owner=$(cat "$root/.hunter-lock/pid")
start=$(cat "$root/.hunter-lock/start")
worker_is_current "$owner" "$start" "$boot_id"
case "$owner" in "$first"|"$second") ;; *) echo 'wrong owner'; exit 1 ;; esac
other=$first
[ "$owner" != "$first" ] || other=$second
if worker_is_current "$other" "$(worker_process_start "$other")" "$boot_id"; then
    state_of_other=$(awk '{{sub(/^.*\) /, ""); print $1}}' /proc/$other/stat)
    [ "$state_of_other" = Z ] || {{ echo 'duplicate worker'; exit 1; }}
fi
echo 'singleton: passed'
children=$(worker_children "$owner")
rm -f "$state/background-updates"
"$runtime/keybox_heal.sh" stop_daemon
wait "$first" 2>/dev/null || true
wait "$second" 2>/dev/null || true
[ ! -d "$root/.hunter-lock" ]
for child in $children; do
    if [ -f /proc/$child/stat ]; then
        state_of_child=$(awk '{{sub(/^.*\) /, ""); print $1}}' /proc/$child/stat)
        [ "$state_of_child" = Z ] || {{ echo 'orphan child'; exit 1; }}
    fi
done
echo 'disable and owned child shutdown: passed'
chmod 700 "$root/tee-runtime/supervisor" "$runtime/reload-fixture.sh"
touch "$root/tee-runtime/daemon"
chmod 700 "$root/tee-runtime/daemon"
"$runtime/reload-fixture.sh" >"$root/fixture.log" 2>&1 &
fixture=$!
attempt=0
while [ ! -f "$root/reloaded" ] && [ "$attempt" -lt 100 ]; do
    sleep 0.1
    attempt=$((attempt + 1))
done
if [ ! -f "$root/reloaded" ]; then cat "$root/fixture.log" "$root/native-error.log"; exit 1; fi
tee_sup=$(cat "$root/tee-runtime/.pid")
tee_start=$(cat "$root/tee-runtime/.pid-start")
"$runtime/keybox_heal.sh" stop_daemon
wait "$fixture" 2>/dev/null || true
worker_is_current "$tee_sup" "$tee_start" "$boot_id"
echo 'background disable preserves restarted persistent service: passed'
if ! verification_allowed; then
    result=$("$runtime/keybox_heal.sh" test_check)
    [ "$result" = DEFERRED ]
    echo 'sleeping/locked verification deferral: passed'
else
    echo 'sleeping deferral: skipped (device awake and unlocked; host mocks cover it)'
fi
'''
    local = ROOT / 'out/device-worker-test.sh'
    local.write_text(script, encoding='utf-8', newline='\n')
    run('push', str(local), remote + '/test.sh')
    try:
        result = run('shell', f'su -c "sh {remote}/test.sh"')
        print(result.stdout)
    except subprocess.CalledProcessError as error:
        print(error.stdout, error.stderr)
        raise
    finally:
        run('shell', f'su -c "rm -rf {remote}"')

if __name__ == '__main__':
    main()
