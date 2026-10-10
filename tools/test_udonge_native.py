
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'out/native-regression'

def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, text=True, encoding='utf-8', **kwargs)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    sdk = Path(os.environ['ANDROID_HOME'])
    compiler = sdk / 'ndk/magisk/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android23-clang++.cmd'
    adb = sdk / 'platform-tools/adb.exe'
    OUT.mkdir(parents=True, exist_ok=True)
    binary = OUT / 'cache-test'
    run(compiler, '-std=c++20', '-O2', '-static-libstdc++', '-Wall', '-Wextra',
        '-I', ROOT / 'udonge/native', ROOT / 'udonge/native/tests/cache_test.cpp', '-o', binary)
    remote = '/data/local/tmp/reisenless-native-regression'
    run(adb, '-s', args.serial, 'shell', f'mkdir -p {remote}')
    run(adb, '-s', args.serial, 'push', binary, remote + '/cache-test', stdout=subprocess.DEVNULL)
    try:
        run(adb, '-s', args.serial, 'shell', f'chmod 700 {remote}/cache-test; {remote}/cache-test {remote}')
    finally:
        run(adb, '-s', args.serial, 'shell', f'rm -f {remote}/cache-test {remote}/cache-input {remote}/cache-input.new; rmdir {remote}')
    run(sys.executable, ROOT / 'tools/test_udonge_hooks.py', '--serial', args.serial)

if __name__ == '__main__':
    main()
