#!/usr/bin/env python3
import argparse
import base64
import binascii
import errno
import glob
import hashlib
import io
import json
import multiprocessing
import os
import platform
import re
import secrets
import shutil
import stat
import string
import struct
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import zlib
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, BadZipFile, ZipFile, ZipInfo


def color_print(code, str):
    if no_color:
        print(str)
    else:
        str = str.replace("\n", f"\033[0m\n{code}")
        print(f"{code}{str}\033[0m")


def error(str):
    color_print("\033[41;39m", f"\n! {str}\n")
    sys.exit(1)


def header(str):
    color_print("\033[44;39m", f"\n{str}\n")


def vprint(str):
    if args.verbose > 0:
        print(str)



os_name = platform.system().lower()
is_windows = False
if os_name != "linux" and os_name != "darwin":

    is_windows = True
    os_name = "windows"
EXE_EXT = ".exe" if is_windows else ""

no_color = False
if is_windows:
    try:
        import colorama

        colorama.init()
    except ImportError:

        no_color = True

if not sys.version_info >= (3, 8):
    error("Requires Python 3.8+")

cpu_count = multiprocessing.cpu_count()

# Common constants
support_abis = {
    "armeabi-v7a": "thumbv7neon-linux-androideabi",
    "x86": "i686-linux-android",
    "arm64-v8a": "aarch64-linux-android",
    "x86_64": "x86_64-linux-android",
    "riscv64": "riscv64-linux-android",
}
abi_alias = {
    "arm": "armeabi-v7a",
    "arm32": "armeabi-v7a",
    "arm64": "arm64-v8a",
    "x64": "x86_64",
}
default_abis = support_abis.keys() - {"riscv64"}
support_targets = {"magisk", "minit", "mboot", "mpol"}
default_targets = support_targets.copy()
rust_targets = default_targets.copy()

rust_crate_map = {"minit": "magiskinit", "mboot": "magiskboot", "mpol": "magiskpolicy"}
clean_targets = {"native", "cpp", "rust", "app"}
ondk_version = "r30.1"


config = {}
args: argparse.Namespace
build_abis: dict[str, str]
force_out = False
udonge_built = False
signing_config = None

SIGNING_SECRET_KEYS = (
    "REISENLESS_KEYSTORE_BASE64",
    "REISENLESS_KEYSTORE_PASSWORD",
    "REISENLESS_KEY_ALIAS",
    "REISENLESS_KEY_PASSWORD",
)
ANDROID_DEBUG_CERT_SHA256 = (
    "fd28057fa1910c30ba7cf6c6a69812da92fc270596942e4dbdcdb5857decf5e"
)






def mv(source: Path, target: Path):
    try:
        shutil.move(source, target)
        vprint(f"mv {source} -> {target}")
    except:
        pass


def cp(source: Path, target: Path):
    try:
        shutil.copyfile(source, target)
        vprint(f"cp {source} -> {target}")
    except:
        pass


def rm(file: Path):
    try:
        os.remove(file)
        vprint(f"rm {file}")
    except FileNotFoundError as e:
        pass


def rm_on_error(func, path, exc_info):
    """Repair permissions and retry the exact operation that failed."""
    try:
        os.chmod(path, stat.S_IWRITE | stat.S_IREAD)
        func(path)
    except FileNotFoundError:
        return
    except OSError as exc:
        is_nonempty_dir = func is os.rmdir and (
            exc.errno == errno.ENOTEMPTY or getattr(exc, "winerror", None) == 145
        )
        if not is_nonempty_dir:
            raise
        # CPython's Windows walker can observe a directory as empty just before
        # delayed compiler outputs become visible. Remove the late entries and
        # retry the directory itself.
        for attempt in range(10):
            for entry in os.scandir(path):
                child = Path(entry.path)
                if entry.is_dir(follow_symlinks=False):
                    rm_rf(child)
                else:
                    try:
                        os.chmod(child, stat.S_IWRITE | stat.S_IREAD)
                        os.unlink(child)
                    except FileNotFoundError:
                        pass
            try:
                os.rmdir(path)
                return
            except FileNotFoundError:
                return
            except OSError as retry:
                if (
                    retry.errno != errno.ENOTEMPTY
                    and getattr(retry, "winerror", None) != 145
                ) or attempt == 9:
                    raise
                time.sleep(0.05 * (attempt + 1))


def rm_rf(path: Path):
    vprint(f"rm -rf {path}")
    delete_path = path
    if is_windows and path.exists():
        # Detach the tree first so no compiler helper can recreate children at
        # the path while it is being removed.
        delete_path = path.with_name(f".{path.name}.delete-{secrets.token_hex(6)}")
        os.replace(path, delete_path)
        if not delete_path.is_dir():
            try:
                os.chmod(delete_path, stat.S_IWRITE | stat.S_IREAD)
                delete_path.unlink()
            except FileNotFoundError:
                pass
            return
        # Rust incremental object names can push the absolute path beyond the
        # legacy Win32 MAX_PATH limit. Python's walker needs the verbatim prefix
        # to unlink those entries reliably.
        delete_path = Path("\\\\?\\" + str(delete_path.absolute()))
    for attempt in range(5):
        try:
            if sys.version_info >= (3, 12):
                shutil.rmtree(delete_path, ignore_errors=False, onexc=rm_on_error)
            else:
                shutil.rmtree(delete_path, ignore_errors=False, onerror=rm_on_error)
            return
        except FileNotFoundError:
            return
        except OSError as exc:
            transient = exc.errno in {errno.ENOTEMPTY, errno.EACCES, errno.EBUSY}
            transient = transient or getattr(exc, "winerror", None) in {5, 32, 145}
            if not transient or attempt == 4:
                raise
            # Rescan the entire tree on the next attempt. Antivirus and compiler
            # helpers can create or release files while Windows walks it.
            time.sleep(0.1 * (attempt + 1))


def execv(cmds: list, env=None):
    out = None if force_out or args.verbose > 0 else subprocess.DEVNULL

    return subprocess.run(cmds, stdout=out, env=env, shell=is_windows)


def cmd_out(cmds: list):
    return (
        subprocess.run(
            cmds,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            shell=is_windows,
        )
        .stdout.strip()
        .decode("utf-8")
    )







def clean_elf():
    cargo_toml = Path("tools", "elf-cleaner", "Cargo.toml")
    cmds = ["run", "--release", "--manifest-path", cargo_toml]
    if args.verbose == 0:
        cmds.append("-q")
    elif args.verbose > 1:
        cmds.append("--verbose")
    cmds.append("--")
    cmds.extend(glob.glob("native/out/*/magisk"))
    cmds.extend(glob.glob("native/out/*/mpol"))
    run_cargo(cmds)


def collect_ndk_build():
    for arch in build_abis.keys():
        arch_dir = Path("native", "libs", arch)
        out_dir = Path("native", "out", arch)
        for source in arch_dir.iterdir():
            target = out_dir / source.name
            mv(source, target)


def run_ndk_build(cmds: list[str]):
    os.chdir("native")
    cmds.append("NDK_PROJECT_PATH=.")
    cmds.append("NDK_APPLICATION_MK=src/Application.mk")
    cmds.append(f"APP_ABI={' '.join(build_abis.keys())}")
    cmds.append(f"-j{cpu_count}")
    if args.verbose > 1:
        cmds.append("V=1")
    if not args.release:
        cmds.append("MAGISK_DEBUG=1")
    proc = execv([ndk_build, *cmds])
    if proc.returncode != 0:
        error("Build binary failed!")
    os.chdir("..")


def build_cpp_src(targets: set[str]):
    cmds = []
    clean = False

    if "magisk" in targets:
        cmds.append("B_MAGISK=1")
        clean = True

    if "mpol" in targets:
        cmds.append("B_POLICY=1")
        clean = True

    if "minit" in targets:
        cmds.append("B_PRELOAD=1")

    if cmds:
        run_ndk_build(cmds)
        collect_ndk_build()

    cmds.clear()

    if "minit" in targets:
        cmds.append("B_INIT=1")

    if "mboot" in targets:
        cmds.append("B_BOOT=1")

    if cmds:
        cmds.append("B_CRT0=1")
        run_ndk_build(cmds)
        collect_ndk_build()

    if clean:
        clean_elf()


def _cargo_target_dir() -> Path:
    repo = Path(__file__).absolute().parent
    if not is_windows:
        return repo / "native" / "out" / "rust"
    repo_id = hashlib.sha256(str(repo).lower().encode()).hexdigest()[:12]
    return Path.home() / ".cache" / "cargo-targets" / repo_id


def run_cargo(cmds: list[str]):
    ensure_paths()
    env = os.environ.copy()
    env["PATH"] = f"{rust_sysroot / "bin"}{os.pathsep}{env["PATH"]}"
    env["CARGO_BUILD_RUSTFLAGS"] = f"-Z threads={min(8, cpu_count)}"
    # Override the relative .cargo target-dir with one canonical absolute path.
    # This prevents duplicate build-std artifacts through normal and verbatim
    # Windows path spellings.
    cargo_target = _cargo_target_dir()
    cargo_target.mkdir(mode=0o755, parents=True, exist_ok=True)
    env["CARGO_TARGET_DIR"] = os.path.normpath(str(cargo_target))
    env["CARGO_INCREMENTAL"] = "0"
    env["CARGO_PROFILE_DEV_INCREMENTAL"] = "false"
    env["CARGO_PROFILE_TEST_INCREMENTAL"] = "false"
    host = {
        "windows": "windows-x86_64",
        "linux": "linux-x86_64",
        "darwin": "darwin-x86_64",
    }[os_name]
    tool_bin = ndk_path / "toolchains" / "llvm" / "prebuilt" / host / "bin"
    clang_prefixes = {
        "aarch64-linux-android": "aarch64-linux-android",
        "thumbv7neon-linux-androideabi": "armv7a-linux-androideabi",
        "i686-linux-android": "i686-linux-android",
        "x86_64-linux-android": "x86_64-linux-android",
        "riscv64-linux-android": "riscv64-linux-android",
    }
    driver_ext = ".cmd" if is_windows else ""
    for triple, prefix in clang_prefixes.items():
        key = f"CARGO_TARGET_{triple.upper().replace('-', '_')}_LINKER"
        env[key] = str(tool_bin / f"{prefix}23-clang{driver_ext}")



    if os_name == "darwin":
        env["DYLD_FALLBACK_LIBRARY_PATH"] = str(rust_sysroot / "lib")
    elif os_name == "linux":
        env["LD_LIBRARY_PATH"] = str(rust_sysroot / "lib")
    proc = execv(["cargo", *cmds], env)
    if proc.returncode != 0:
        error(f"Cargo command failed with exit code {proc.returncode}")
    return proc


def build_rust_src(targets: set[str]):
    targets = targets.copy()
    targets = targets & rust_targets
    if not targets:
        return

    os.chdir(Path("native", "src"))


    cmds = ["build", "-p", ""]
    if args.release:
        cmds.append("-r")
        profile = "release"
    else:
        profile = "debug"
    if args.verbose == 0:
        cmds.append("-q")
    elif args.verbose > 1:
        cmds.append("--verbose")

    for triple in build_abis.values():
        cmds.append("--target")
        cmds.append(triple)

    for tgt in targets:
        cargo_tgt = rust_crate_map.get(tgt, tgt)
        cmds[2] = cargo_tgt
        proc = run_cargo(cmds)
        if proc.returncode != 0:
            error("Build binary failed!")

    os.chdir(Path("..", ".."))

    native_out = Path("native", "out")
    rust_out = _cargo_target_dir()
    for arch, triple in build_abis.items():
        arch_out = native_out / arch
        arch_out.mkdir(mode=0o755, exist_ok=True)
        for tgt in targets:
            cargo_tgt = rust_crate_map.get(tgt, tgt)
            source = rust_out / triple / profile / f"lib{cargo_tgt}.a"
            target = arch_out / f"lib{cargo_tgt}-rs.a"
            mv(source, target)


