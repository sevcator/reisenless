
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parent.parent
BASH = shutil.which('bash')
if os.name == 'nt':
    BASH = 'C:/Program Files/Git/bin/bash.exe'

def shell_functions(path):
    return '\n'.join(m.group() for m in re.finditer(
        r'(?ms)^[A-Za-z_][A-Za-z_0-9]*\(\) \{\n.*?^}', path.read_text(encoding='utf-8')))

class WorkerTests(unittest.TestCase):
    def run_script(self, body, setup=''):
        with tempfile.TemporaryDirectory(prefix='udonge-test-') as directory:
            root = Path(directory).as_posix()
            script = f"root='{root}'\nstate=$root/state\nruntime=$root/runtime\nwork=$root/work\nmkdir -p \"$state\" \"$runtime\"\n"
            helper = REPO / 'udonge/payload/worker.sh'
            if helper.exists():
                script += shell_functions(helper) + '\n'
            script += shell_functions(REPO / 'udonge/payload/keybox_heal.sh') + '\n'
            script += shell_functions(REPO / 'udonge/payload/service.sh') + '\n'
            script += 'log() { :; }\nlog_err() { :; }\n' + setup + '\n' + body
            result = subprocess.run([BASH], input=script, capture_output=True, text=True, encoding='utf-8', timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            return result.stdout.strip()

    def test_disabled_hunter_does_no_work(self):
        result = self.run_script('hunt_daemon', '''
touch "$state/disabled"
hunt_keybox() { echo WORK; return 0; }
sleep() { :; }
''')
        self.assertEqual('', result)

    def test_background_updates_off_does_no_work(self):
        result = self.run_script('hunt_daemon', '''
touch "$state/enabled"
hunt_keybox() { echo WORK; return 0; }
sleep() { :; }
''')
        self.assertEqual('', result)

    def test_sleeping_verification_does_not_launch_or_wake(self):
        result = self.run_script('run_play_integrity_test', '''
dumpsys() { case "$1" in power) echo 'mWakefulness=Asleep';; window) echo 'showing=false';; esac; }
input() { echo INTERACTION; }
wm() { echo UNLOCK; }
am() { echo LAUNCH; }
sleep() { :; }
uiautomator() { :; }
''')
        self.assertEqual('DEFERRED', result)

    def test_locked_awake_device_defers_verification(self):
        result = self.run_script('run_play_integrity_test', '''
dumpsys() { case "$1" in power) echo 'mWakefulness=Awake';; window) echo 'showing=true';; esac; }
''')
        self.assertEqual('DEFERRED', result)

    def test_unknown_keyguard_state_defers_verification(self):
        result = self.run_script('run_play_integrity_test', '''
dumpsys() { case "$1" in power) echo 'mInteractive=true';; window) echo unknown;; esac; }
''')
        self.assertEqual('DEFERRED', result)

    def test_lock_has_single_owner_and_can_be_released(self):
        result = self.run_script('''
worker_acquire hunter || exit 1
if worker_acquire hunter; then echo STOLEN; else echo BUSY; fi
worker_release hunter
worker_acquire hunter && echo ACQUIRED
worker_release hunter
''', '''
boot_id=testing
worker_process_start() { echo stable; }
''')
        self.assertEqual('BUSY\nACQUIRED', result)

    def test_old_boot_lock_is_recovered(self):
        result = self.run_script('''
mkdir "$root/.hunter-lock"
printf '%s' "$$" > "$root/.hunter-lock/pid"
echo stable > "$root/.hunter-lock/start"
echo old-boot > "$root/.hunter-lock/boot"
worker_acquire hunter && echo ACQUIRED
worker_release hunter
''', '''
boot_id=testing
worker_process_start() { echo stable; }
''')
        self.assertEqual('ACQUIRED', result)

    def test_two_stale_lock_contenders_cannot_steal_new_owner_without_flock(self):
        result = self.run_script('''
mkdir "$root/.hunter-lock"
echo 999999 > "$root/.hunter-lock/pid"
echo dead > "$root/.hunter-lock/start"
echo testing > "$root/.hunter-lock/boot"
( worker_acquire hunter && { echo winner >> "$root/winners"; sleep 1; worker_release hunter; } ) &
first=$!
( worker_acquire hunter && { echo winner >> "$root/winners"; sleep 1; worker_release hunter; } ) &
second=$!
wait "$first" || :
wait "$second" || :
wc -l < "$root/winners"
''', '''
boot_id=testing
worker_guard() { "$@"; }
worker_current_pid() { worker_pid=$BASHPID; }
worker_process_start() { echo stable; }
worker_is_current() {
    [ "$1" = 999999 ] || { kill -0 "$1" 2>/dev/null; return $?; }
    if mkdir "$root/first-contender" 2>/dev/null; then
        while [ ! -f "$root/second-contender" ]; do sleep 0.01; done
    else
        touch "$root/second-contender"
        sleep 0.3
    fi
    return 1
}
''')
        self.assertEqual('1', result)

    def test_background_stop_preserves_persistent_tee_subtree(self):
        result = self.run_script('worker_stop_tree 111111 stable 1', '''
worker_process_start() { echo stable; }
worker_children() { [ "$1" != 111111 ] || echo 222222; }
worker_is_persistent_tee() { [ "$1" = 222222 ]; }
kill() { printf '%s %s\\n' "$1" "$2"; }
sleep() { :; }
''')
        self.assertNotIn('222222', result)
        self.assertIn('-TERM 111111', result)

    def test_reused_pid_does_not_match_old_owner(self):
        result = self.run_script('''
if worker_is_current "$$" old-start testing; then echo STOLEN; else echo SAFE; fi
''', '''
boot_id=testing
worker_process_start() { echo new-start; }
''')
        self.assertEqual('SAFE', result)

    def test_unresponsive_owned_worker_is_killed_after_grace_period(self):
        result = self.run_script('worker_stop_tree 999999 stable', '''
worker_process_start() { echo stable; }
kill() { printf '%s\\n' "$1"; }
sleep() { :; }
''')
        self.assertEqual('-STOP\n-TERM\n-CONT\n-KILL', result)

    def test_disappearing_child_does_not_abort_strict_runtime_installation(self):
        result = self.run_script('set -e\nworker_stop_tree 111111 stable\necho COMPLETED', '''
worker_process_start() { [ "$1" = 111111 ] || return 1; echo stable; }
worker_children() { echo 222222; }
kill() { printf '%s %s\\n' "$1" "$2"; }
sleep() { :; }
''')
        self.assertIn('-CONT 111111', result)
        self.assertTrue(result.endswith('COMPLETED'))

    def test_download_304_reuses_previous_body(self):
        result = self.run_script('''
fetch_candidate https://example.test/key.xml "$root/first" || exit 1
fetch_candidate https://example.test/key.xml "$root/second" || exit 1
cat "$root/second"
''', '''
curl() {
    local out
    while [ "$#" -gt 0 ]; do
        case "$1" in -o) shift; out=$1;; esac
        shift
    done
    if [ -f "$root/downloaded" ]; then printf 304; else
        touch "$root/downloaded"; echo complete > "$out"; printf 200
    fi
}
''')
        self.assertEqual('complete', result)

    def test_failed_partial_download_keeps_previous_body(self):
        result = self.run_script('''
fetch_candidate https://example.test/key.xml "$root/first" || exit 1
fetch_candidate https://example.test/key.xml "$root/second" || exit 1
cat "$root/second"
''', '''
curl() {
    local out
    while [ "$#" -gt 0 ]; do
        case "$1" in -o) shift; out=$1;; esac
        shift
    done
    if [ -f "$root/downloaded" ]; then
        echo partial > "$out"; printf 200; return 18
    fi
    touch "$root/downloaded"; echo complete > "$out"; printf 200
}
''')
        self.assertEqual('complete', result)

    def test_verification_configuration_change_invalidates_cache(self):
        result = self.run_script('''
verification_context=
verification_cache_hit fixture || :
remember_verification fixture DEVICE
verification_cache_hit fixture && echo CACHED
echo changed > "$state/pif.conf"
verification_context=
if verification_cache_hit fixture; then echo STALE; else echo INVALIDATED; fi
''', 'dumpsys() { :; }')
        self.assertEqual('CACHED\nINVALIDATED', result)

    def test_device_only_candidate_is_not_verified_twice(self):
        result = self.run_script('''
echo candidate > "$root/candidate.xml"
test_candidate_for_strong "$root/candidate.xml" fixture || :
test_candidate_for_strong "$root/candidate.xml" fixture || :
wc -l < "$root/checks"
''', '''
tee_state=$state
verification_allowed() { return 0; }
extract_serials() { :; }
reload_tee() { :; }
flush_gms_cache() { :; }
sleep() { :; }
run_play_integrity_test() { echo check >> "$root/checks"; printf DEVICE; }
''')
        self.assertEqual('1', result)

    def test_deferred_local_candidate_stays_queued(self):
        result = self.run_script('''
mkdir -p "$state/queue" "$work"
echo candidate > "$state/queue/pending.xml"
process_queue_directory "$state/queue" || :
[ -f "$state/queue/pending.xml" ] && echo QUEUED
[ ! -f "$state/queue/processed/pending.xml" ] && echo NOT_CONSUMED
''', '''
sanitize_keybox() { cp "$1" "$2"; }
check_keybox() { return 0; }
test_candidate_for_strong() { return 4; }
''')
        self.assertEqual('QUEUED\nNOT_CONSUMED', result)

    def test_transient_verification_failure_stays_queued(self):
        result = self.run_script('''
mkdir -p "$state/queue" "$work"
echo candidate > "$state/queue/pending.xml"
process_queue_directory "$state/queue" || :
[ -f "$state/queue/pending.xml" ] && echo RETRY
''', '''
sanitize_keybox() { cp "$1" "$2"; }
check_keybox() { return 0; }
test_candidate_for_strong() { return 3; }
''')
        self.assertEqual('RETRY', result)

    def test_deferred_hunt_does_not_visit_other_sources(self):
        result = self.run_script('hunt_keybox || :', '''
process_queue_directory() { echo QUEUE; return 4; }
harvest_remote_candidates() { echo REMOTE; return 1; }
''')
        self.assertEqual('QUEUE', result)

    def test_duplicate_urls_are_downloaded_once_per_cycle(self):
        result = self.run_script('''
printf 'https://example.test/key.xml\\nhttps://example.test/key.xml\\n' > "$state/keybox_urls.conf"
harvest_remote_candidates || :
wc -l < "$root/downloads"
''', '''
curl() {
    echo download >> "$root/downloads"
    local out code=0
    while [ "$#" -gt 0 ]; do
        case "$1" in -o) shift; out=$1;; -w) code=1; shift;; esac
        shift
    done
    echo candidate > "$out"
    [ "$code" = 0 ] || printf 200
    return 0
}
sanitize_keybox() { cp "$1" "$2"; }
check_keybox() { return 0; }
test_candidate_for_strong() { return 2; }
''')
        self.assertEqual('1', result)

    def test_failed_candidate_install_reports_failure_and_keeps_active_file(self):
        result = self.run_script('''
echo original > "$state/keybox.xml"
echo https://example.test/key.xml > "$state/keybox_urls.conf"
heal_keybox
echo "status=$?"
cat "$state/keybox.xml"
''', '''
tee_state=$state
curl() { :; }
check_keybox() { return 0; }
score_keybox() { case "$1" in */keybox.xml) echo 1;; *) echo 2;; esac; }
fetch_candidate() { echo candidate > "$2"; }
sanitize_keybox() { command cp "$1" "$2"; }
cp() { case "$2" in */.keybox.*) return 1;; *) command cp "$@";; esac; }
reload_tee() { echo RELOADED; }
flush_gms_cache() { echo FLUSHED; }
''')
        self.assertEqual('status=1\noriginal', result)

    def test_failed_default_install_reports_failure_and_keeps_active_file(self):
        result = self.run_script('''
mkdir -p "$runtime/defaults"
echo default > "$runtime/defaults/keybox.xml"
echo original > "$state/keybox.xml"
heal_keybox
echo "status=$?"
cat "$state/keybox.xml"
''', '''
tee_state=$state
check_keybox() { case "$1" in */state/keybox.xml) return 1;; *) return 0;; esac; }
sanitize_keybox() { command cp "$1" "$2"; }
cp() { case "$2" in */.keybox.*) return 1;; *) command cp "$@";; esac; }
reload_tee() { echo RELOADED; }
flush_gms_cache() { echo FLUSHED; }
''')
        self.assertEqual('status=1\noriginal', result)

    def test_missing_active_file_does_not_select_another_runtime(self):
        result = self.run_script('''
touch "$runtime/service.sh"
expected="$(cd "$runtime/.." && pwd)"
found="$(find_root)"
if [ "$found" = "$expected" ]; then echo CURRENT; else echo FOREIGN; fi
''', '''
dirname() { printf '%s\\n' "$runtime"; }
pidof() { echo 123; }
readlink() { echo /data/foreign/tee-runtime; }
''')
        self.assertEqual('CURRENT', result)

    def test_service_metadata_publication_does_not_allow_concurrent_owner(self):
        service = (REPO / 'udonge/payload/service.sh').read_text(encoding='utf-8')
        acquisition = service[service.index('lock_wait=0'):service.index('boot_wait=0')]
        result = self.run_script('''
service_start() {
''' + acquisition + '''
    if ! command mkdir "$root/active" 2>/dev/null; then touch "$root/overlap"; fi
    sleep 0.5
    rmdir "$root/active" 2>/dev/null || :
    cleanup
    trap - EXIT INT TERM
}
service_start &
first=$!
while [ ! -f "$root/publishing" ]; do sleep 0.01; done
service_start &
second=$!
sleep 0.2
touch "$root/publish-now"
wait "$first" || :
wait "$second" || :
if [ -f "$root/overlap" ]; then echo OVERLAP; else echo SAFE; fi
''', '''
lock=$root/.service-lock
boot_id=testing
worker_guard() { "$@"; }
worker_current_pid() { worker_pid=$BASHPID; }
worker_process_start() { echo stable; }
process_is_current() { [ -s "$lock/pid" ]; }
mkdir() {
    if [ "$1" = "$lock" ] && command mkdir "$root/first-publisher" 2>/dev/null; then
        command mkdir "$@" || return $?
        touch "$root/publishing"
        while [ ! -f "$root/publish-now" ]; do sleep 0.01; done
        return 0
    fi
    command mkdir "$@"
}
''')
        self.assertEqual('SAFE', result)

    def test_payload_replacement_restarts_the_owned_service(self):
        result = self.run_script('''
tee_state=$state
mkdir -p "$runtime/tee/test-abi" "$root/tee-runtime"
echo current > "$runtime/version"
echo new-payload > "$runtime/payload.id"
echo current > "$root/tee-runtime/.version"
echo old-payload > "$root/tee-runtime/.payload_id"
echo 123 > "$root/tee-runtime/.pid"
echo stable > "$root/tee-runtime/.pid-start"
echo testing > "$root/tee-runtime/.pid-boot"
touch "$root/old-live" "$state/keybox.xml"
touch "$runtime/tee/test-abi/libTEESimulator.so" "$runtime/tee/test-abi/inject"
touch "$runtime/tee/classes.dex" "$runtime/tee/daemon"
printf '#!/bin/sh\\necho started > "$2/started"\\n' > "$runtime/tee/test-abi/supervisor"
chmod 700 "$runtime/tee/test-abi/supervisor"
start_tee
wait
if [ -f "$root/stopped" ]; then echo STOPPED; else echo OLD_RUNNING; fi
if [ -f "$root/tee-runtime/started" ]; then echo STARTED; else echo NOT_STARTED; fi
cat "$root/tee-runtime/.payload_id"
''', '''
boot_id=testing
getprop() { case "$1" in ro.build.version.sdk) echo 29;; ro.product.cpu.abi) echo test-abi;; esac; }
chcon() { :; }
ln() { :; }
mkdir() { [ "$1" = /data/adb ] || command mkdir "$@"; }
sleep() { :; }
find_tee_supervisor() { [ ! -f "$root/old-live" ] || echo 123; }
remember_tee_supervisor() { echo "$2" > "$1/.pid"; echo stable > "$1/.pid-start"; echo testing > "$1/.pid-boot"; }
worker_process_start() { echo stable; }
worker_is_current() { [ "$1" = 123 ] && [ "$2" = stable ] && [ "$3" = testing ]; }
worker_is_persistent_tee() { [ "$1" = 123 ]; }
worker_stop_tree() { [ "$1" != 123 ] || { rm "$root/old-live"; touch "$root/stopped"; }; }
process_is_current() { return 1; }
''')
        self.assertEqual('STOPPED\nSTARTED\nnew-payload', result)

    def test_failed_queue_archive_preserves_original_candidate(self):
        for validation in (0, 1):
            with self.subTest(validation=validation):
                result = self.run_script('''
mkdir -p "$state/queue" "$work"
echo original > "$state/queue/pending.xml"
echo blocked > "$state/queue/processed"
process_queue_directory "$state/queue" || :
if [ -f "$state/queue/pending.xml" ]; then cat "$state/queue/pending.xml"; else echo LOST; fi
''', '''
sanitize_keybox() { command cp "$1" "$2"; }
check_keybox() { return ''' + str(validation) + '''; }
test_candidate_for_strong() { return 2; }
''')
                self.assertEqual('original', result)

    def test_legacy_shutdown_stops_only_this_identity_hunter(self):
        result = self.run_script('''
root="$(cd "$root" && pwd)"
runtime=$root/runtime
mkdir -p "$root/foreign/runtime"
printf '#!/bin/sh\\nwhile :; do sleep 1; done\\n' > "$runtime/keybox_heal.sh"
cp "$runtime/keybox_heal.sh" "$root/foreign/runtime/keybox_heal.sh"
bash "$runtime/keybox_heal.sh" hunt_daemon >/dev/null 2>&1 &
owned=$!
bash "$root/foreign/runtime/keybox_heal.sh" hunt_daemon >/dev/null 2>&1 &
foreign=$!
bash "$runtime/keybox_heal.sh" status >/dev/null 2>&1 &
status=$!
trap 'kill "$owned" "$foreign" "$status" 2>/dev/null || :; wait 2>/dev/null || :' EXIT
sleep 0.1
worker_stop_legacy_hunters
if kill -0 "$owned" 2>/dev/null; then echo OWNED_RUNNING; else echo OWNED_STOPPED; fi
kill -0 "$foreign" 2>/dev/null && echo FOREIGN_RUNNING
kill -0 "$status" 2>/dev/null && echo STATUS_RUNNING
''')
        self.assertEqual('OWNED_STOPPED\nFOREIGN_RUNNING\nSTATUS_RUNNING', result)

    def test_legacy_shutdown_tolerates_a_process_disappearing_during_scan(self):
        result = self.run_script('''
set -e
worker_stop_legacy_hunters
echo DONE
''', 'worker_process_start() { return 1; }')
        self.assertEqual('DONE', result)

    def test_relative_legacy_script_requires_its_runtime_working_directory(self):
        result = self.run_script('''
root="$(cd "$root" && pwd)"
runtime=$root/runtime
mkdir -p "$root/foreign/runtime"
printf '#!/bin/sh\\nwhile :; do sleep 1; done\\n' > "$runtime/keybox_heal.sh"
cp "$runtime/keybox_heal.sh" "$root/foreign/runtime/keybox_heal.sh"
(cd "$runtime" && exec bash ./keybox_heal.sh hunt_daemon) >/dev/null 2>&1 &
owned=$!
(cd "$root/foreign/runtime" && exec bash ./keybox_heal.sh hunt_daemon) >/dev/null 2>&1 &
foreign=$!
trap 'kill "$owned" "$foreign" 2>/dev/null || :; wait 2>/dev/null || :' EXIT
sleep 0.1
worker_stop_legacy_hunters
if kill -0 "$owned" 2>/dev/null; then echo OWNED_RUNNING; else echo OWNED_STOPPED; fi
kill -0 "$foreign" 2>/dev/null && echo FOREIGN_RUNNING
''')
        self.assertEqual('OWNED_STOPPED\nFOREIGN_RUNNING', result)

    def test_legacy_shutdown_rechecks_start_after_reading_process_arguments(self):
        result = self.run_script('''
root="$(cd "$root" && pwd)"
runtime=$root/runtime
printf '#!/bin/sh\\nwhile :; do sleep 1; done\\n' > "$runtime/keybox_heal.sh"
bash "$runtime/keybox_heal.sh" hunt_daemon >/dev/null 2>&1 &
owned=$!
trap 'kill "$owned" 2>/dev/null || :; wait 2>/dev/null || :' EXIT
sleep 0.1
worker_process_start() {
    [ "$1" = "$owned" ] || return 1
    if [ -f "$root/snapshot" ]; then echo reused; else touch "$root/snapshot"; echo original; fi
}
worker_stop_legacy_hunters
kill -0 "$owned" 2>/dev/null && echo SAFE
''')
        self.assertEqual('SAFE', result)

    def test_legacy_match_accepts_named_busybox_and_rejects_shell_command_text(self):
        helper = (REPO / 'udonge/payload/worker.sh').read_text(encoding='utf-8')
        matcher = re.search(r'(?ms)^worker_is_legacy_hunter\(\) \{\n.*?^}', helper).group()
        matcher = matcher.replace('/proc/$1/', '$root/proc/$1/')
        result = self.run_script('''
mkdir -p "$root/proc/123"
ln -s "$runtime" "$root/proc/123/cwd"
printf '%s\\0' /data/identity/renamed-toolbox sh "$runtime/keybox_heal.sh" hunt_daemon > "$root/proc/123/cmdline"
worker_is_legacy_hunter 123 && echo BUSYBOX
printf '%s\\0' /system/bin/sh -c "echo $runtime/keybox_heal.sh hunt_daemon" > "$root/proc/123/cmdline"
if worker_is_legacy_hunter 123; then echo UNSAFE; else echo TEXT_IGNORED; fi
''', 'worker_busybox_name=renamed-toolbox\n' + matcher)
        self.assertEqual('BUSYBOX\nTEXT_IGNORED', result)

if __name__ == '__main__':
    unittest.main()
