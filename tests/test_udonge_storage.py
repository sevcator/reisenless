import ast
import hashlib
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import unittest
import zlib


ROOT = Path(__file__).resolve().parents[1]
BASH = r"C:\Program Files\Git\bin\bash.exe" if Path(r"C:\Program Files\Git\bin\bash.exe").exists() else shutil.which("bash")


def build_functions():
    names = {"_fit_tee_path", "_patch_tee_native_paths", "_patch_tee_dex"}
    tree = ast.parse((ROOT / "build.py").read_text(encoding="utf-8"))
    selected = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in names]

    def error(message):
        raise ValueError(message)

    namespace = {"error": error, "Path": Path, "hashlib": hashlib, "struct": struct, "zlib": zlib}
    exec(compile(ast.Module(body=selected, type_ignores=[]), "build.py", "exec"), namespace)
    return namespace


class UdongeStorageTests(unittest.TestCase):
    def run_cleanup(self, body):
        source = (ROOT / "scripts/app_functions.sh").read_text(encoding="utf-8")
        source = source.replace("/data/adb", "${canonical}")
        with tempfile.TemporaryDirectory(prefix="reisenless-storage-", ignore_cleanup_errors=True) as directory:
            script = Path(directory) / "fixture.sh"
            script.write_text(
                "set -e\ncanonical=$(pwd -P)/canonical\n"
                "SECURE_DIR=$(pwd -P)/current\nLEGACY_SECURE_DIR=$(pwd -P)/previous\n"
                "DB_NAME=db\nUDONGE_DIR=udonge\nSTAGE_SCRIPT=stage.sh\n"
                "mkdir -p \"$canonical\" \"$SECURE_DIR\"\n" + source + "\n" + body,
                encoding="utf-8", newline="\n",
            )
            result = subprocess.run([BASH, "fixture.sh"], cwd=directory, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_migration_keeps_oem_directory_inode(self):
        self.run_cleanup('''
before=$(stat -c %i "$canonical")
cleanup_migrated_layout "$canonical" magisk.db udonge .migration-canonical.complete canonical || true
[ -d "$canonical" ]
[ "$(stat -c %i "$canonical")" = "$before" ]
''')

    def test_foreign_root_cleanup_keeps_oem_directory_inode(self):
        self.run_cleanup('''
validate_foreign_root_path() { return 1; }
foreign_kernel_active() { return 1; }
preserve_upgrade_boot_files() { return 0; }
stop_foreign_root_processes() { return 0; }
preserve_foreign_module_images() { return 0; }
before=$(stat -c %i "$canonical")
cleanup_obsolete_frameworks storage-test
[ -d "$canonical" ]
[ "$(stat -c %i "$canonical")" = "$before" ]
''')

    def test_private_legacy_cleanup_still_removes_owned_directory(self):
        self.run_cleanup('''
mkdir -p "$LEGACY_SECURE_DIR"
printf 'source=%s\n' "$LEGACY_SECURE_DIR" > "$SECURE_DIR/.migration-private.complete"
cleanup_migrated_layout "$LEGACY_SECURE_DIR" old.db udonge .migration-private.complete private
[ ! -e "$LEGACY_SECURE_DIR" ]
[ -d "$canonical" ]
''')

    def test_native_tee_paths_preserve_binary_layout(self):
        patch = build_functions()["_patch_tee_native_paths"]
        libraries = list((ROOT / "udonge/payload/tee").rglob("*.so"))
        self.assertTrue(libraries)
        changed = 0
        for library in libraries:
            original = library.read_bytes()
            output = patch(original, "/data/.gacl/.thj")
            self.assertEqual(len(output), len(original))
            self.assertNotIn(b"/data/adb/tricky_store", output)
            self.assertEqual(output[:4], b"\x7fELF")
            changed += output != original
        self.assertGreater(changed, 0)

    def test_dex_private_paths_and_checksums(self):
        original = (ROOT / "udonge/payload/tee/classes.dex").read_bytes()
        output = build_functions()["_patch_tee_dex"](original, "/data/.gacl/.thj")
        self.assertEqual(len(output), len(original))
        self.assertNotIn(b"/data/adb/tricky_store", output)
        self.assertNotIn(b"/data/adb/modules/tricky_store", output)
        self.assertEqual(output[12:32], hashlib.sha1(output[32:]).digest())
        self.assertEqual(struct.unpack_from("<I", output, 8)[0], zlib.adler32(output[12:]) & 0xffffffff)

    def test_path_padding_preserves_path_and_rejects_overflow(self):
        fit = build_functions()["_fit_tee_path"]
        padded = fit("/data/.abcd/.ef/state", 24)
        self.assertEqual(len(padded), 24)
        self.assertEqual(re.sub(rb"/+", b"/", padded), b"/data/.abcd/.ef/state")
        with self.assertRaises(ValueError):
            fit("/data/a-path-too-long-for-the-payload/state", 20)

    def test_service_never_creates_canonical_tee_alias(self):
        source = (ROOT / "udonge/payload/service.sh").read_text(encoding="utf-8")
        self.assertNotRegex(source, r"(?m)^\s*mkdir\s[^\n]*/data/adb")
        self.assertNotIn("/data/adb/tricky_store", source)

    def test_injector_accepts_only_recorded_keystore_process(self):
        source = (ROOT / "udonge/payload/tee/inject.sh").read_text(encoding="utf-8")
        with tempfile.TemporaryDirectory(prefix="reisenless-inject-", ignore_cleanup_errors=True) as directory:
            folder = Path(directory)
            process = folder / "proc/123"
            process.mkdir(parents=True)
            (process / "stat").write_text(" ".join(["0"] * 21 + ["456"]) + "\n")
            (folder / ".keystore-pid").write_text("123\n")
            (folder / ".keystore-start").write_text("456\n")
            (folder / "inject.sh").write_text("readlink() { cat \"$1\"; }\n" + source.replace("/proc/", "./proc/"), newline="\n")
            (folder / "inject.bin").write_text("#!/bin/sh\nprintf '%s\\n' invoked\n", newline="\n")
            for name, start, arguments, allowed in (
                ("keystore2", "456", ["123", "libTEESimulator.so", "entry"], True),
                ("keystore", "456", ["123", "libTEESimulator.so", "entry"], True),
                ("com.tencent.soter.soterserver", "456", ["123", "libTEESimulator.so", "entry"], False),
                ("system_server", "456", ["123", "libTEESimulator.so", "entry"], False),
                ("keystore2", "457", ["123", "libTEESimulator.so", "entry"], False),
                ("keystore2", "456", ["124", "libTEESimulator.so", "entry"], False),
                ("keystore2", "456", ["123 124", "libTEESimulator.so", "entry"], False),
                ("keystore2", "456", ["123", "other.so", "entry"], False),
            ):
                with self.subTest(name=name, start=start, arguments=arguments):
                    (process / "exe").write_text("/system/bin/" + name + "\n")
                    (folder / ".keystore-start").write_text(start + "\n")
                    result = subprocess.run([BASH, "inject.sh", *arguments], cwd=folder, capture_output=True, text=True)
                    self.assertEqual(result.returncode == 0, allowed, result.stderr)
                    self.assertEqual("invoked" in result.stdout, allowed)

    def test_tee_setup_disables_global_boot_changes_and_excludes_system_uids(self):
        source = (ROOT / "udonge/payload/service.sh").read_text(encoding="utf-8")
        start = source.index("start_tee() {")
        end = source.index("\n}\n", start) + 2
        self.run_cleanup(source[start:end] + '''
root="$SECURE_DIR/udonge"
state="$root/state"
tee_state="$state"
runtime="$root/runtime"
mkdir -p "$state" "$runtime/tee/arm64-v8a" "$runtime/defaults" "$root/tee-runtime"
for name in libTEESimulator.so inject supervisor; do
    touch "$runtime/tee/arm64-v8a/$name"
done
touch "$runtime/tee/classes.dex" "$runtime/tee/daemon" "$runtime/tee/inject.sh"
printf '1\\n' > "$runtime/version"
printf 'current-payload\\n' > "$runtime/payload.id"
cp "$runtime/version" "$root/tee-runtime/.version"
cp "$runtime/payload.id" "$root/tee-runtime/.payload_id"
printf '#!/bin/sh\\n' > "$root/tee-runtime/supervisor"
chmod +x "$root/tee-runtime/supervisor"
printf 'keybox\\n' > "$runtime/defaults/keybox.xml"
printf 'android\\ncom.android.phone\\ncom.google.android.gms\\ncom.oem.system\\ncom.oem.profile\\n' > "$state/target.txt"
getprop() { case "$1" in ro.build.version.sdk) echo 31;; ro.product.cpu.abi) echo arm64-v8a;; esac; }
cmd() { printf 'package:android uid:1000\\npackage:com.oem.system uid:1001\\npackage:com.oem.profile uid:101001\\npackage:com.google.android.gms uid:10176\\n'; }
chcon() { return 0; }
worker_is_current() { return 1; }
find_tee_supervisor() { echo 123; }
remember_tee_supervisor() { return 0; }
read_profile_value() { echo 2026-10-05; }
set +e
start_tee
result=$?
set -e
[ "$result" = 0 ]
[ "$(cat "$state/boot_props_mode")" = off ]
grep -qE '^com\\.google\\.android\\.gms(!)?$' "$state/target.txt"
! grep -qE 'android$|com.android.phone|com.oem.system|com.oem.profile' "$state/target.txt"
[ ! -e "$state/tee-broken" ]
''')

    def run_boot_confirmation(self, body):
        source = (ROOT / "udonge/payload/service.sh").read_text(encoding="utf-8")
        start = source.index("confirm_boot() {")
        end = source.index("\n}\n", start) + 2
        self.run_cleanup(source[start:end] + '''
state="$SECURE_DIR/state"
runtime="$SECURE_DIR/runtime"
boot_id=current-boot
mkdir -p "$state"
printf '%s\\n' "$boot_id" > "$state/boot-pending"
pidof() { printf '100\\n'; }
worker_process_start() { printf '200\\n'; }
worker_is_current() { return 0; }
getprop() { printf '1\\n'; }
background_allowed() { return 1; }
sleep() { :; }
''' + body)

    def test_stable_boot_is_confirmed_without_network_work(self):
        self.run_boot_confirmation('''
confirm_boot
[ ! -e "$state/boot-pending" ]
''')

    def test_restarted_system_server_does_not_confirm_boot(self):
        self.run_boot_confirmation('''
worker_is_current() { return 1; }
confirm_boot
[ "$(cat "$state/boot-pending")" = "$boot_id" ]
''')

    def test_other_boot_marker_is_not_cleared(self):
        self.run_boot_confirmation('''
printf 'another-boot\\n' > "$state/boot-pending"
confirm_boot
[ "$(cat "$state/boot-pending")" = another-boot ]
''')

    def test_default_pif_profile_is_certified(self):
        content = (ROOT / "udonge/payload/defaults/pif.conf").read_text(encoding="utf-8")
        self.assertNotIn("CANARY", content)
        self.assertNotIn("tegu_beta", content)
        self.assertNotIn("marlin", content)
        self.assertIn("DEVICE_INITIAL_SDK_INT=29", content)
        self.assertIn("FINGERPRINT=Xiaomi/apollopro_global/apollo:12/RKQ1.211001.001/V14.0.4.0.SJDMIXM:user/release-keys", content)

    def test_canary_profile_migration(self):
        source = (ROOT / "udonge/payload/post-fs-data.sh").read_text(encoding="utf-8")
        self.run_cleanup('''
state="$SECURE_DIR/state"
runtime="$SECURE_DIR/runtime"
mkdir -p "$state" "$runtime/defaults"
printf 'FINGERPRINT=google/tegu_beta/tegu:CANARY/ZP11.260918.007/16484274:user/release-keys\\n' > "$state/pif.conf"
printf 'FINGERPRINT=google/marlin/marlin:10/QP1A.191005.007.A3/5988628:user/release-keys\\nDEVICE_INITIAL_SDK_INT=25\\n' > "$runtime/defaults/pif.conf"
if grep -qE 'CANARY|tegu|ZP11|CP2A' "$state/pif.conf" 2>/dev/null; then
    cp "$runtime/defaults/pif.conf" "$state/pif.conf"
    chmod 600 "$state/pif.conf"
fi
grep -qxF 'DEVICE_INITIAL_SDK_INT=25' "$state/pif.conf"
! grep -qF 'CANARY' "$state/pif.conf"
''')

    def test_empty_oem_data_adb_not_touched(self):
        self.run_cleanup('''
mkdir -p "$canonical"
STAGE_SCRIPT=stage.sh
migrate_legacy_layout
[ ! -f "$SECURE_DIR/.migration-canonical.complete" ]
''')

    def test_default_identity_secure_dir_is_not_data_adb(self):
        build_text = (ROOT / "build.py").read_text(encoding="utf-8")
        self.assertNotIn('"secureDir": config.get("secureDir", "/data/adb")', build_text)
        self.assertIn('"secureDir": config.get("secureDir", "/data/.ms")', build_text)
        plugin_text = (ROOT / "app/build-logic/src/main/java/Plugin.kt").read_text(encoding="utf-8")
        self.assertNotIn('get("secureDir") ?: "/data/adb"', plugin_text)
        self.assertIn('get("secureDir") ?: "/data/.ms"', plugin_text)

    def test_packaged_udonge_bin_has_no_data_adb_paths(self):
        import zipfile
        archive_path = ROOT / "out/udonge.bin"
        if not archive_path.exists():
            return
        with zipfile.ZipFile(archive_path, "r") as zf:
            for name in zf.namelist():
                if name.startswith("zygisk/"):
                    continue
                content = zf.read(name)
                self.assertNotIn(b"/data/adb", content, f"File {name} in udonge.bin contains /data/adb")

    def test_keybox_urls_use_https(self):
        urls_file = ROOT / "udonge/payload/defaults/keybox_urls.conf"
        self.assertTrue(urls_file.exists())
        lines = [line.strip() for line in urls_file.read_text(encoding="utf-8").splitlines() if line.strip() and not line.strip().startswith("#")]
        self.assertTrue(lines)
        for line in lines:
            self.assertTrue(line.startswith("https://"), f"URL {line} is not HTTPS")

    def test_config_maps_board_and_partition_props(self):
        config_text = (ROOT / "udonge/native/config.cpp").read_text(encoding="utf-8")
        self.assertIn('cfg.props["ro.board.first_api_level"] = v;', config_text)
        self.assertIn('cfg.props["ro.board.api_level"] = v;', config_text)
        self.assertIn('cfg.props["ro.product.board"] = v;', config_text)
        self.assertIn('cfg.props["ro.system_ext.build.fingerprint"] = v;', config_text)
        self.assertIn('cfg.props["ro.odm.build.fingerprint"] = v;', config_text)

    def test_hooks_includes_xiaomi_devicelock(self):
        hooks_text = (ROOT / "udonge/native/hooks.cpp").read_text(encoding="utf-8")
        self.assertIn('"ro.secureboot.devicelock"', hooks_text)

    def test_service_package_manager_fallback(self):
        service_text = (ROOT / "udonge/payload/service.sh").read_text(encoding="utf-8")
        self.assertIn("com.tencent.soter.soterserver", service_text)
        self.assertIn("com.miui.securitycenter", service_text)
        self.assertIn("while [ \"$pkg_try\" -lt 15 ]; do", service_text)

    def test_app_functions_has_no_data_adb_references(self):
        source = (ROOT / "scripts/app_functions.sh").read_text(encoding="utf-8")
        self.assertNotIn("/data/adb", source)

    def test_hooks_filters_adb_entry_from_data(self):
        hooks_text = (ROOT / "udonge/native/hooks.cpp").read_text(encoding="utf-8")
        self.assertIn("is_data_directory", hooks_text)
        self.assertIn('strcmp(entry->d_name, "adb") == 0 && is_data_directory(dir)', hooks_text)


if __name__ == "__main__":
    unittest.main()

