import importlib.util
import tempfile
import os
import shutil
import subprocess
from zipfile import ZipFile
from pathlib import Path
from unittest import TestCase, main
from unittest.mock import patch
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location('magisk_build', Path(__file__).resolve().parent.parent / 'build.py')
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)

class BuildCommandsTest(TestCase):
    def test_sync_stops_legacy_hunter_before_swapping_only_its_runtime(self):
        bash = 'C:/Program Files/Git/bin/bash.exe' if os.name == 'nt' else shutil.which('bash')
        def shell_path(path):
            value = path.as_posix()
            return '/' + value[0].lower() + value[2:] if os.name == 'nt' else value
        with tempfile.TemporaryDirectory(prefix='udonge-sync-test-') as directory:
            secure = Path(directory)
            runtime = secure / '.runtime/runtime'
            foreign = secure / '.foreign/runtime'
            incoming = secure / 'incoming'
            for path in (runtime, foreign, incoming):
                path.mkdir(parents=True)
            standin = '#!/bin/sh\nwhile :; do sleep 1; done\n'
            (secure / 'hunt_daemon').write_text(standin, encoding='utf-8', newline='\n')
            for path in (runtime, foreign):
                (path / 'keybox_heal.sh').write_text(standin, encoding='utf-8', newline='\n')
            for name in ('service.sh', 'hideapps.dex', 'payload.id'):
                (incoming / name).write_text('new runtime\n', encoding='utf-8', newline='\n')
            shutil.copyfile(Path(__file__).resolve().parent.parent / 'udonge/payload/worker.sh', incoming / 'worker.sh')

            processes = [subprocess.Popen([bash, '-c', 'exec -a "$1" bash hunt_daemon',
                                          'fixture', shell_path(path / 'keybox_heal.sh')], cwd=secure,
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                         for path in (runtime, foreign)]
            try:
                script = build._udonge_sync_script({
                    'secureDir': shell_path(secure), 'udongeDir': '.runtime',
                    'busyboxName': 'missing-busybox', 'udongeFileType': 'test_lib_file',
                }).replace('/data/local/tmp', shell_path(secure / 'stage'))
                fixture = (f'fixture="{shell_path(incoming)}"\n'
                           'unzip() { command cp -R "$fixture/." "$root/runtime.new/"; }\n'
                           'cat() { if [ "$1" = /proc/sys/kernel/random/boot_id ]; then echo testing; else command cat "$@"; fi; }\n'
                           'sleep 0.1\n')
                result = subprocess.run([bash], input=fixture + script, text=True, encoding='utf-8',
                                        capture_output=True, timeout=20)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn('UDONGE_SYNCED', result.stdout)
                self.assertEqual('new runtime\n', (runtime / 'service.sh').read_text(encoding='utf-8'))
                self.assertIsNotNone(processes[0].poll(), 'legacy hunter survived runtime replacement')
                self.assertIsNone(processes[1].poll(), 'another identity was stopped')
            finally:
                for process in processes:
                    if process.poll() is None:
                        process.terminate()
                    process.wait(timeout=5)

    def test_packaged_payload_is_checked_before_device_sync(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            payload = output / 'udonge.bin'
            payload.write_bytes(b'current runtime')
            apk = output / 'built.apk'
            with ZipFile(apk, 'w') as archive:
                archive.writestr('assets/runtime.bin', payload.read_bytes())
            identity = {
                'secureDir': '/data/.current', 'udongeDir': '.runtime',
                'busyboxName': 'toolbox', 'udongeFileType': 'current_lib_file',
                'udongeArchive': 'runtime.bin',
            }
            with patch.object(build, 'args', SimpleNamespace(serial='phone'), create=True), \
                    patch.object(build, 'config', {'outdir': output}, create=True), \
                    patch.object(build, 'adb_path', Path('adb'), create=True), \
                    patch.object(build, '_build_identity', return_value=identity), \
                    patch.object(build, 'ensure_adb') as adb, \
                    patch.object(build, 'execv', return_value=SimpleNamespace(returncode=0)) as push, \
                    patch.object(build.subprocess, 'run', side_effect=[
                        SimpleNamespace(stdout='uid=0(root)', returncode=0),
                        SimpleNamespace(stdout='UDONGE_SYNCED', stderr='', returncode=0),
                    ]):
                build._sync_udonge_to_device(payload, installed_apk=apk)
                adb.assert_called_once()
                self.assertEqual(2, push.call_count)
                adb.reset_mock()
                push.reset_mock()
                payload.write_bytes(b'unrelated runtime')
                build._sync_udonge_to_device(payload, installed_apk=apk)
                adb.assert_not_called()
                push.assert_not_called()

    def test_generation_is_an_explicit_action(self):
        with patch('sys.argv', ['build.py', 'gen']):
            args = build.parse_args()
        self.assertEqual('generate_native', args.func.__name__)

    def test_sync_requires_explicit_installation(self):
        with patch.object(build, 'args', create=True) as args, patch.object(build, 'ensure_adb') as adb:
            args.install = False
            build._sync_udonge_to_device(Path(__file__))
        adb.assert_not_called()

    def test_sync_only_updates_the_current_build_identity(self):
        script = build._udonge_sync_script({
            'secureDir': '/data/.current', 'udongeDir': '.runtime',
            'busyboxName': 'toolbox', 'udongeFileType': 'current_lib_file',
        })
        self.assertIn('root=/data/.current/.runtime', script)
        self.assertNotIn('find /data', script)
        self.assertNotIn('chmod -R 700 "$root"', script)
        self.assertIn('worker.sh', script)

    def test_sync_accepts_generated_build_identity(self):
        with patch.object(build, 'args', SimpleNamespace(release=False), create=True), \
                patch.object(build, 'config', {'randomizeBuild': 'true'}, create=True), \
                patch.object(build, '_repository_namespace', return_value='test-repository'):
            identity = build._build_identity()
        script = build._udonge_sync_script(identity)
        self.assertIn(identity['busyboxName'], script)
        self.assertIn(identity['secureDir'] + '/' + identity['udongeDir'], script)

    def test_explicit_install_action_authorizes_sync_after_installation(self):
        with patch('sys.argv', ['build.py', 'install', 'built.apk']):
            args = build.parse_args()
        with patch.object(build, 'args', args, create=True), \
                patch.object(build, 'config', {'outdir': Path('out')}, create=True), \
                patch.object(build, 'adb_path', Path('adb'), create=True), \
                patch.object(build, 'ensure_paths'), patch.object(build, 'ensure_adb'), \
                patch.object(build, 'cmd_out', return_value='List of devices attached\nphone\tdevice\n'), \
                patch.object(build, 'execv', return_value=SimpleNamespace(returncode=0)), \
                patch.object(build, '_sync_udonge_to_device') as sync:
            args.func()
        sync.assert_called_once_with(Path('out/udonge.bin'), installed_apk=Path('built.apk'))

if __name__ == '__main__':
    main()
