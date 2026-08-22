#!/usr/bin/env python3
import argparse
import glob
import hashlib
import json
import multiprocessing
import os
import platform
import re
import shutil
import stat
import string
import struct
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zlib
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


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


# OS detection
os_name = platform.system().lower()
is_windows = False
if os_name != "linux" and os_name != "darwin":
    # It's possible we're using MSYS/Cygwin/MinGW, treat them all as Windows
    is_windows = True
    os_name = "windows"
EXE_EXT = ".exe" if is_windows else ""

no_color = False
if is_windows:
    try:
        import colorama

        colorama.init()
    except ImportError:
        # We can't do ANSI color codes in terminal on Windows without colorama
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
# Map from binary target names to Rust crate (cargo package) names
rust_crate_map = {"minit": "magiskinit", "mboot": "magiskboot", "mpol": "magiskpolicy"}
clean_targets = {"native", "cpp", "rust", "app"}
ondk_version = "r30.1"

# Global vars
config = {}
args: argparse.Namespace
build_abis: dict[str, str]
force_out = False

###################
# Helper functions
###################


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


def rm_on_error(func, path, _):
    # Removing a read-only file on Windows will get "WindowsError: [Error 5] Access is denied"
    # Clear the "read-only" bit and retry
    try:
        os.chmod(path, stat.S_IWRITE)
        os.unlink(path)
    except FileNotFoundError as e:
        pass


def rm_rf(path: Path):
    vprint(f"rm -rf {path}")
    if sys.version_info >= (3, 12):
        shutil.rmtree(path, ignore_errors=False, onexc=rm_on_error)
    else:
        shutil.rmtree(path, ignore_errors=False, onerror=rm_on_error)


def execv(cmds: list, env=None):
    out = None if force_out or args.verbose > 0 else subprocess.DEVNULL
    # Use shell on Windows to support PATHEXT
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


###############
# Build Native
###############


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