def write_if_diff(file_name: Path, text: str):
    do_write = True
    if file_name.exists():
        with open(file_name, "r", encoding="utf-8") as f:
            orig = f.read()
        do_write = orig != text
    if do_write:
        with open(file_name, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)


def _repository_namespace() -> str:
    namespace = config.get("identityNamespace", "").strip()
    if not namespace:
        namespace = os.environ.get("GITHUB_REPOSITORY", "").strip()
    if not namespace:
        remote = cmd_out(["git", "remote", "get-url", "origin"]).strip()
        match = re.search(r"(?:github\.com[/:])([^/]+/[^/]+?)(?:\.git)?$", remote)
        namespace = match.group(1) if match else remote
    if not namespace:
        namespace = cmd_out(["git", "rev-list", "--max-parents=0", "HEAD"]).strip()
    return namespace.lower()


def _build_identity() -> dict[str, str]:
    enabled = config.get("randomizeBuild", "true").lower() == "true"
    if not enabled:
        if args.release:
            error("Release builds require randomizeBuild=true")
        return {
            "buildId": "ms", "secureDir": config.get("secureDir", "/data/adb"),
            "appPackageName": "io.sevcator.reisenless",
            "classNamespace": "com.topjohnwu.magisk",
            "sharedNamespace": "com.topjohnwu.shared",
            "superuserNamespace": "com.topjohnwu.superuser",
            "widgetNamespace": "com.topjohnwu.widget",
            "vendorNamespace": "com.topjohnwu", "appLabel": "reisenless",
            "appVersionName": config["version"],
            "artifactName": "app-release.apk",
            "brandLong": "rootclient",
            "brandCore": "kernel",
            "brandInject": "inject",
            "brandAuthor": "developer",
            "udongeJavaNamespace": "com.topjohnwu.reisenless.hideapps",
            "appClass": "com.topjohnwu.magisk.core.App",
            "mainActivityClass": "com.topjohnwu.magisk.ui.MainActivity",
            "suRequestActivityClass": "com.topjohnwu.magisk.ui.surequest.SuRequestActivity",
            "webUiActivityClass": "com.topjohnwu.magisk.ui.webui.WebUIActivity",
            "receiverClass": "com.topjohnwu.magisk.core.Receiver",
            "serviceClass": "com.topjohnwu.magisk.core.Service",
            "jobServiceClass": "com.topjohnwu.magisk.core.JobService",
            "backgroundUpdateJobServiceClass": "com.topjohnwu.magisk.core.BackgroundUpdateJobService",
            "providerClass": "com.topjohnwu.magisk.core.Provider",
            "anchorSuffix": "anchor", "providerSuffix": "provider",
            "runtimeSeed": "reisenless-runtime-identity",
            "dataDir": "ms", "dbName": "ms.db", "internalDir": ".ms",
            "socketName": "socket", "policyName": "mpol", "bin32Name": "ms32",
            "busyboxName": "busybox",
            "mainLibName": "magisk", "busyboxLibName": "busybox",
            "policyLibName": "mpol", "initLdLibName": "init-ld",
            "bootLibName": "mboot", "initLibName": "minit",
            "bootctlLibName": "bootctl",
            "ramdiskName": "ms",
            "stubName": "stub.apk", "initLdName": "init-ld",
            "udongeDir": "udonge", "udongeArchive": "udonge.bin",
            "backupConfig": ".cfg", "redirPath": "/data/._init",
            "procDomain": "ms", "fileType": "ms_file",
            "udongeFileType": "udonge_lib_file", "suCache": ".su_cache",
            "tmpDir": "/dev/tmp", "backupPrefix": "/data/ms_backup_",
            "preloadLib": "/dev/preload.so", "preloadPolicy": "/dev/sepolicy",
            "preloadAck": "/dev/ack", "stageScript": "udonge.sh",
        }




    configured_seed = (
        os.environ.get("REISENLESS_IDENTITY_SEED", "").strip()
        or config.get("identitySeed", "").strip()
    )
    public_fallback = "reisenless-build-identity-v1"
    if args.release and not configured_seed:
        error("Release builds require a private identitySeed or REISENLESS_IDENTITY_SEED")
    if args.release and configured_seed == public_fallback:
        error("Refusing to create a release with the public identity seed")
    seed = configured_seed or public_fallback
    namespace = _repository_namespace()

    def token(label: str, minimum: int = 5, maximum: int = 10) -> str:
        digest = hashlib.shake_256(
            f"{seed}\0{namespace}\0{label}".encode("utf-8")
        ).digest(maximum + 1)
        size = minimum + digest[0] % (maximum - minimum + 1)
        return "".join(string.ascii_lowercase[value % 26] for value in digest[1:size + 1])

    explicit_secure_dir = config.get("secureDir", "")
    randomize_secure = config.get("randomizeSecureDir", "true").lower() == "true"
    if args.release and not randomize_secure:
        error("Release builds require randomizeSecureDir=true")
    secure_dir = (
        f"/data/.{token('secure-dir', 4, 4)}"
        if randomize_secure or not explicit_secure_dir
        else explicit_secure_dir
    )
    if args.release and secure_dir in {"/data/adb", "/data/ms", "/data/.magisk"}:
        error("Refusing to create a release with a public secure directory")
    proc = token("policy-domain", 6, 9)
    file_type = token("policy-file", 6, 9)
    udonge_type = token("policy-udonge", 6, 9)
    main_binary = token("main-binary", 5, 8)
    class_namespace = "com." + token("class-owner", 9, 9) \
        + "." + token("class-package", 6, 6)

    def component(source: str, label: str) -> str:
        suffix_size = len(source) - len("com.topjohnwu.magisk") - 1
        return class_namespace + "." + token(label, suffix_size, suffix_size)

    return {
        "buildId": main_binary,
        "appPackageName": "com." + token("app-package-owner", 6, 9)
            + "." + token("app-package", 6, 10),
        # These replace source namespaces byte-for-byte in DEX and binary XML.
        # Equal encoded lengths avoid rebuilding Android binary string pools.
        "classNamespace": class_namespace,
        "sharedNamespace": "com." + token("shared-owner", 9, 9)
            + "." + token("shared-package", 6, 6),
        "superuserNamespace": "com." + token("superuser-owner", 9, 9)
            + "." + token("superuser-package", 9, 9),
        "widgetNamespace": "com." + token("widget-owner", 9, 9)
            + "." + token("widget-package", 6, 6),
        "vendorNamespace": "com." + token("vendor-namespace", 9, 9),
        # Keep the product name stable in user-facing Android surfaces. The
        # package, classes, binaries, and runtime paths remain randomized.
        "appLabel": "Reisenless",
        "appVersionName": token("app-version", 8, 12),
        "artifactName": token("release-artifact", 10, 16) + ".apk",
        "brandLong": token("visible-brand-long", 10, 10),
        "brandCore": token("visible-brand-core", 6, 6),
        "brandInject": token("visible-brand-inject", 6, 6),
        "brandAuthor": token("visible-brand-author", 9, 9),
        "udongeJavaNamespace": "com." + token("hide-owner", 9, 9)
            + "." + token("hide-core", 10, 10)
            + "." + token("hide-package", 8, 8),
        "appClass": component("com.topjohnwu.magisk.core.App", "component-app"),
        "mainActivityClass": component(
            "com.topjohnwu.magisk.ui.MainActivity", "component-main-activity"
        ),
        "suRequestActivityClass": component(
            "com.topjohnwu.magisk.ui.surequest.SuRequestActivity",
            "component-su-request-activity",
        ),
        "webUiActivityClass": component(
            "com.topjohnwu.magisk.ui.webui.WebUIActivity", "component-web-ui-activity"
        ),
        "receiverClass": component(
            "com.topjohnwu.magisk.core.Receiver", "component-receiver"
        ),
        "serviceClass": component(
            "com.topjohnwu.magisk.core.Service", "component-service"
        ),
        "jobServiceClass": component(
            "com.topjohnwu.magisk.core.JobService", "component-job-service"
        ),
        "backgroundUpdateJobServiceClass": component(
            "com.topjohnwu.magisk.core.BackgroundUpdateJobService",
            "component-background-update-job-service",
        ),
        "providerClass": component(
            "com.topjohnwu.magisk.core.Provider", "component-provider"
        ),
        "anchorSuffix": token("anchor-suffix", 6, 10),
        "providerSuffix": token("provider-suffix", 6, 10),
        "runtimeSeed": token("runtime-seed", 32, 32),
        "secureDir": secure_dir,
        "dataDir": "." + token("data-bin", 6, 10),
        "dbName": "." + token("database", 6, 10),
        "internalDir": "." + token("tmpfs-internal", 6, 10),
        "socketName": token("daemon-socket", 6, 10),
        "policyName": token("policy-binary", 5, 9),
        "bin32Name": token("bin32-databin", 5, 9),
        "busyboxName": token("toolbox-binary", 6, 10),
        # APK native-library entry names are public to every package that can
        # inspect the installed APK. Keep their identities build-generated too.
        "mainLibName": token("packaged-main-binary", 6, 10),
        "busyboxLibName": token("packaged-toolbox-binary", 6, 10),
        "policyLibName": token("packaged-policy-binary", 6, 10),
        "initLdLibName": token("packaged-init-loader", 6, 10),
        "bootLibName": token("packaged-boot-tool", 6, 10),
        "initLibName": token("packaged-init-tool", 6, 10),
        "bootctlLibName": token("packaged-bootctl-tool", 6, 10),

        "ramdiskName": main_binary,
        "stubName": token("stub-apk", 6, 10) + ".apk",
        "initLdName": token("init-loader", 6, 10),
        "udongeDir": "." + token("udonge-root", 3, 3),
        "udongeArchive": token("udonge-archive", 7, 11) + ".bin",
        "backupConfig": "." + token("backup-config", 6, 10),

        "redirPath": "/data/." + token("init-redirect", 7, 9),
        "procDomain": proc + "_d", "fileType": file_type + "_f",
        "udongeFileType": udonge_type + "_f",
        "suCache": "." + token("package-cache", 6, 10),
        "tmpDir": "/dev/." + token("installer-temp", 6, 10),
        "backupPrefix": "/data/." + token("backup-prefix", 6, 10) + "_",
        "preloadLib": "/dev/." + token("preload-lib", 6, 10) + ".so",
        "preloadPolicy": "/dev/." + token("preload-policy", 6, 10),
        "preloadAck": "/dev/." + token("preload-ack", 6, 10),
        "stageScript": "." + token("udonge-stage", 6, 10) + ".sh",
    }


def _build_flag_metadata():
    return {
        "version": config["version"],
        "versionCode": config["versionCode"],
        "release": args.release,
        "randomizeBuild": config.get("randomizeBuild", "true").lower() == "true",
        "identityNamespace": _repository_namespace(),
        "identity": _build_identity(),
    }


