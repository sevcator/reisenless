import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
BASH = 'C:/Program Files/Git/bin/bash.exe' if os.name == 'nt' else shutil.which('bash')


class FeatureActivationTest(unittest.TestCase):
    def run_entry(self, name, markers, enabled=False):
        with tempfile.TemporaryDirectory(prefix='reisenless-activation-') as directory:
            root = Path(directory)
            state = root / 'state'
            runtime = root / 'runtime'
            state.mkdir()
            runtime.mkdir()
            for marker in markers:
                (state / marker).touch()
            if enabled:
                shutil.copyfile(ROOT / 'udonge/payload/worker.sh', runtime / 'worker.sh')
                (runtime / 'defaults').mkdir()
                for item in ('targets.conf', 'props.conf', 'pif.conf', 'keybox_urls.conf'):
                    (runtime / 'defaults' / item).write_text('', encoding='utf-8')
            shell_root = root.as_posix()
            if os.name == 'nt':
                shell_root = '/' + shell_root[0].lower() + shell_root[2:]
            production = (ROOT / 'udonge/payload' / name).read_text(encoding='utf-8')
            production = production.replace('root=/data/adb/udonge', f"root='{shell_root}'")
            boundary = f"audit_root='{shell_root}'\n" + '''
record() { printf '%s\\n' "$*" >> "$audit_root/commands"; }
cat() { if [ "$1" = /proc/sys/kernel/random/boot_id ]; then echo testing; else command cat "$@"; fi; }
getprop() {
    record "getprop $*"
    case "$1" in sys.boot_completed) echo 1;; ro.build.version.sdk) echo 23;; esac
}
resetprop() { record "resetprop $*"; }
setprop() { record "setprop $*"; }
settings() { record "settings $*"; }
start() { record "start $*"; }
stop() { record "stop $*"; }
pm() { record "pm $*"; }
am() { record "am $*"; }
mkdir() { case "$*" in */data/system/*) return 1;; esac; command mkdir "$@"; }
touch() { case "$*" in */data/system/*) return 1;; esac; command touch "$@"; }
chmod() { case "$*" in */data/system/*) return 0;; esac; command chmod "$@"; }
chown() { :; }
'''
            result = subprocess.run([BASH], input=boundary + production,
                                    capture_output=True, text=True, encoding='utf-8', timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            commands = (root / 'commands').read_text(encoding='utf-8') if (root / 'commands').exists() else ''
            return commands, sorted(path.name for path in state.iterdir()), (root / '.service-lock').exists()

    def test_unrequested_disabled_and_pending_features_have_no_side_effects(self):
        for name in ('post-fs-data.sh', 'service.sh'):
            for markers in ((), ('disabled',), ('enabled', 'disabled'), ('enabled', 'pending-reboot')):
                with self.subTest(script=name, markers=markers):
                    commands, state, lock = self.run_entry(name, markers)
                    self.assertEqual('', commands)
                    self.assertEqual(sorted(markers), state)
                    self.assertFalse(lock)

    def test_explicit_enablement_keeps_usb_debugging_under_user_control(self):
        for name in ('post-fs-data.sh', 'service.sh'):
            with self.subTest(script=name):
                commands, state, lock = self.run_entry(name, ('enabled',), enabled=True)
                self.assertIn('getprop', commands)
                self.assertNotIn('start adbd', commands)
                self.assertNotIn('settings ', commands)
                self.assertNotIn('persist.sys.usb.config', commands)
                self.assertNotIn('persist.sys.oppo.usbactive', commands)
                self.assertFalse(lock)


if __name__ == '__main__':
    unittest.main()
