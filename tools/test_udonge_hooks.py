
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
REMOTE = '/data/local/tmp/reisenless-hook-regression'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    sdk = Path(os.environ['ANDROID_HOME'])
    compiler = sdk / 'ndk/magisk/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android24-clang++.cmd'
    adb = sdk / 'platform-tools/adb.exe'
    out = ROOT / 'out/hook-regression'
    out.mkdir(parents=True, exist_ok=True)
    binary = out / 'hooks-test'

    def run(*command, **kwargs):
        return subprocess.run([str(value) for value in command], check=kwargs.pop('check', True), **kwargs)

    run(compiler, '-std=c++20', '-O2', '-static-libstdc++', '-Wall', '-Wextra',
        '-ffunction-sections', '-fdata-sections', '-Wl,--gc-sections',
        ROOT / 'udonge/native/tests/hooks_test.cpp', '-o', binary)
    lsplt = ROOT / 'native/src/external/lsplt/lsplt/src/main/jni'
    hooks = (ROOT / 'udonge/native/hooks.cpp').read_text(encoding='utf-8')
    hooks = hooks.replace('lsplt::MapInfo::Scan()', 'test_scan()')
    hooks = hooks.replace(
        '#include "../../native/src/external/lsplt/lsplt/src/main/jni/include/lsplt.hpp"',
        '#include <lsplt.hpp>')
    (out / 'late-hooks-source.cpp').write_text(hooks, encoding='utf-8', newline='\n')
    late_binary = out / 'late-hooks-test'
    library = out / 'liblate.so'
    next_library = out / 'libnext.so'
    run(compiler, '-shared', '-fPIC', '-static-libstdc++',
        ROOT / 'udonge/native/tests/late_library.cpp', '-o', library)
    run(compiler, '-shared', '-fPIC', '-static-libstdc++',
        ROOT / 'udonge/native/tests/next_library.cpp', '-o', next_library)

    run(compiler, '-std=c++20', '-O2', '-static-libstdc++', '-Wall', '-Wextra',
        '-fno-optimize-sibling-calls',
        '-ffunction-sections', '-fdata-sections', '-Wl,--gc-sections',
        '-I', out, '-I', ROOT / 'udonge/native', '-I', lsplt / 'include',
        ROOT / 'udonge/native/tests/late_hooks_test.cpp',
        lsplt / 'lsplt.cc', lsplt / 'elf_util.cc', '-ldl', '-llog', '-o', late_binary)
    (out / 'directory-hooks-source.cpp').write_text(
        hooks.replace('test_scan()', 'lsplt::MapInfo::Scan()').replace('::readlink(', 'test_readlink('),
        encoding='utf-8', newline='\n')
    directory_binary = out / 'directory-hooks-test'
    run(compiler, '-std=c++20', '-O2', '-static-libstdc++', '-Wall', '-Wextra',
        '-ffunction-sections', '-fdata-sections', '-Wl,--gc-sections',
        '-I', out, '-I', ROOT / 'udonge/native', '-I', lsplt / 'include',
        ROOT / 'udonge/native/tests/directory_hooks_test.cpp', '-o', directory_binary)
    run(adb, '-s', args.serial, 'shell', f'mkdir -p {REMOTE}')
    try:
        for artifact in (binary, late_binary, library, next_library, directory_binary):
            run(adb, '-s', args.serial, 'push', artifact, f'{REMOTE}/{artifact.name}',
                stdout=subprocess.DEVNULL)
        failed = False
        for executable in (binary, late_binary, directory_binary):
            run(adb, '-s', args.serial, 'shell', f'chmod 700 {REMOTE}/{executable.name}')
            result = run(adb, '-s', args.serial, 'shell',
                         f'{REMOTE}/{executable.name} {REMOTE}', check=False)
            failed |= result.returncode != 0
        result = run(adb, '-s', args.serial, 'shell',
                     f'{REMOTE}/late-hooks-test {REMOTE} dlsym', check=False)
        failed |= result.returncode != 0
        if failed:
            raise SystemExit(1)
    finally:
        run(adb, '-s', args.serial, 'shell',
            f'rm -f {REMOTE}/hooks-test {REMOTE}/ordinary-file '
            f'{REMOTE}/late-hooks-test {REMOTE}/liblate.so '
            f'{REMOTE}/libnext.so {REMOTE}/directory-hooks-test '
            f'{REMOTE}/magisk-fixture {REMOTE}/ordinary-fixture')
        run(adb, '-s', args.serial, 'shell', f'rmdir {REMOTE}')

if __name__ == '__main__':
    main()