def _validate_generated_flags(action: str):
    native_gen_path = Path("native", "out", "generated")
    flags_h = native_gen_path / "flags.h"
    flags_rs = native_gen_path / "flags.rs"
    metadata_file = native_gen_path / "flags.json"
    if not flags_h.exists() or not flags_rs.exists() or not metadata_file.exists():
        error(f"Native build identity is missing. {action}")

    try:
        metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        error(f"Native build identity is invalid. {action}")

    if metadata != _build_flag_metadata():
        error(f"Native build identity does not match this configuration. {action}")

    build_id = _read_generated_flag("BUILD_ID", "")
    secure_dir = _read_generated_flag("BUILD_SECURE_DIR", "")
    if not re.fullmatch(r"[a-z]{2,16}", build_id) or not secure_dir:
        error(f"Native build identity is invalid. {action}")


def _validate_legacy_identity_config():
    values = {
        "legacySecureDir": config.get("legacySecureDir", "").strip(),
        "legacyDbName": config.get("legacyDbName", "").strip(),
        "legacyUdongeDir": config.get("legacyUdongeDir", "").strip(),
        "legacyBackupConfig": config.get("legacyBackupConfig", "").strip(),
    }
    if not any(values.values()):
        return
    if not all(values.values()):
        error("Legacy migration requires secure dir, database, runtime dir, and boot marker")
    if not re.fullmatch(r"/data/\.[a-z]{3,16}", values["legacySecureDir"]):
        error("Invalid legacySecureDir")
    for key in ("legacyDbName", "legacyUdongeDir", "legacyBackupConfig"):
        if not re.fullmatch(r"\.[a-z]{2,16}", values[key]):
            error(f"Invalid {key}")
    if values["legacyBackupConfig"] == _build_identity()["backupConfig"]:
        error("Legacy and current boot markers must be different")


def _escape_flag_string(value: str) -> str:
    return (
        value.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\t", "\\t")
    )


def dump_flag_header():
    identity = _build_identity()
    build_id = identity["buildId"]
    secure_dir = identity["secureDir"]
    if (
        not re.fullmatch(r"/data/[A-Za-z0-9._/-]+", secure_dir)
        or "/../" in f"{secure_dir}/"
        or secure_dir.endswith("/")
    ):
        error(f'Invalid secureDir: "{secure_dir}"')


    spoof_fp = config.get("spoofFingerprint", "")
    spoof_mfr = config.get("spoofManufacturer", "")
    spoof_model = config.get("spoofModel", "")
    spoof_product = config.get("spoofProduct", "")
    spoof_device = config.get("spoofDevice", "")
    spoof_bid = config.get("spoofBuildId", "")
    spoof_patch = config.get("spoofSecurityPatch", "")
    spoof_ver = config.get("spoofVersionRelease", "")

    version = _escape_flag_string(config["version"])

    flag_txt = "#pragma once\n"
    flag_txt += f'#define MAGISK_VERSION      "{version}"\n'
    flag_txt += f'#define MAGISK_VER_CODE     {config["versionCode"]}\n'
    flag_txt += f"#define MAGISK_DEBUG        {0 if args.release else 1}\n"
    flag_txt += f'#define BUILD_ID            "{build_id}"\n'
    flag_txt += f'#define BUILD_SECURE_DIR    "{secure_dir}"\n'
    identity_flags = {
        "appPackageName": "BUILD_APP_PACKAGE_NAME",
        "providerSuffix": "BUILD_PROVIDER_SUFFIX",
        "dataDir": "BUILD_DATA_DIR", "dbName": "BUILD_DB_NAME",
        "internalDir": "BUILD_INTERNAL_DIR", "socketName": "BUILD_SOCKET_NAME",
        "runtimeSeed": "BUILD_RUNTIME_SEED",
        "policyName": "BUILD_POLICY_NAME", "bin32Name": "BUILD_BIN32_NAME",
        "busyboxName": "BUILD_BUSYBOX_NAME",
        "mainLibName": "BUILD_MAIN_LIB_NAME",
        "busyboxLibName": "BUILD_BUSYBOX_LIB_NAME",
        "policyLibName": "BUILD_POLICY_LIB_NAME",
        "initLdLibName": "BUILD_INIT_LD_LIB_NAME",
        "bootLibName": "BUILD_BOOT_LIB_NAME",
        "initLibName": "BUILD_INIT_LIB_NAME",
        "bootctlLibName": "BUILD_BOOTCTL_LIB_NAME",
        "ramdiskName": "BUILD_RAMDISK_NAME",
        "stubName": "BUILD_STUB_NAME", "initLdName": "BUILD_INIT_LD_NAME",
        "udongeDir": "BUILD_UDONGE_DIR", "udongeArchive": "BUILD_UDONGE_ARCHIVE",
        "backupConfig": "BUILD_BACKUP_CONFIG", "redirPath": "BUILD_REDIR_PATH",
        "procDomain": "BUILD_PROC_DOMAIN", "fileType": "BUILD_FILE_TYPE",
        "udongeFileType": "BUILD_UDONGE_FILE_TYPE", "suCache": "BUILD_SU_CACHE",
        "tmpDir": "BUILD_TMP_DIR", "backupPrefix": "BUILD_BACKUP_PREFIX",
        "preloadLib": "BUILD_PRELOAD_LIB", "preloadPolicy": "BUILD_PRELOAD_POLICY",
        "preloadAck": "BUILD_PRELOAD_ACK", "stageScript": "BUILD_STAGE_SCRIPT",
    }
    for key, macro in identity_flags.items():
        flag_txt += f'#define {macro:<24} "{identity[key]}"\n'

    native_gen_path = Path("native", "out", "generated")
    native_gen_path.mkdir(mode=0o755, parents=True, exist_ok=True)
    write_if_diff(native_gen_path / "flags.h", flag_txt)

    rust_flag_txt = f'pub const MAGISK_VERSION: &str = "{version}";\n'
    rust_flag_txt += f'pub const MAGISK_VER_CODE: i32 = {config["versionCode"]};\n'
    rust_flag_txt += f'pub const BUILD_ID: &str = "{build_id}";\n'
    rust_flag_txt += f'pub const BUILD_SECURE_DIR: &str = "{secure_dir}";\n'
    for key, const_name in identity_flags.items():
        rust_flag_txt += f'pub const {const_name}: &str = "{identity[key]}";\n'
    write_if_diff(native_gen_path / "flags.rs", rust_flag_txt)
    write_if_diff(
        native_gen_path / "flags.json",
        json.dumps(_build_flag_metadata(), indent=2, sort_keys=True) + "\n",
    )


def ensure_toolchain():
    ensure_paths()


    try:
        with open(Path(ndk_path, "ONDK_VERSION"), "r") as ondk_ver:
            assert ondk_ver.read().strip(" \t\r\n") == ondk_version
    except:
        error('Unmatched NDK. Please install/upgrade NDK with "build.py ndk"')

    if sccache := shutil.which("sccache"):
        os.environ["RUSTC_WRAPPER"] = sccache
        os.environ["NDK_CCACHE"] = sccache
        os.environ["CARGO_INCREMENTAL"] = "0"
    if ccache := shutil.which("ccache"):
        os.environ["NDK_CCACHE"] = ccache


def build_native():
    ensure_toolchain()

    if "targets" not in vars(args) or not args.targets:
        targets = default_targets
    else:
        targets = set(args.targets) & support_targets
        if not targets:
            return

    header("* Building: " + " ".join(targets))




    if default_targets.issubset(targets):
        dump_flag_header()
    else:
        flags_h = Path("native", "out", "generated", "flags.h")
        if flags_h.exists():
            _validate_generated_flags(
                "Run a full native build before changing build configuration."
            )
        else:
            dump_flag_header()
    build_rust_src(targets)
    build_cpp_src(targets)







def find_jdk():
    env = os.environ.copy()
    known_jdk_paths = [
        env.get("JAVA_HOME"),
        Path.home() / "AppData" / "Roaming" / "PrismLauncher" / "java" / "java-runtime-epsilon",
    ]
    for jdk_candidate in known_jdk_paths:
        if jdk_candidate:
            jdk_path = Path(jdk_candidate)
            javac_candidate = jdk_path / "bin" / ("javac.exe" if platform.system().lower() == "windows" else "javac")
            if javac_candidate.exists():
                env["PATH"] = f'{jdk_path / "bin"}{os.pathsep}{env["PATH"]}'
                env["JAVA_HOME"] = str(jdk_path)
                env["JDK_HOME"] = str(jdk_path)
                break

    if "ANDROID_STUDIO" in env:
        studio = env["ANDROID_STUDIO"]
        jbr = Path(studio, "jbr", "bin")
        if not jbr.exists():
            jbr = Path(studio, "Contents", "jbr", "Contents", "Home", "bin")
        if jbr.exists():
            env["PATH"] = f'{jbr}{os.pathsep}{env["PATH"]}'
            env["JAVA_HOME"] = str(jbr.parent)
            env["JDK_HOME"] = str(jbr.parent)

    no_jdk = False
    try:
        proc = subprocess.run(
            "javac -version",
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            env=env,
            shell=True,
            text=True,
        )
        no_jdk = proc.returncode != 0 or not proc.stdout.strip().startswith("javac")
    except FileNotFoundError:
        no_jdk = True

    if no_jdk:
        error(
            "Please set Android Studio's path to environment variable ANDROID_STUDIO,\n"
            + "or install JDK and make sure 'javac' is available in PATH"
        )

    return env