def run_cargo(cmds: list[str]):
    ensure_paths()
    env = os.environ.copy()
    env["PATH"] = f"{rust_sysroot / "bin"}{os.pathsep}{env["PATH"]}"
    env["CARGO_BUILD_RUSTFLAGS"] = f"-Z threads={min(8, cpu_count)}"
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
    # Cargo calls executables in $RUSTROOT/lib/rustlib/$TRIPLE/bin, we need
    # to make sure the runtime linker also search $RUSTROOT/lib for libraries.
    # This is only required on Unix, as Windows search dlls from PATH.
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

    # Start building the build commands
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
    rust_out = native_out / "rust"
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
        return {
            "buildId": "ms", "secureDir": config.get("secureDir", "/data/adb"),
            "dataDir": "ms", "dbName": "ms.db", "internalDir": ".ms",
            "socketName": "socket", "policyName": "mpol", "bin32Name": "ms32",
            "busyboxName": "busybox",
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

    # A private CI seed can make the generated names non-derivable. Keeping
    # the seed stable preserves upgrades while all identifiers are still
    # materialized only as part of the build.
    seed = (
        os.environ.get("REISENLESS_IDENTITY_SEED", "").strip()
        or config.get("identitySeed", "").strip()
        or "reisenless-build-identity-v1"
    )
    namespace = _repository_namespace()

    def token(label: str, minimum: int = 5, maximum: int = 10) -> str:
        digest = hashlib.shake_256(
            f"{seed}\0{namespace}\0{label}".encode("utf-8")
        ).digest(maximum + 1)
        size = minimum + digest[0] % (maximum - minimum + 1)
        return "".join(string.ascii_lowercase[value % 26] for value in digest[1:size + 1])

    explicit_secure_dir = config.get("secureDir", "")
    randomize_secure = config.get("randomizeSecureDir", "true").lower() == "true"
    secure_dir = (
        f"/data/.{token('secure-dir', 4, 4)}"
        if randomize_secure or not explicit_secure_dir
        else explicit_secure_dir
    )
    proc = token("policy-domain", 6, 9)
    file_type = token("policy-file", 6, 9)
    udonge_type = token("policy-udonge", 6, 9)
    main_binary = token("main-binary", 5, 8)
    return {
        "buildId": main_binary,
        "secureDir": secure_dir,
        "dataDir": "." + token("data-bin", 6, 10),
        "dbName": "." + token("database", 6, 10),
        "internalDir": "." + token("tmpfs-internal", 6, 10),
        "socketName": token("daemon-socket", 6, 10),
        "policyName": token("policy-binary", 5, 9),
        "bin32Name": token("bin32-databin", 5, 9),
        "busyboxName": token("toolbox-binary", 6, 10),
        # The ramdisk proxy must resolve to the daemon after /sbin is moved.
        "ramdiskName": main_binary,
        "stubName": token("stub-apk", 6, 10) + ".apk",
        "initLdName": token("init-loader", 6, 10),
        "udongeDir": "." + token("udonge-root", 3, 3),
        "udongeArchive": token("udonge-archive", 7, 11) + ".bin",
        "backupConfig": "." + token("backup-config", 6, 10),
        # Must fit inside the /system/bin/init string patched in-place.
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
        "spoofFingerprint": config.get("spoofFingerprint", ""),
        "spoofManufacturer": config.get("spoofManufacturer", ""),
        "spoofModel": config.get("spoofModel", ""),
        "spoofProduct": config.get("spoofProduct", ""),
        "spoofDevice": config.get("spoofDevice", ""),
        "spoofBuildId": config.get("spoofBuildId", ""),
        "spoofSecurityPatch": config.get("spoofSecurityPatch", ""),
        "spoofVersionRelease": config.get("spoofVersionRelease", ""),
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

    # Build spoof values (empty string = disabled)
    spoof_fp = config.get("spoofFingerprint", "")
    spoof_mfr = config.get("spoofManufacturer", "")
    spoof_model = config.get("spoofModel", "")
    spoof_product = config.get("spoofProduct", "")
    spoof_device = config.get("spoofDevice", "")
    spoof_bid = config.get("spoofBuildId", "")
    spoof_patch = config.get("spoofSecurityPatch", "")
    spoof_ver = config.get("spoofVersionRelease", "")

    version = _escape_flag_string(config["version"])
    spoof_fp = _escape_flag_string(spoof_fp)
    spoof_mfr = _escape_flag_string(spoof_mfr)
    spoof_model = _escape_flag_string(spoof_model)
    spoof_product = _escape_flag_string(spoof_product)
    spoof_device = _escape_flag_string(spoof_device)
    spoof_bid = _escape_flag_string(spoof_bid)
    spoof_patch = _escape_flag_string(spoof_patch)
    spoof_ver = _escape_flag_string(spoof_ver)

    flag_txt = "#pragma once\n"
    flag_txt += f'#define MAGISK_VERSION      "{version}"\n'
    flag_txt += f'#define MAGISK_VER_CODE     {config["versionCode"]}\n'
    flag_txt += f"#define MAGISK_DEBUG        {0 if args.release else 1}\n"
    flag_txt += f'#define BUILD_ID            "{build_id}"\n'
    flag_txt += f'#define BUILD_SECURE_DIR    "{secure_dir}"\n'
    identity_flags = {
        "dataDir": "BUILD_DATA_DIR", "dbName": "BUILD_DB_NAME",
        "internalDir": "BUILD_INTERNAL_DIR", "socketName": "BUILD_SOCKET_NAME",
        "policyName": "BUILD_POLICY_NAME", "bin32Name": "BUILD_BIN32_NAME",
        "busyboxName": "BUILD_BUSYBOX_NAME",
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
    flag_txt += f'#define SPOOF_FINGERPRINT   "{spoof_fp}"\n'
    flag_txt += f'#define SPOOF_MANUFACTURER  "{spoof_mfr}"\n'
    flag_txt += f'#define SPOOF_MODEL         "{spoof_model}"\n'
    flag_txt += f'#define SPOOF_PRODUCT       "{spoof_product}"\n'
    flag_txt += f'#define SPOOF_DEVICE        "{spoof_device}"\n'
    flag_txt += f'#define SPOOF_BUILD_ID      "{spoof_bid}"\n'
    flag_txt += f'#define SPOOF_SECURITY_PATCH "{spoof_patch}"\n'
    flag_txt += f'#define SPOOF_VERSION_RELEASE "{spoof_ver}"\n'

    native_gen_path = Path("native", "out", "generated")
    native_gen_path.mkdir(mode=0o755, parents=True, exist_ok=True)
    write_if_diff(native_gen_path / "flags.h", flag_txt)

    rust_flag_txt = f'pub const MAGISK_VERSION: &str = "{version}";\n'
    rust_flag_txt += f'pub const MAGISK_VER_CODE: i32 = {config["versionCode"]};\n'
    rust_flag_txt += f'pub const BUILD_ID: &str = "{build_id}";\n'
    rust_flag_txt += f'pub const BUILD_SECURE_DIR: &str = "{secure_dir}";\n'
    for key, const_name in identity_flags.items():
        rust_flag_txt += f'pub const {const_name}: &str = "{identity[key]}";\n'
    rust_flag_txt += f'pub const SPOOF_FINGERPRINT: &str = "{spoof_fp}";\n'
    rust_flag_txt += f'pub const SPOOF_MANUFACTURER: &str = "{spoof_mfr}";\n'
    rust_flag_txt += f'pub const SPOOF_MODEL: &str = "{spoof_model}";\n'
    rust_flag_txt += f'pub const SPOOF_PRODUCT: &str = "{spoof_product}";\n'
    rust_flag_txt += f'pub const SPOOF_DEVICE: &str = "{spoof_device}";\n'
    rust_flag_txt += f'pub const SPOOF_BUILD_ID: &str = "{spoof_bid}";\n'
    rust_flag_txt += f'pub const SPOOF_SECURITY_PATCH: &str = "{spoof_patch}";\n'
    rust_flag_txt += f'pub const SPOOF_VERSION_RELEASE: &str = "{spoof_ver}";\n'
    write_if_diff(native_gen_path / "flags.rs", rust_flag_txt)
    write_if_diff(
        native_gen_path / "flags.json",
        json.dumps(_build_flag_metadata(), indent=2, sort_keys=True) + "\n",
    )


def ensure_toolchain():
    ensure_paths()

    # Verify NDK install
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

    # Rebuilding only some binaries with a new random identity leaves the
    # native output internally inconsistent. Partial builds must reuse the
    # exact flags of the existing output set; full builds rotate the identity.
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


############
# Build App
############


def find_jdk():
    env = os.environ.copy()
    if "ANDROID_STUDIO" in env:
        studio = env["ANDROID_STUDIO"]
        jbr = Path(studio, "jbr", "bin")
        if not jbr.exists():
            jbr = Path(studio, "Contents", "jbr", "Contents", "Home", "bin")
        if jbr.exists():
            env["PATH"] = f'{jbr}{os.pathsep}{env["PATH"]}'

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
        no_jdk = proc.returncode != 0 or not proc.stdout.strip().startswith("javac 21")
    except FileNotFoundError:
        no_jdk = True

    if no_jdk:
        error(
            "Please set Android Studio's path to environment variable ANDROID_STUDIO,\n"
            + "or install JDK 21 and make sure 'javac' is available in PATH"
        )

    return env


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
        # Repeated path separators are equivalent on Android and let the
        # replacement preserve the DEX string_data layout exactly. For the
        # library path, pad after the Udonge root so it still sorts before the
        # state path in the DEX string_ids table.
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


def build_udonge():
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

    native_sources = [
        Path("udonge", "native", name)
        for name in ("main.cpp", "config.cpp", "hideapps.cpp", "hooks.cpp", "spoof.cpp")
    ]
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
    with ZipFile(output, "w") as zf:
        _zip_bytes(zf, "version", f"{config['version']}\n".encode())
        _zip_bytes(zf, "hideapps.dex", hideapps_dex.read_bytes())
        for lib in sorted(zygisk_out.glob("*.so")):
            _zip_bytes(zf, f"zygisk/{lib.name}", lib.read_bytes())
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
            _zip_bytes(zf, name, data, mode)

    rm_rf(work)
    header(f"Output: {output}")


def build_apk(module: str):
    ensure_paths()
    env = find_jdk()
    props = args.config.resolve()

    # Write flags.prop for Gradle (read by Plugin.kt as Config.version, etc.)
    gradle_build_dir = Path("app", "build")
    gradle_build_dir.mkdir(mode=0o755, parents=True, exist_ok=True)
    identity = _build_identity()
    write_if_diff(
        gradle_build_dir / "flags.prop",
        f"version={config['version']}\n"
        f"magisk.versionCode={config['versionCode']}\n"
        f"abiList={','.join(build_abis.keys())}\n"
        + "".join(f"{key}={value}\n" for key, value in identity.items()),
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
    return target


def build_app():
    _validate_generated_flags(
        "Build native binaries with the same mode and configuration first."
    )
    build_udonge()
    header("* Building the Reisenless app")
    apk = build_apk(":apk")

    build_type = "release" if args.release else "debug"

    # Rename apk-variant.apk to app-variant.apk
    source = apk
    target = apk.parent / apk.name.replace("apk-", "app-")
    mv(source, target)
    header(f"Output: {target}")

    # Stub building is directly integrated into the main app
    # build process. Copy the stub APK into output directory.
    source = Path("app", "core", "src", build_type, "assets", "stub.apk")
    target = config["outdir"] / f"stub-{build_type}.apk"
    cp(source, target)


def build_stub():
    header("* Building the stub app")
    apk = build_apk(":stub")
    header(f"Output: {apk}")


################
# Build General
################


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


############
# Utilities
############


def gen_ide():
    ensure_paths()
    set_build_abis({args.abi})

    # Dump flags for both C++ and Rust code
    dump_flag_header()

    # Run build.rs to generate Rust/C++ FFI bindings
    os.chdir(Path("native", "src"))
    run_cargo(["check"])
    os.chdir(Path("..", ".."))

    # Generate compilation database
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


def clippy_cli():
    ensure_toolchain()
    global force_out
    force_out = True
    if args.abi:
        set_build_abis(set(args.abi))
    else:
        set_build_abis(default_abis)

    if not args.release and not args.debug:
        # If none is specified, run both
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
            # Python 3.14 may need to seek backwards while resolving links in
            # the archive, which is impossible with tarfile's streaming mode.
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

    # Build rustup-wrapper
    wrapper_src = Path("tools", "rustup-wrapper")
    cargo_toml = wrapper_src / "Cargo.toml"
    cmds = ["build", "--release", f"--manifest-path={cargo_toml}"]
    if args.verbose > 1:
        cmds.append("--verbose")
    run_cargo(cmds)

    # Replace rustup with wrapper
    wrapper = wrapper_dir / (f"rustup{EXE_EXT}")
    wrapper.unlink(missing_ok=True)
    cp(wrapper_src / "target" / "release" / (f"rustup-wrapper{EXE_EXT}"), wrapper)
    wrapper.chmod(0o755)


##################
# AVD and testing
##################


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

    # Extract busybox from APK
    busybox = Path(config["outdir"], "busybox")
    with ZipFile(apk) as zf:
        with zf.open(f"lib/{abi}/libbusybox.so") as libbb:
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


##########################
# Config, paths, argparse
##########################


def ensure_paths():
    global sdk_path, ndk_root, ndk_path, rust_sysroot
    global ndk_build, gradlew, adb_path

    # Skip if already initialized
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


# We allow using several functionality with only ADB
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
    # Try to convert several aliases to real ABI
    abis = {abi_alias.get(k, k) for k in abis}
    # Check any unknown ABIs
    for k in abis - support_abis.keys():
        error(f"Unknown ABI: {k}")
    build_abis = {k: support_abis[k] for k in support_abis if k in abis}


def load_config():
    commit_hash = cmd_out(["git", "rev-parse", "--short=8", "HEAD"])
    commit_timestamp = cmd_out(["git", "show", "-s", "--format=%ct", "HEAD"])

    # Default values
    config["version"] = commit_hash
    config["versionCode"] = 1000000
    config["outdir"] = "out"

    # Load prop files
    if args.config.exists():
        config.update(parse_props(args.config))

    gradle_props = Path("app", "gradle.properties")
    if gradle_props.exists():
        for key, value in parse_props(gradle_props).items():
            if key.startswith("magisk."):
                config[key[7:]] = value

    # Keep app and native metadata deterministic for the exact source commit.
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

    app_parser = subparsers.add_parser("app", help="build the Reisenless app")

    stub_parser = subparsers.add_parser("stub", help="build the stub app")

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

    # Set callbacks
    all_parser.set_defaults(func=build_all)
    native_parser.set_defaults(func=build_native)
    cargo_parser.set_defaults(func=cargo_cli)
    clippy_parser.set_defaults(func=clippy_cli)
    rustup_parser.set_defaults(func=setup_rustup)
    gen_parser.set_defaults(func=gen_ide)
    app_parser.set_defaults(func=build_app)
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