def _keystore_certificate_sha256(
    store: Path, password: str, alias: str, env: dict[str, str]
) -> str:
    keytool = shutil.which("keytool", path=env.get("PATH"))
    if not keytool:
        error("JDK 21 keytool is required to validate the manager signing key")
    with tempfile.TemporaryDirectory(prefix="manager-cert-") as temp_dir:
        certificate = Path(temp_dir, "certificate.der")
        proc = subprocess.run(
            [
                keytool,
                "-exportcert",
                "-keystore",
                str(store),
                "-storepass",
                password,
                "-alias",
                alias,
                "-file",
                str(certificate),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            env=env,
            shell=is_windows,
            text=True,
        )
        if proc.returncode != 0 or not certificate.is_file():
            error(f"Unable to read manager signing certificate: {proc.stdout}")
        return hashlib.sha256(certificate.read_bytes()).hexdigest()


def _generate_local_signing_secrets(
    secrets_file: Path, store: Path, env: dict[str, str]
) -> dict[str, str]:
    keytool = shutil.which("keytool", path=env.get("PATH"))
    if not keytool:
        error("JDK 21 keytool is required to generate the manager signing key")

    alphabet = string.ascii_letters + string.digits
    password = "".join(secrets.choice(alphabet) for _ in range(64))
    alias = "release-" + _build_identity()["buildId"]
    store.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    proc = subprocess.run(
        [
            keytool,
            "-genkeypair",
            "-storetype",
            "PKCS12",
            "-keystore",
            str(store),
            "-storepass",
            password,
            "-keypass",
            password,
            "-alias",
            alias,
            "-keyalg",
            "RSA",
            "-keysize",
            "4096",
            "-validity",
            "36500",
            "-dname",
            f"CN={_build_identity()['appLabel']},O={_build_identity()['brandLong']}",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        env=env,
        shell=is_windows,
        text=True,
    )
    if proc.returncode != 0 or not store.is_file():
        error(f"Unable to generate manager signing key: {proc.stdout}")

    cert_sha256 = _keystore_certificate_sha256(store, password, alias, env)
    values = {
        "REISENLESS_KEYSTORE_BASE64": base64.b64encode(
            store.read_bytes()
        ).decode("ascii"),
        "REISENLESS_KEYSTORE_PASSWORD": password,
        "REISENLESS_KEY_ALIAS": alias,
        "REISENLESS_KEY_PASSWORD": password,
        "REISENLESS_CERT_SHA256": cert_sha256.upper(),
    }
    secrets_file.write_text(json.dumps(values, indent=2) + "\n", encoding="utf-8")
    header(f"Generated private manager signing key: {store}")
    return values


def _prepare_signing_config(env: dict[str, str]) -> dict[str, str]:
    global signing_config
    if signing_config is not None:
        return signing_config

    explicit_names = ("keyStore", "keyStorePass", "keyAlias", "keyPass")
    explicit = {name: config.get(name, "").strip() for name in explicit_names}
    if any(explicit.values()) and not all(explicit.values()):
        error("Signing config requires keyStore, keyStorePass, keyAlias, and keyPass")

    expected_digest = ""
    if all(explicit.values()):
        store = Path(explicit["keyStore"]).expanduser().resolve()
        store_password = explicit["keyStorePass"]
        alias = explicit["keyAlias"]
        key_password = explicit["keyPass"]
    else:
        env_values = {name: os.environ.get(name, "").strip() for name in SIGNING_SECRET_KEYS}
        present = [name for name, value in env_values.items() if value]
        if present and len(present) != len(SIGNING_SECRET_KEYS):
            error("All four REISENLESS_KEYSTORE_* GitHub secrets are required")

        signing_dir = Path(".private", "manager-signing")
        secrets_file = signing_dir / "secrets.json"
        store = signing_dir / "release.p12"
        if len(present) == len(SIGNING_SECRET_KEYS):
            values = env_values
            expected_digest = os.environ.get("REISENLESS_CERT_SHA256", "").strip()
            store = signing_dir / "environment-release.p12"
        elif secrets_file.is_file():
            try:
                values = json.loads(secrets_file.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as exc:
                error(f"Invalid local signing secrets: {exc}")
            missing = [name for name in SIGNING_SECRET_KEYS if not values.get(name)]
            if missing:
                error("Local signing secrets are incomplete: " + ", ".join(missing))
            expected_digest = str(values.get("REISENLESS_CERT_SHA256", "")).strip()
        elif os.environ.get("CI", "").lower() == "true":
            error(
                "Release signing secrets are not configured. Add the four "
                "REISENLESS_KEYSTORE_* repository secrets."
            )
        else:
            values = _generate_local_signing_secrets(secrets_file, store, env)
            expected_digest = values["REISENLESS_CERT_SHA256"]

        try:
            raw_store = base64.b64decode(
                values["REISENLESS_KEYSTORE_BASE64"], validate=True
            )
        except (binascii.Error, ValueError, TypeError) as exc:
            error(f"Invalid manager keystore encoding: {exc}")
        store.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        if not store.is_file() or store.read_bytes() != raw_store:
            store.write_bytes(raw_store)
        store = store.resolve()
        store_password = values["REISENLESS_KEYSTORE_PASSWORD"]
        alias = values["REISENLESS_KEY_ALIAS"]
        key_password = values["REISENLESS_KEY_PASSWORD"]

    if not store.is_file():
        error(f"Manager signing keystore does not exist: {store}")
    actual_digest = _keystore_certificate_sha256(store, store_password, alias, env)
    if expected_digest and actual_digest.lower() != expected_digest.lower():
        error("Manager signing certificate does not match its configured digest")
    if actual_digest.lower() == ANDROID_DEBUG_CERT_SHA256:
        error("Refusing to build the manager with the public Android debug signing key")

    signing_config = {
        "keyStore": store.as_posix(),
        "keyStorePass": store_password,
        "keyAlias": alias,
        "keyPass": key_password,
        "certificateSha256": actual_digest,
    }
    return signing_config


def _validate_packaged_signing(apk: Path, expected_digest: str, env: dict[str, str]):
    candidates = sorted((sdk_path / "build-tools").glob("*/apksigner*"), reverse=True)
    apksigner = next((path for path in candidates if path.suffix in {"", ".bat"}), None)
    if apksigner is None:
        error("Android apksigner is required to validate the manager APK")
    proc = subprocess.run(
        [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        env=env,
        shell=is_windows,
        text=True,
    )
    digests = {
        value.lower()
        for value in re.findall(
            r"certificate SHA-256 digest:\s*([0-9a-f]{64})", proc.stdout, re.I
        )
    }
    if proc.returncode != 0 or expected_digest.lower() not in digests:
        error(f"APK is not signed by the configured private key: {apk}\n{proc.stdout}")
    if len(digests) != 1:
        error(f"APK must contain exactly one signing identity: {apk}")
    if "Verified using v1 scheme (JAR signing): true" not in proc.stdout or \
            "Verified using v2 scheme (APK Signature Scheme v2): true" not in proc.stdout:
        error(f"APK must verify with its enabled v1 and v2 signature schemes: {apk}")
    if ANDROID_DEBUG_CERT_SHA256 in digests:
        error(f"APK unexpectedly contains the public Android debug certificate: {apk}")
    if re.search(r"reisenless|magisk|zygisk|topjohnwu", proc.stdout, re.I):
        error(f"Signing metadata exposes a project identity: {apk}")
    header(f"Verified private signing identity: {expected_digest}")


def _validate_embedded_trust_anchor(
    apk: Path, expected_digest: str, env: dict[str, str]
):
    stub_name = _build_identity()["stubName"]
    try:
        with ZipFile(apk) as archive:
            stub = archive.read(f"assets/{stub_name}")
    except (BadZipFile, KeyError, OSError) as exc:
        error(f"Unable to read embedded manager trust anchor from {apk}: {exc}")
    with tempfile.TemporaryDirectory(prefix="reisenless-stub-") as temp_dir:
        stub_apk = Path(temp_dir, stub_name)
        stub_apk.write_bytes(stub)
        _validate_packaged_signing(stub_apk, expected_digest, env)
        _validate_native_certificates((apk, stub_apk), expected_digest, env)


def _validate_native_certificates(apks: tuple[Path, ...], expected_digest: str,
                                  env: dict[str, str]):
    """Prove the actual daemon parser accepts each signed release artifact.

    This supplements, never replaces, apksigner cryptographic verification.
    Synthetic parser tests alone missed an apksig signed-data format change.
    """
    package_source = Path("native", "src", "core", "package.rs").read_text(encoding="utf-8")
    if "info.trusted_cert = read_certificate(&mut fd, -1);" not in package_source:
        error("Daemon must parse the embedded stub trust anchor independently of manager versionCode")
    ensure_paths()
    env = env.copy()
    # The host MinGW linker also needs the bundled Rust runtime DLLs on Windows.
    env["PATH"] = f"{rust_sysroot / 'bin'}{os.pathsep}{env.get('PATH', '')}"
    with tempfile.TemporaryDirectory(prefix="native-cert-check-") as temp_dir:
        checker = Path(temp_dir, f"check-apk-certificate{EXE_EXT}")
        compiler = rust_sysroot / "bin" / f"rustc{EXE_EXT}"
        source = Path("scripts", "check_apk_certificate.rs").absolute()
        try:
            compiled = subprocess.run(
                [str(compiler), "--edition=2024", str(source), "-o", str(checker)],
                env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                timeout=120,
            )
            if compiled.returncode != 0:
                error("Native certificate checker compilation failed:\n"
                      + compiled.stdout.decode(errors="replace"))
            stub_name = _build_identity()["stubName"]
            for artifact in apks:
                min_version = 0 if artifact.name == stub_name or "stub" in artifact.name.lower() else int(config["versionCode"])
                parsed = subprocess.run(
                    [str(checker), str(min_version), str(artifact.absolute())],
                    env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    timeout=30,
                )
                if parsed.returncode != 0:
                    error(f"Daemon cannot parse release certificate: {artifact}\n"
                          + parsed.stderr.decode(errors="replace"))
                if hashlib.sha256(parsed.stdout).hexdigest() != expected_digest.lower():
                    error(f"Daemon certificate does not match the trusted signer: {artifact}")
        except (OSError, subprocess.TimeoutExpired) as exc:
            error(f"Native certificate verification could not complete: {exc}")
    header("Verified daemon certificate parser against manager and embedded trust anchor")


def _read_generated_flag(name: str, fallback: str) -> str:
    flags_h = Path("native", "out", "generated", "flags.h")
    if flags_h.exists():
        for line in flags_h.read_text(encoding="utf-8").splitlines():
            match = re.fullmatch(rf'#define\s+{re.escape(name)}\s+"([^"]*)"', line)
            if match:
                return match.group(1)
    return fallback


def _latest_android_tool(path: Path) -> Path:
    def key(item: Path):
        return tuple(int(part) for part in re.findall(r"\d+", item.name))

    entries = [item for item in path.iterdir() if item.is_dir()]
    if not entries:
        error(f"No Android SDK tools found in {path}")
    return max(entries, key=key)


def _zip_bytes(zf: ZipFile, name: str, data: bytes, mode: int = 0o644):
    info = ZipInfo(name, (1980, 1, 1, 0, 0, 0))
    info.compress_type = ZIP_DEFLATED
    info.external_attr = (stat.S_IFREG | mode) << 16
    zf.writestr(info, data)


def _patch_tee_dex(data: bytes, udonge_root: str) -> bytes:
    def fit_path(path: str, size: int, pad_after: str | None = None) -> bytes:
        encoded = path.encode()
        if len(encoded) > size:
            error(f"Generated Udonge path is too long for TEE DEX: {path}")




        if pad_after is not None:
            prefix = (pad_after.rstrip("/") + "/").encode()
            if not encoded.startswith(prefix):
                error(f"Invalid Udonge DEX padding prefix: {pad_after}")
            split = len(prefix)
        else:
            split = encoded.find(b"/", len(b"/data/"))
            if split < 0:
                split = len(encoded)
        return encoded[:split] + b"/" * (size - len(encoded)) + encoded[split:]

    state_source = b"/data/adb/tricky_store"
    library_source = b"/data/adb/modules/tricky_store/libcertgen.so"
    replacements = (
        (state_source, fit_path(f"{udonge_root}/state", len(state_source))),
        (
            library_source,
            fit_path(
                f"{udonge_root}/tee-runtime/libcertgen.so",
                len(library_source),
                udonge_root,
            ),
        ),
    )
    for source, target in replacements:
        if len(source) != len(target):
            error("Udonge TEE path replacements must preserve DEX string lengths")
        if source not in data:
            error(f"Udonge TEE path is missing from classes.dex: {source.decode()}")
        data = data.replace(source, target)

    patched = bytearray(data)
    patched[12:32] = hashlib.sha1(patched[32:]).digest()
    patched[8:12] = struct.pack("<I", zlib.adler32(patched[12:]) & 0xFFFFFFFF)
    return bytes(patched)


def _patch_hideapps_dex(data: bytes, namespace: str) -> bytes:
    source = "com.topjohnwu.reisenless.hideapps"
    if len(source) != len(namespace):
        error("Generated package-filter namespace has an invalid length")
    patched = bytearray(data)
    replacements = (
        (source.encode(), namespace.encode()),
        (source.replace(".", "/").encode(), namespace.replace(".", "/").encode()),
        (source.replace(".", "-").encode(), namespace.replace(".", "-").encode()),
    )
    changed = 0
    for old, new in replacements:
        changed += patched.count(old)
        patched[:] = patched.replace(old, new)
    if changed == 0:
        error("Package-filter DEX namespace marker is missing")
    patched[12:32] = hashlib.sha1(patched[32:]).digest()
    patched[8:12] = struct.pack("<I", zlib.adler32(patched[12:]) & 0xFFFFFFFF)
    return bytes(patched)


def build_udonge():
    global udonge_built
    if udonge_built:
        return

    ensure_paths()
    header("* Building the built-in Udonge payload")

    host = {
        "windows": "windows-x86_64",
        "linux": "linux-x86_64",
        "darwin": "darwin-x86_64",
    }[os_name]
    tool_bin = ndk_path / "toolchains" / "llvm" / "prebuilt" / host / "bin"
    work = config["outdir"] / ".udonge-build"
    if work.exists():
        rm_rf(work)
    zygisk_out = work / "zygisk"
    zygisk_out.mkdir(parents=True)

    java_out = work / "java"
    java_out.mkdir(parents=True)
    android_jar = _latest_android_tool(sdk_path / "platforms") / "android.jar"
    build_tools = _latest_android_tool(sdk_path / "build-tools")
    d8 = build_tools / ("d8.bat" if is_windows else "d8")
    java_source = Path(
        "udonge", "java", "com", "topjohnwu", "reisenless", "hideapps",
        "PackageManagerProxy.java",
    )
    env = find_jdk()
    proc = execv(
        [
            "javac",
            "-source", "8",
            "-target", "8",
            "-classpath", android_jar,
            "-d", java_out,
            java_source,
        ],
        env=env,
    )
    if proc.returncode != 0:
        error("Build Hide Apps Java runtime failed!")
    class_files = sorted(java_out.rglob("*.class"))
    proc = execv(
        [d8, "--min-api", "26", "--output", java_out, *class_files],
        env=env,
    )
    if proc.returncode != 0:
        error("Build Hide Apps DEX runtime failed!")
    hideapps_dex = java_out / "classes.dex"
    hideapps_dex.write_bytes(
        _patch_hideapps_dex(
            hideapps_dex.read_bytes(),
            _build_identity()["udongeJavaNamespace"],
        )
    )

    native_sources = [
        Path("udonge", "native", name)
        for name in ("main.cpp", "config.cpp", "hideapps.cpp", "hooks.cpp", "spoof.cpp")
    ]
    native_sources.extend([
        Path("native", "src", "external", "lsplt", "lsplt", "src", "main", "jni", "elf_util.cc"),
        Path("native", "src", "external", "lsplt", "lsplt", "src", "main", "jni", "lsplt.cc"),
    ])
    api = "23"
    identity = _build_identity()
    secure_dir = identity["secureDir"].rstrip("/")
    udonge_root = f'{secure_dir}/{identity["udongeDir"]}'
    drivers = {
        "armeabi-v7a": "armv7a-linux-androideabi",
        "arm64-v8a": "aarch64-linux-android",
        "x86": "i686-linux-android",
        "x86_64": "x86_64-linux-android",
    }
    for abi in build_abis:
        if abi == "riscv64":
            continue
        driver_ext = ".cmd" if is_windows else ""
        clang = tool_bin / f"{drivers[abi]}{api}-clang++{driver_ext}"
        if not clang.exists():
            error(f"Udonge compiler is missing: {clang}")
        output = zygisk_out / f"{abi}.so"
        cmd = [
            clang,
            "-std=c++20",
            "-Oz",
            "-fPIC",
            "-fvisibility=hidden",
            "-fno-exceptions",
            "-fno-rtti",
            "-ffunction-sections",
            "-fdata-sections",
            "-static-libstdc++",
            "-shared",
            "-Wl,--gc-sections",
            "-Wl,--build-id=none",
            f'-DUDONGE_ROOT="{udonge_root}"',
            f'-DHIDEAPPS_CLASS_NAME="{identity["udongeJavaNamespace"]}"',
            *native_sources,
            "-ldl",
            "-llog",
            "-o",
            output,
        ]
        proc = execv(cmd)
        if proc.returncode != 0:
            error(f"Build Udonge for {abi} failed!")

    output = config["outdir"] / "udonge.bin"
    payload = Path("udonge", "payload")
    entries: list[tuple[str, bytes, int]] = [
        ("version", f"{config['version']}\n".encode(), 0o644),
        ("hideapps.dex", hideapps_dex.read_bytes(), 0o644),
    ]
    for lib in sorted(zygisk_out.glob("*.so")):
        entries.append((f"zygisk/{lib.name}", lib.read_bytes(), 0o644))
    for source in sorted(item for item in payload.rglob("*") if item.is_file()):
        name = source.relative_to(payload).as_posix()
        data = source.read_bytes()
        if name == "tee/classes.dex":
            data = _patch_tee_dex(data, udonge_root)
        if name.endswith(".sh") or name in {"tee/daemon"} or name.endswith("/inject") or name.endswith("/supervisor"):
            mode = 0o700
        else:
            mode = 0o600 if name.startswith("defaults/") else 0o644
        if name.endswith(".sh"):
            data = data.replace(b"root=/data/adb/udonge", f"root={udonge_root}".encode())
            data = data.replace(b"udonge_lib_file", identity["udongeFileType"].encode())
        entries.append((name, data, mode))

    payload_hash = hashlib.sha256()
    for name, data, mode in entries:
        payload_hash.update(name.encode())
        payload_hash.update(b"\0")
        payload_hash.update(mode.to_bytes(4, "little"))
        payload_hash.update(len(data).to_bytes(8, "little"))
        payload_hash.update(data)
    payload_id = f"{payload_hash.hexdigest()}\n".encode()

    with ZipFile(output, "w") as zf:
        _zip_bytes(zf, "payload.id", payload_id)
        for name, data, mode in entries:
            _zip_bytes(zf, name, data, mode)

    rm_rf(work)
    udonge_built = True
    header(f"Output: {output}")


def _validate_packaged_udonge(apk: Path):
    identity = _build_identity()
    archive_name = identity["udongeArchive"]
    archive_path = f"assets/{archive_name}"
    payload = config["outdir"] / "udonge.bin"
    if not payload.is_file():
        error("Build the Udonge payload before packaging the app")

    required_script_markers = {
        "META-INF/com/google/android/update-binary":
            '$BBBIN unzip -o "$3" "assets/*" "lib/*" "META-INF/com/google/*" -d $INSTALLER',
        "META-INF/com/google/android/updater-script":
            '[ -f "$BINDIR/$PACKAGED_BUSYBOX_LIB" ] && mv "$BINDIR/$PACKAGED_BUSYBOX_LIB" "$BINDIR/$BUSYBOX_NAME"',
        "assets/util_functions.sh":
            'mkdir -p "${SECURE_DIR}" 2>/dev/null',
        "assets/boot_patch.sh":
            'overlay.d/sbin/$UDONGE_ARCHIVE.xz',
    }
    try:
        with ZipFile(apk) as zf:
            corrupt = zf.testzip()
            if corrupt:
                error(f"Corrupt APK entry: {corrupt}")
            packaged = zf.read(archive_path)
            expected = payload.read_bytes()
            if packaged != expected:
                error(f"Packaged Udonge payload does not match {payload}")
            if archive_name != "udonge.bin" and "assets/udonge.bin" in zf.namelist():
                error("Unrandomized Udonge asset leaked into the APK")
            for script, marker in required_script_markers.items():
                contents = zf.read(script).decode("utf-8")
                if marker not in contents:
                    error(f"Udonge installer handoff is missing from {script}")
            bootstrap = zf.read("META-INF/com/google/android/update-binary").decode("utf-8")
            if f'-x "lib/*/lib{identity["busyboxLibName"]}.so"' in bootstrap:
                error("Installer bootstrap excludes the packaged BusyBox handoff")
            utilities = zf.read("assets/util_functions.sh").decode("utf-8")
            if '/data/local/tmp/*)' not in utilities or \
                    'staged patched image without writing the boot partition' not in utilities:
                error("Safe patch-only boot staging is missing from the installer")
            legacy_marker = config.get("legacyBackupConfig", "").strip()
            if legacy_marker:
                boot_patch = zf.read("assets/boot_patch.sh").decode("utf-8")
                if f"LEGACY_BACKUP_CONFIG='{legacy_marker}'" not in utilities \
                        or 'exists .backup/$LEGACY_BACKUP_CONFIG' not in boot_patch \
                        or '[[:space:]]\\.backup/\\(\\.[[:alnum:]_-]*\\)' not in boot_patch \
                        or '"rm .backup/$LEGACY_BACKUP_CONFIG" "restore"' not in boot_patch:
                    error("Legacy randomized boot marker is missing from the installer")
    except (BadZipFile, KeyError, OSError, UnicodeDecodeError) as exc:
        error(f"Invalid packaged Udonge payload in {apk}: {exc}")

    header(f"Verified randomized Udonge payload: {archive_path}")


def _validate_packaged_binary_identity(apk: Path):
    identity = _build_identity()
    aliases = {
        "magisk": identity["mainLibName"],
        "busybox": identity["busyboxLibName"],
        "mpol": identity["policyLibName"],
        "init-ld": identity["initLdLibName"],
        "mboot": identity["bootLibName"],
        "minit": identity["initLibName"],
        "bootctl": identity["bootctlLibName"],
    }
    if len(set(aliases.values())) != len(aliases):
        error("Generated packaged binary aliases are not unique")

    try:
        with ZipFile(apk) as zf:
            names = zf.namelist()
            missing = [
                alias for alias in aliases.values()
                if not any(
                    re.fullmatch(rf"lib/[^/]+/lib{re.escape(alias)}\.so", name)
                    for name in names
                )
            ]
            leaked = [
                name for name in names
                for source, alias in aliases.items()
                if alias != source
                and re.fullmatch(rf"lib/[^/]+/lib{re.escape(source)}\.so", name)
            ]
            update_binary = zf.read(
                "META-INF/com/google/android/update-binary"
            ).decode("utf-8")
            util_functions = zf.read("assets/util_functions.sh").decode("utf-8")
    except (BadZipFile, KeyError, OSError, UnicodeDecodeError) as exc:
        error(f"Invalid packaged binary identity in {apk}: {exc}")

    if missing:
        error("Randomized packaged binaries are missing: " + ", ".join(missing))
    if leaked:
        error("Source packaged binary names leaked into APK: " + ", ".join(leaked))
    if f"PACKAGED_BUSYBOX_LIB='{identity['busyboxLibName']}'" not in update_binary:
        error("Randomized bootstrap binary name is missing from update-binary")
    for key in aliases:
        variable = {
            "magisk": "PACKAGED_MAIN_LIB",
            "busybox": "PACKAGED_BUSYBOX_LIB",
            "mpol": "PACKAGED_POLICY_LIB",
            "init-ld": "PACKAGED_INIT_LD_LIB",
            "mboot": "PACKAGED_BOOT_LIB",
            "minit": "PACKAGED_INIT_LIB",
            "bootctl": "PACKAGED_BOOTCTL_LIB",
        }[key]
        if f"{variable}='{aliases[key]}'" not in util_functions:
            error(f"Installer mapping is missing for {aliases[key]}")

    header("Verified randomized packaged binary identities")


def _namespace_encodings(value: str) -> tuple[bytes, ...]:
    path = value.replace(".", "/")
    return (
        value.encode(), path.encode(),
        value.encode("utf-16le"), path.encode("utf-16le"),
        value.encode("utf-16be"), path.encode("utf-16be"),
    )


def _validate_packaged_app_identity(apk: Path):
    identity = _build_identity()
    namespaces = {
        "com.topjohnwu.magisk.core.BackgroundUpdateJobService":
            identity["backgroundUpdateJobServiceClass"],
        "com.topjohnwu.magisk.ui.surequest.SuRequestActivity":
            identity["suRequestActivityClass"],
        "com.topjohnwu.magisk.ui.webui.WebUIActivity": identity["webUiActivityClass"],
        "com.topjohnwu.magisk.ui.MainActivity": identity["mainActivityClass"],
        "com.topjohnwu.magisk.core.JobService": identity["jobServiceClass"],
        "com.topjohnwu.magisk.core.Receiver": identity["receiverClass"],
        "com.topjohnwu.magisk.core.Service": identity["serviceClass"],
        "com.topjohnwu.magisk.core.Provider": identity["providerClass"],
        "com.topjohnwu.magisk.core.App": identity["appClass"],
        "com.topjohnwu.magisk": identity["classNamespace"],
        "com.topjohnwu.shared": identity["sharedNamespace"],
        "com.topjohnwu.superuser": identity["superuserNamespace"],
        "com.topjohnwu.widget": identity["widgetNamespace"],
        "com.topjohnwu": identity["vendorNamespace"],
    }
    namespaces = {
        source: target for source, target in namespaces.items() if source != target
    }
    if not namespaces:
        return
    if any(len(source) != len(target) for source, target in namespaces.items()):
        error("A randomized class namespace has an invalid encoded length")

    source_forms = tuple(
        marker
        for source in namespaces
        for marker in _namespace_encodings(source)
    )
    target_forms = tuple(
        marker
        for target in namespaces.values()
        for marker in _namespace_encodings(target)
    )
    leaked_entries = []
    target_found = False
    try:
        with ZipFile(apk) as zf:
            for name in zf.namelist():
                contents = zf.read(name)
                if (
                    any(
                        source in name or source.replace(".", "/") in name
                        for source in namespaces
                    )
                    or any(marker in contents for marker in source_forms)
                ):
                    leaked_entries.append(name)
                if any(target in name for target in namespaces.values()) or any(
                    marker in contents for marker in target_forms
                ):
                    target_found = True
                if re.fullmatch(r"classes\d*\.dex", name):
                    if contents[:4] != b"dex\n" or len(contents) < 32:
                        error(f"Invalid DEX header in {apk}: {name}")
                    signature = hashlib.sha1(contents[32:]).digest()
                    checksum = zlib.adler32(contents[12:]) & 0xFFFFFFFF
                    stored_checksum = int.from_bytes(contents[8:12], "little")
                    if contents[12:32] != signature or stored_checksum != checksum:
                        error(f"Invalid rewritten DEX checksum in {apk}: {name}")
    except (BadZipFile, KeyError, OSError) as exc:
        error(f"Invalid randomized app identity in {apk}: {exc}")

    if leaked_entries:
        error(
            "Source app namespace leaked into packaged entries: "
            + ", ".join(leaked_entries)
        )
    if not target_found:
        error(f"Randomized class namespace is missing from {apk}")

    aapt2 = _latest_android_tool(sdk_path / "build-tools") / f"aapt2{EXE_EXT}"
    proc = subprocess.run(
        [aapt2, "dump", "badging", apk],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        shell=is_windows,
        text=True,
    )
    if proc.returncode != 0:
        error(f"Unable to inspect packaged app identity in {apk}: {proc.stdout}")
    if f"package: name='{identity['appPackageName']}'" not in proc.stdout:
        error(f"Randomized application ID is missing from {apk}")
    if f"versionName='{identity['appVersionName']}'" not in proc.stdout:
        error(f"Randomized application version name is missing from {apk}")
    labels = re.findall(r"^application-label(?:-[^:]+)?:'([^']*)'", proc.stdout, re.M)
    if not labels or any(label != identity["appLabel"] for label in labels):
        error(f"Randomized application label is inconsistent in {apk}")
    if any(source in proc.stdout for source in namespaces):
        error(f"Source component namespace leaked into manifest metadata in {apk}")

    header(
        f"Verified randomized app identity: "
        f"{identity['appPackageName']} / {identity['classNamespace']}"
    )


def _contains_encoded_token(data: bytes, token: str) -> bool:
    lower = data.lower()
    value = token.lower()
    return any(
        encoded in lower
        for encoded in (
            value.encode(),
            value.encode("utf-16le"),
            value.encode("utf-16be"),
        )
    )


def _validate_dex(data: bytes, label: str):
    """Reject corrupted post-processing output before APK installation."""
    def fail(reason):
        raise ValueError(f"Invalid DEX {label}: {reason}")

    if len(data) < 112 or not data.startswith(b"dex\n"):
        fail("missing header")
    if struct.unpack_from("<I", data, 32)[0] != len(data):
        fail("file size mismatch")
    if hashlib.sha1(data[32:]).digest() != data[12:32]:
        fail("SHA-1 mismatch")
    if zlib.adler32(data[12:]) != struct.unpack_from("<I", data, 8)[0]:
        fail("checksum mismatch")
    count, table = struct.unpack_from("<II", data, 56)
    if table + count * 4 > len(data):
        fail("truncated string table")
    previous = None
    for index in range(count):
        offset = struct.unpack_from("<I", data, table + index * 4)[0]
        length = 0
        for shift in range(0, 35, 7):
            if offset >= len(data):
                fail("truncated string length")
            value = data[offset]
            offset += 1
            length |= (value & 0x7f) << shift
            if value < 128:
                break
        else:
            fail("invalid string length")
        end = data.find(b"\x00", offset)
        if end < 0:
            fail("unterminated string")
        # DEX uses modified UTF-8 and orders strings by UTF-16 code units.
        try:
            text = data[offset:end].replace(b"\xc0\x80", b"\x00").decode(
                "utf-8", errors="surrogatepass"
            )
            key = text.encode("utf-16-be", errors="surrogatepass")
        except UnicodeError:
            fail("invalid string encoding")
        if len(key) // 2 != length:
            fail("string length mismatch")
        if previous is not None and previous >= key:
            fail(f"unsorted or duplicate string_ids at index {index}")
        previous = key


def _without_ui_display_labels(contents: bytes) -> bytes:
    """Allow only the requested complete string-pool display values in resources.arsc.

    This is a UI exception, not a claim that the APK has no visible branding.
    Internal identifiers and embedded occurrences remain subject to scanning.
    Keep these names synchronized with TransformApkTask's display labels.
    """
    for label in ("zygisk", "Zygisk", "udonge", "Udonge"):
        for encoding in ("utf-8", "utf-16le", "utf-16be"):
            value = label.encode(encoding)
            contents = contents.replace(value, ("_" * len(label)).encode(encoding))
    return contents


def _validate_release_artifact(apk: Path):
    """Reject public identities and malformed/alignment-unsafe release APKs."""
    # Reisenless is the intentional user-facing product label. Other legacy
    # identities remain forbidden throughout release APK contents.
    public_tokens = ("topjohnwu", "magisk", "zygisk", "udonge")
    global_tokens = ("topjohnwu",)
    forbidden_identifiers = (
        "io.sevcator.reisenless",
        "com.usjrbnga.hvsavzoq",
        "com.eltavine.duckdetector",
        "isreisenlesssu",
        "kernelsu",
        "apatch",
    )
    forbidden_hosts = (
        "github.com", "x.com/", "paypal.me", "patreon.com", "ko-fi.com",
    )
    updater_markers = (
        "home_notice_content", "home_support_content", "home_support_title",
        "settings_check_update", "settings_update_channel", "magisk_update_title",
        "update_channel", "updated_channel", "app_changelog",
    )
    visible_entries = re.compile(
        r"(?:AndroidManifest\.xml|resources\.arsc|classes\d*\.dex)$"
    )
    leaked: list[str] = []

    build_tools = _latest_android_tool(sdk_path / "build-tools")
    zipalign = build_tools / f"zipalign{EXE_EXT}"
    aligned = subprocess.run(
        [str(zipalign), "-c", "-P", "16", "4", str(apk)],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        shell=is_windows,
        text=True,
    )
    if aligned.returncode != 0:
        error(f"Release APK is not fully ZIP/page aligned: {apk}\n{aligned.stdout}")

    identity = _build_identity()
    payload_prefix = re.escape(f"assets/{identity['udongeArchive']}!/")
    # Compatibility vocabulary is allowed only in the exact privileged entries
    # that implement the inherited module/boot protocol. It is never allowed by
    # file type alone, so adding a new occurrence requires an explicit review.
    compatibility_allowlist = {
        "magisk": (
            re.compile(r"META-INF/com/google/android/updater-script"),
            re.compile(
                r"assets/(?:addon\.d|app_functions|boot_patch|module_installer|"
                r"uninstaller|util_functions)\.sh"
            ),
            re.compile(
                r"lib/[^/]+/lib(?:"
                + "|".join(
                    re.escape(identity[name])
                    for name in ("bootLibName", "mainLibName", "initLibName")
                )
                + r")\.so"
            ),
            re.compile(payload_prefix + r"zygisk/[^/]+\.so"),
        ),
        "zygisk": (
            re.compile(r"assets/app_functions\.sh"),
            re.compile(r"lib/[^/]+/lib" + re.escape(identity["mainLibName"]) + r"\.so"),
            re.compile(payload_prefix + r"zygisk/[^/]+\.so"),
        ),
    }

    def scan_entry(label: str, name: str, contents: bytes, depth: int = 0):
        if re.fullmatch(r"classes\d*\.dex", name):
            _validate_dex(contents, label)
        if depth == 0 and name == "resources.arsc":
            contents = _without_ui_display_labels(contents)
        lower_name = name.lower()
        nested_archive = contents.startswith(b"PK\x03\x04")
        tokens = public_tokens if visible_entries.fullmatch(name) else global_tokens
        if any(token in lower_name for token in tokens) or (
            not nested_archive and any(_contains_encoded_token(contents, token) for token in tokens)
        ):
            leaked.append(label)
        if any(token in lower_name for token in forbidden_identifiers) or (
            not nested_archive and any(
                _contains_encoded_token(contents, token) for token in forbidden_identifiers
            )
        ):
            leaked.append(label)
        if not nested_archive and any(
            _contains_encoded_token(contents, host) for host in forbidden_hosts
        ):
            leaked.append(label)
        if visible_entries.fullmatch(name) and any(
            _contains_encoded_token(contents, marker) for marker in updater_markers
        ):
            leaked.append(label)
        if visible_entries.fullmatch(name) and any(
            _contains_encoded_token(contents, token) for token in ("magisk", "zygisk")
        ):
            leaked.append(label)
        if not nested_archive:
            for token, allowed_entries in compatibility_allowlist.items():
                if _contains_encoded_token(contents, token) and not any(
                    pattern.fullmatch(label) for pattern in allowed_entries
                ):
                    leaked.append(label)
        if depth < 3 and nested_archive:
            try:
                with ZipFile(io.BytesIO(contents)) as nested:
                    for child in nested.infolist():
                        if child.is_dir():
                            continue
                        scan_entry(f"{label}!/{child.filename}", child.filename,
                                   nested.read(child), depth + 1)
            except BadZipFile:
                leaked.append(f"{label} (invalid nested archive)")

    try:
        with ZipFile(apk) as archive:
            resources = archive.getinfo("resources.arsc")
            if resources.compress_type != ZIP_STORED:
                error("Release resources.arsc must be stored without compression")
            with apk.open("rb") as raw_apk:
                raw_apk.seek(resources.header_offset)
                local_header = raw_apk.read(30)
            if len(local_header) != 30 or local_header[:4] != b"PK\x03\x04":
                error("Release resources.arsc has an invalid local ZIP header")
            name_len, extra_len = struct.unpack_from("<HH", local_header, 26)
            data_offset = resources.header_offset + 30 + name_len + extra_len
            if data_offset % 4:
                error("Release resources.arsc is not aligned to a 4-byte boundary")
            for info in archive.infolist():
                if info.is_dir():
                    continue
                contents = archive.read(info)
                if re.fullmatch(r"lib/[^/]+/[^/]+\.so", info.filename):
                    if info.compress_type != ZIP_STORED:
                        error(f"Native library is compressed: {info.filename}")
                    with apk.open("rb") as raw_apk:
                        raw_apk.seek(info.header_offset)
                        native_header = raw_apk.read(30)
                    if len(native_header) != 30 or native_header[:4] != b"PK\x03\x04":
                        error(f"Native library has an invalid local ZIP header: {info.filename}")
                    name_len, extra_len = struct.unpack_from("<HH", native_header, 26)
                    native_offset = info.header_offset + 30 + name_len + extra_len
                    if native_offset % 16384:
                        error(f"Native library is not 16 KiB page aligned: {info.filename}")
                scan_entry(info.filename, info.filename, contents)
    except (BadZipFile, KeyError, OSError) as exc:
        error(f"Unable to validate release artifact visibility: {exc}")

    if leaked:
        error("Forbidden release identity/content leaked into: " + ", ".join(sorted(set(leaked))))

    aapt2 = build_tools / f"aapt2{EXE_EXT}"
    manifest = subprocess.run(
        [str(aapt2), "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        shell=is_windows,
        text=True,
    )
    if manifest.returncode != 0:
        error(f"Unable to inspect release manifest: {manifest.stdout}")
    if re.search(r"android:(?:debuggable|testOnly)[^\n]*=(?:true|0xffffffff)", manifest.stdout):
        error("Release APK is debuggable or test-only")
    if not re.search(r"android:extractNativeLibs[^\n]*=(?:true|0xffffffff)", manifest.stdout):
        error("Manager CLI executables require extractNativeLibs=true")
    header("Verified release artifact (readable UI labels explicitly allowed)")


def _generate_obfuscation_dictionary(identity: dict[str, str]):
    """Generate the R8 dictionary from the same build identity as every other name."""
    first = string.ascii_lowercase + string.ascii_uppercase
    rest = first + string.digits
    names = []
    for a in first:
        if a not in {"a", "A"}:
            names.append(a)
        for b in rest:
            names.append(a + b)
            names.extend(a + b + c for c in rest)

    seed = json.dumps(identity, sort_keys=True, separators=(",", ":")).encode()
    names.sort(
        key=lambda name: hashlib.shake_256(
            seed + b"\0r8-dictionary\0" + name.encode()
        ).digest(16)
    )
    write_if_diff(Path("app", "dict.txt"), "\n".join(names) + "\n")


def build_apk(module: str):
    ensure_paths()
    _validate_legacy_identity_config()
    env = find_jdk()
    signing = _prepare_signing_config(env)
    props = args.config.resolve()


    gradle_build_dir = Path("app", "build")
    gradle_build_dir.mkdir(mode=0o755, parents=True, exist_ok=True)
    identity = _build_identity()
    _generate_obfuscation_dictionary(identity)
    write_if_diff(
        gradle_build_dir / "flags.prop",
        f"version={config['version']}\n"
        f"magisk.versionCode={config['versionCode']}\n"
        f"abiList={','.join(build_abis.keys())}\n"
        + "".join(f"{key}={value}\n" for key, value in identity.items())
        + "".join(
            f"{key}={signing[key]}\n"
            for key in ("keyStore", "keyStorePass", "keyAlias", "keyPass")
        ),
    )

    os.chdir("app")
    build_type = "Release" if args.release else "Debug"
    proc = execv(
        [
            gradlew,
            f"{module}:assemble{build_type}",
            f"-PconfigPath={props}",
            f"-PabiList={','.join(build_abis.keys())}",
        ],
        env=env,
    )
    os.chdir("..")
    if proc.returncode != 0:
        error(f"Build {module} failed!")

    build_type = build_type.lower()

    paths = module.split(":")

    apk = f"{paths[-1]}-{build_type}.apk"
    source = Path("app", *paths, "build", "outputs", "apk", build_type, apk)
    target = config["outdir"] / apk
    mv(source, target)
    _validate_packaged_signing(target, signing["certificateSha256"], env)
    if module in {":apk", ":apk-legacy"}:
        _validate_embedded_trust_anchor(target, signing["certificateSha256"], env)
        _validate_packaged_udonge(target)
        _validate_packaged_binary_identity(target)
        _validate_packaged_app_identity(target)
        if args.release:
            _validate_release_artifact(target)
    return target


def build_app():
    _validate_generated_flags(
        "Build native binaries with the same mode and configuration first."
    )
    build_udonge()
    header("* Building the manager app")
    manager_ui = config.get("managerUi", "compose")
    if manager_ui not in {"classic", "compose"}:
        error("managerUi must be classic or compose")
    apk = build_apk(":apk-legacy" if manager_ui == "classic" else ":apk")

    source = apk
    target = apk.parent / (
        _build_identity()["artifactName"]
        if args.release
        else apk.name.replace("apk-", "app-")
    )
    mv(source, target)
    header(f"Output: {target}")


def build_app_legacy():
    _validate_generated_flags(
        "Build native binaries with the same mode and configuration first."
    )
    build_udonge()
    header("* Building the legacy Magisk app")
    apk = build_apk(":apk-legacy")
    header(f"Output: {apk}")


def build_stub():
    header("* Building the signed manager trust anchor")
    apk = build_apk(":stub")
    header(f"Output: {apk}")







def cleanup():
    ensure_paths()
    if args.targets:
        targets: set[str] = set(args.targets) & clean_targets
        if "native" in targets:
            targets.add("cpp")
            targets.add("rust")
    else:
        targets = clean_targets

    if "cpp" in targets:
        header("* Cleaning C++")
        rm_rf(Path("native", "libs"))
        rm_rf(Path("native", "obj"))

    if "rust" in targets:
        header("* Cleaning Rust")
        rm_rf(Path("native", "out", "rust"))
        rm_rf(_cargo_target_dir())
        rm(Path("native", "src", "boot", "proto", "mod.rs"))
        rm(Path("native", "src", "boot", "proto", "update_metadata.rs"))
        for rs_gen in glob.glob("native/**/*-rs.*pp", recursive=True):
            rm(Path(rs_gen))

    if "native" in targets:
        header("* Cleaning native")
        rm_rf(Path("native", "out"))
        rm_rf(Path("tools", "elf-cleaner", "target"))

    if "app" in targets:
        header("* Cleaning app")
        os.chdir("app")
        execv([gradlew, ":clean"], env=find_jdk())
        os.chdir("..")


def build_all():
    build_native()
    build_app()
    build_app_legacy()







def gen_ide():
    ensure_paths()
    set_build_abis({args.abi})


    dump_flag_header()


    os.chdir(Path("native", "src"))
    run_cargo(["check"])
    os.chdir(Path("..", ".."))


    rm_rf(Path("native", "compile_commands.json"))
    run_ndk_build(
        [
            "B_MAGISK=1",
            "B_INIT=1",
            "B_BOOT=1",
            "B_POLICY=1",
            "B_PRELOAD=1",
            "B_CRT0=1",
            "compile_commands.json",
        ]
    )


def test_native_auth():
    """Run authorization and APK trust-parser tests with the host compiler."""
    ensure_paths()
    env = os.environ.copy()
    env["PATH"] = f"{rust_sysroot / 'bin'}{os.pathsep}{env['PATH']}"
    with tempfile.TemporaryDirectory(prefix="manager-auth-test-") as temp:
        for stem in ("manager_auth", "apk_cert"):
            source = Path("native", "src", "core", f"{stem}.rs").absolute()
            executable = Path(temp, f"{stem}-tests")
            if is_windows:
                executable = executable.with_suffix(".exe")
            compile_result = execv(
                ["rustc", "--edition=2024", "--test", str(source), "-o", str(executable)],
                env,
            )
            if compile_result.returncode != 0:
                error(
                    f"Host {stem} test compilation failed with exit code "
                    f"{compile_result.returncode}"
                )
            test_result = execv([str(executable), "--nocapture"], env)
            if test_result.returncode != 0:
                error(f"Host {stem} tests failed with exit code {test_result.returncode}")


def test_identity_generation():
    """Prove private identity derivation is deterministic and seed-separated."""
    saved_env_seed = os.environ.pop("REISENLESS_IDENTITY_SEED", None)
    saved_seed = config.get("identitySeed")
    saved_randomize = config.get("randomizeBuild")
    saved_secure = config.get("randomizeSecureDir")
    saved_release = args.release
    try:
        args.release = False
        config["randomizeBuild"] = "true"
        config["randomizeSecureDir"] = "true"
        config["identitySeed"] = "identity-test-private-seed-a"
        first = _build_identity()
        second = _build_identity()
        config["identitySeed"] = "identity-test-private-seed-b"
        different = _build_identity()
        if first != second:
            error("Identity generation is not deterministic for an identical seed")
        compared = ("appPackageName", "classNamespace", "secureDir", "runtimeSeed")
        if any(first[key] == different[key] for key in compared):
            error("Different private identity seeds did not separate critical identities")
        print("Identity generation tests passed")
    finally:
        if saved_env_seed is not None:
            os.environ["REISENLESS_IDENTITY_SEED"] = saved_env_seed
        args.release = saved_release
        if saved_seed is None:
            config.pop("identitySeed", None)
        else:
            config["identitySeed"] = saved_seed
        if saved_randomize is None:
            config.pop("randomizeBuild", None)
        else:
            config["randomizeBuild"] = saved_randomize
        if saved_secure is None:
            config.pop("randomizeSecureDir", None)
        else:
            config["randomizeSecureDir"] = saved_secure


def clippy_cli():
    ensure_toolchain()
    global force_out
    force_out = True
    if args.abi:
        set_build_abis(set(args.abi))
    else:
        set_build_abis(default_abis)

    if not args.release and not args.debug:

        args.release = True
        args.debug = True

    os.chdir(Path("native", "src"))
    cmds = ["clippy", "--no-deps", "--target"]
    for triple in build_abis.values():
        if args.debug:
            run_cargo(cmds + [triple])
        if args.release:
            run_cargo(cmds + [triple, "--release"])
    os.chdir(Path("..", ".."))


def cargo_cli():
    global force_out
    force_out = True
    if len(args.commands) >= 1 and args.commands[0] == "--":
        args.commands = args.commands[1:]
    os.chdir(Path("native", "src"))
    run_cargo(args.commands)
    os.chdir(Path("..", ".."))


def setup_ndk():
    ensure_paths()
    url = f"https://github.com/topjohnwu/ondk/releases/download/{ondk_version}/ondk-{ondk_version}-{os_name}.tar.xz"
    ndk_archive = url.split("/")[-1]
    staging_dir = Path(tempfile.mkdtemp(prefix=".magisk-ondk-", dir=ndk_root))

    header(f"* Downloading and extracting {ndk_archive}")
    try:
        with urllib.request.urlopen(url) as response:


            with tempfile.TemporaryFile() as archive:
                shutil.copyfileobj(response, archive)
                archive.seek(0)
                with tarfile.open(mode="r:xz", fileobj=archive) as tar:
                    if hasattr(tarfile, "data_filter"):
                        tar.extractall(staging_dir, filter="tar")
                    else:
                        tar.extractall(staging_dir)

        markers = list(staging_dir.rglob("ONDK_VERSION"))
        if len(markers) != 1 or markers[0].read_text().strip() != ondk_version:
            error(f"Invalid {ndk_archive} layout")
        extracted_ndk_path = markers[0].parent

        if ndk_path.exists():
            rm_rf(ndk_path)
        shutil.move(extracted_ndk_path, ndk_path)
    finally:
        if staging_dir.exists():
            rm_rf(staging_dir)


def setup_rustup():
    wrapper_dir = Path(args.wrapper_dir)
    rm_rf(wrapper_dir)
    wrapper_dir.mkdir(mode=0o755, parents=True, exist_ok=True)
    if "CARGO_HOME" in os.environ:
        cargo_home = Path(os.environ["CARGO_HOME"])
    else:
        cargo_home = Path.home() / ".cargo"
    cargo_bin = cargo_home / "bin"
    for src in cargo_bin.iterdir():
        tgt = wrapper_dir / src.name
        tgt.symlink_to(f"rustup{EXE_EXT}")


    wrapper_src = Path("tools", "rustup-wrapper")
    cargo_toml = wrapper_src / "Cargo.toml"
    cmds = ["build", "--release", f"--manifest-path={cargo_toml}"]
    if args.verbose > 1:
        cmds.append("--verbose")
    run_cargo(cmds)


    wrapper = wrapper_dir / (f"rustup{EXE_EXT}")
    wrapper.unlink(missing_ok=True)
    cp(wrapper_src / "target" / "release" / (f"rustup-wrapper{EXE_EXT}"), wrapper)
    wrapper.chmod(0o755)







def push_files(script: Path):
    if args.build:
        build_all()
    ensure_adb()

    abi = cmd_out([adb_path, "shell", "getprop", "ro.product.cpu.abi"])
    if not abi:
        error("Cannot detect emulator ABI")

    if args.apk:
        apk = Path(args.apk)
    else:
        apk = Path(
            config["outdir"], ("app-release.apk" if args.release else "app-debug.apk")
        )


    busybox = Path(config["outdir"], "busybox")
    with ZipFile(apk) as zf:
        busybox_lib = _build_identity()["busyboxLibName"]
        with zf.open(f"lib/{abi}/lib{busybox_lib}.so") as libbb:
            with open(busybox, "wb") as bb:
                bb.write(libbb.read())

    try:
        proc = execv([adb_path, "push", busybox, script, "/data/local/tmp"])
        if proc.returncode != 0:
            error("adb push failed!")
    finally:
        rm_rf(busybox)

    proc = execv([adb_path, "push", apk, "/data/local/tmp/magisk.apk"])
    if proc.returncode != 0:
        error("adb push failed!")


def setup_avd():
    header("* Setting up emulator")

    push_files(Path("scripts", "live_setup.sh"))

    proc = execv([adb_path, "shell", "sh", "/data/local/tmp/live_setup.sh"])
    if proc.returncode != 0:
        error("live_setup.sh failed!")


def patch_avd_file():
    input = Path(args.image)
    output = Path(args.output)

    header(f"* Patching {input.name}")

    push_files(Path("scripts", "host_patch.sh"))

    proc = execv([adb_path, "push", input, "/data/local/tmp"])
    if proc.returncode != 0:
        error("adb push failed!")

    src_file = f"/data/local/tmp/{input.name}"
    out_file = f"{src_file}.magisk"

    proc = execv([adb_path, "shell", "sh", "/data/local/tmp/host_patch.sh", src_file])
    if proc.returncode != 0:
        error("host_patch.sh failed!")

    proc = execv([adb_path, "pull", out_file, output])
    if proc.returncode != 0:
        error("adb pull failed!")

    header(f"Output: {output}")







def ensure_paths():
    global sdk_path, ndk_root, ndk_path, rust_sysroot
    global ndk_build, gradlew, adb_path


    if "sdk_path" in globals():
        return

    try:
        sdk_path = Path(os.environ["ANDROID_HOME"])
    except KeyError:
        try:
            sdk_path = Path(os.environ["ANDROID_SDK_ROOT"])
        except KeyError:
            error("Please set Android SDK path to environment variable ANDROID_HOME")

    ndk_root = sdk_path / "ndk"
    ndk_path = ndk_root / "magisk"
    ndk_build = ndk_path / "ndk-build"
    rust_sysroot = ndk_path / "toolchains" / "rust"
    adb_path = sdk_path / "platform-tools" / "adb"
    gradlew = Path.cwd() / "app" / "gradlew"



def ensure_adb():
    global adb_path
    if "adb_path" not in globals():
        if adb := shutil.which("adb"):
            adb_path = Path(adb)
        else:
            error("Command 'adb' cannot be found in PATH")


def parse_props(file: Path) -> dict[str, str]:
    props = {}
    with open(file, "r", encoding="utf-8") as f:
        for line in [l.strip(" \t\r\n") for l in f]:
            if line.startswith("#") or len(line) == 0:
                continue
            prop = line.split("=", 1)
            if len(prop) != 2:
                continue
            key = prop[0].strip(" \t\r\n")
            value = prop[1].strip(" \t\r\n")
            if not key or not value:
                continue
            props[key] = value
    return props


def set_build_abis(abis: set[str]):
    global build_abis

    abis = {abi_alias.get(k, k) for k in abis}

    for k in abis - support_abis.keys():
        error(f"Unknown ABI: {k}")
    build_abis = {k: support_abis[k] for k in support_abis if k in abis}


def load_config():
    commit_hash = cmd_out(["git", "rev-parse", "--short=8", "HEAD"])
    commit_timestamp = cmd_out(["git", "show", "-s", "--format=%ct", "HEAD"])


    config["version"] = commit_hash
    config["versionCode"] = 1000000
    config["outdir"] = "out"


    if args.config.exists():
        config.update(parse_props(args.config))

    gradle_props = Path("app", "gradle.properties")
    if gradle_props.exists():
        for key, value in parse_props(gradle_props).items():
            if key.startswith("magisk."):
                config[key[7:]] = value


    config["version"] = commit_hash
    config["versionCode"] = commit_timestamp

    try:
        config["versionCode"] = int(config["versionCode"])
    except ValueError:
        error('Config error: "versionCode" is required to be an integer')

    config["outdir"] = Path(config["outdir"])
    config["outdir"].mkdir(mode=0o755, parents=True, exist_ok=True)

    if "abiList" in config:
        abis = set(re.split("\\s*,\\s*", config["abiList"]))
    else:
        abis = default_abis

    set_build_abis(abis)


def parse_args():
    parser = argparse.ArgumentParser(description="Magisk build script")
    parser.set_defaults(func=lambda x: None)
    parser.add_argument(
        "-r", "--release", action="store_true", help="compile in release mode"
    )
    parser.add_argument(
        "-v", "--verbose", action="count", default=0, help="verbose output"
    )
    parser.add_argument(
        "-c",
        "--config",
        default="config.prop",
        help="custom config file (default: config.prop)",
    )
    subparsers = parser.add_subparsers(title="actions")

    all_parser = subparsers.add_parser("all", help="build everything")

    native_parser = subparsers.add_parser("native", help="build native binaries")
    native_parser.add_argument(
        "targets",
        nargs="*",
        help=f"{', '.join(support_targets)}, \
        or empty for defaults ({', '.join(default_targets)})",
    )

    app_parser = subparsers.add_parser("app", help="build the Magisk app")

    app_legacy_parser = subparsers.add_parser(
        "app-legacy", help="build the legacy Magisk app"
    )

    stub_parser = subparsers.add_parser("stub", help="build the manager trust anchor")

    clean_parser = subparsers.add_parser("clean", help="cleanup")
    clean_parser.add_argument(
        "targets", nargs="*", help="native, cpp, rust, java, or empty to clean all"
    )

    ndk_parser = subparsers.add_parser("ndk", help="setup Magisk NDK")

    emu_parser = subparsers.add_parser("emulator", help="setup AVD for development")
    emu_parser.add_argument("apk", help="a Magisk APK to use", nargs="?")
    emu_parser.add_argument(
        "-b", "--build", action="store_true", help="build before patching"
    )

    avd_patch_parser = subparsers.add_parser(
        "avd_patch", help="patch AVD ramdisk.img or init_boot.img"
    )
    avd_patch_parser.add_argument("image", help="path to ramdisk.img or init_boot.img")
    avd_patch_parser.add_argument("output", help="output file name")
    avd_patch_parser.add_argument("--apk", help="a Magisk APK to use")
    avd_patch_parser.add_argument(
        "-b", "--build", action="store_true", help="build before patching"
    )

    cargo_parser = subparsers.add_parser(
        "cargo", help="call 'cargo' commands against the project"
    )
    cargo_parser.add_argument("commands", nargs=argparse.REMAINDER)

    clippy_parser = subparsers.add_parser("clippy", help="run clippy on Rust sources")
    clippy_parser.add_argument(
        "--abi", action="append", help="target ABI(s) to run clippy"
    )
    clippy_parser.add_argument(
        "-r", "--release", action="store_true", help="run clippy as release"
    )
    clippy_parser.add_argument(
        "-d", "--debug", action="store_true", help="run clippy as debug"
    )

    rustup_parser = subparsers.add_parser("rustup", help="setup rustup wrapper")
    rustup_parser.add_argument(
        "wrapper_dir", help="path to setup rustup wrapper binaries"
    )

    gen_parser = subparsers.add_parser("gen", help="generate files for IDE")
    gen_parser.add_argument("--abi", default="arm64-v8a", help="target ABI to generate")

    native_auth_test_parser = subparsers.add_parser(
        "test-native-auth", help="run host-only manager authorization tests"
    )
    identity_test_parser = subparsers.add_parser(
        "test-identity", help="test deterministic private identity generation"
    )


    all_parser.set_defaults(func=build_all)
    native_parser.set_defaults(func=build_native)
    cargo_parser.set_defaults(func=cargo_cli)
    clippy_parser.set_defaults(func=clippy_cli)
    rustup_parser.set_defaults(func=setup_rustup)
    gen_parser.set_defaults(func=gen_ide)
    native_auth_test_parser.set_defaults(func=test_native_auth)
    identity_test_parser.set_defaults(func=test_identity_generation)
    app_parser.set_defaults(func=build_app)
    app_legacy_parser.set_defaults(func=build_app_legacy)
    stub_parser.set_defaults(func=build_stub)
    emu_parser.set_defaults(func=setup_avd)
    avd_patch_parser.set_defaults(func=patch_avd_file)
    clean_parser.set_defaults(func=cleanup)
    ndk_parser.set_defaults(func=setup_ndk)

    if len(sys.argv) == 1:
        parser.print_help()
        sys.exit(1)

    return parser.parse_args()


def main():
    global args
    args = parse_args()
    args.config = Path(args.config)
    load_config()
    args.func()


if __name__ == "__main__":
    main()
