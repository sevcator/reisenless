#!/usr/bin/env python3
import os
import re
import sys
import json
import random
import string
import argparse
import subprocess
from pathlib import Path


ROOT = Path(__file__).parent.resolve()
SETUP_KT      = ROOT / "app/buildSrc/src/main/java/Setup.kt"
APK_STRINGS   = ROOT / "app/apk/src/main/res/values/strings.xml"
LOCAL_PROPS   = ROOT / "local.properties"
STATE_FILE    = ROOT / ".personalize.json"
CONSTS_HPP    = ROOT / "native/src/include/consts.hpp"
CONSTS_RS     = ROOT / "native/src/include/consts.rs"

ORIGINAL_PKG  = "com.topjohnwu.magisk"
ORIGINAL_NAME = "Magisk"
DEFAULT_PREFIX = "ms"


PRESETS = [
    ("com.android.systemui.manager",    "System UI Manager"),
    ("com.android.phone.updater",       "Phone Updater"),
    ("com.google.android.gms.helper",   "GMS Helper"),
    ("com.android.security.service",    "Security Service"),
    ("com.android.device.manager",      "Device Manager"),
    ("com.android.storage.optimizer",   "Storage Optimizer"),
    ("com.android.settings.helper",     "Settings Helper"),
    ("com.android.system.patcher",      "System Patcher"),
    ("com.android.kernel.manager",      "Kernel Manager"),
    ("com.android.overlay.service",     "Overlay Service"),
]


_NAMESPACES = ["android", "google", "system", "device", "phone", "kernel", "media"]
_NOUNS      = ["manager", "helper", "service", "updater", "patcher", "monitor", "daemon"]

def _rand_pkg() -> tuple[str, str]:
    ns   = random.choice(_NAMESPACES)
    noun = random.choice(_NOUNS)
    tag  = "".join(random.choices(string.ascii_lowercase + string.digits, k=5))
    pkg  = f"com.{ns}.{noun}.{tag}"
    name = f"{ns.title()} {noun.title()}"
    return pkg, name


def _patch_native_prefix(prefix: str) -> None:
    old = DEFAULT_PREFIX


    if CONSTS_HPP.exists():
        text = CONSTS_HPP.read_text("utf-8")
        text = text.replace(f'SECURE_DIR "/{old}"',    f'SECURE_DIR "/{prefix}"')
        text = text.replace(f'SECURE_DIR "/{old}.db"', f'SECURE_DIR "/{prefix}.db"')

        text = re.sub(
            r'(#define\s+INTLROOT\s+")' + re.escape(f'.{old}') + r'"',
            r'\g<1>.' + prefix + '"',
            text,
        )
        text = re.sub(
            r'(#define\s+SEPOL_PROC_DOMAIN\s+")' + re.escape(old) + r'"',
            r'\g<1>' + prefix + '"',
            text,
        )
        text = re.sub(
            r'(#define\s+SEPOL_FILE_TYPE\s+")' + re.escape(old) + r'_file"',
            r'\g<1>' + prefix + '_file"',
            text,
        )
        CONSTS_HPP.write_text(text, "utf-8")
        print(f"[+] consts.hpp     prefix '{old}' ->'{prefix}'")


    if CONSTS_RS.exists():
        text = CONSTS_RS.read_text("utf-8")
        text = text.replace(f'SECURE_DIR, "/{old}")',    f'SECURE_DIR, "/{prefix}")')
        text = text.replace(f'SECURE_DIR, "/{old}.db")', f'SECURE_DIR, "/{prefix}.db")')
        text = re.sub(
            r'(INTERNAL_DIR:\s*&str\s*=\s*")' + re.escape(f'.{old}') + r'"',
            r'\g<1>.' + prefix + '"',
            text,
        )
        text = re.sub(
            r'(SEPOL_PROC_DOMAIN:\s*&str\s*=\s*")' + re.escape(old) + r'"',
            r'\g<1>' + prefix + '"',
            text,
        )
        text = re.sub(
            r'(SEPOL_FILE_TYPE:\s*&str\s*=\s*")' + re.escape(old) + r'_file"',
            r'\g<1>' + prefix + '_file"',
            text,
        )
        text = re.sub(
            r'(SEPOL_LOG_TYPE:\s*&str\s*=\s*")' + re.escape(old) + r'_log_file"',
            r'\g<1>' + prefix + '_log_file"',
            text,
        )
        CONSTS_RS.write_text(text, "utf-8")
        print(f"[+] consts.rs      prefix '{old}' ->'{prefix}'")


def _patch_setup_kt(pkg: str) -> None:
    text = SETUP_KT.read_text("utf-8")

    patched, n = re.subn(
        r'(applicationId\s*=\s*)"[^"]*"',
        f'\\1"{pkg}"',
        text
    )
    if n == 0:
        print("[!] applicationId not found in Setup.kt — skipping")
        return
    SETUP_KT.write_text(patched, "utf-8")
    print(f"[+] applicationId  ->{pkg}")


def _patch_apk_strings(name: str) -> None:
    if not APK_STRINGS.exists():
        return
    text = APK_STRINGS.read_text("utf-8")

    patched, n = re.subn(
        r'(<string name="magisk">)[^<]*(</string>)',
        f'\\g<1>{name}\\2',
        text
    )
    if n:
        APK_STRINGS.write_text(patched, "utf-8")
        print(f"[+] App label      ->{name}")


def _save_state(pkg: str, name: str, prefix: str = DEFAULT_PREFIX) -> None:
    STATE_FILE.write_text(json.dumps({"pkg": pkg, "name": name, "prefix": prefix}, indent=2), "utf-8")


def _load_state() -> dict:
    if STATE_FILE.exists():
        return json.loads(STATE_FILE.read_text("utf-8"))
    return {}


def _keytool_available() -> bool:
    try:
        subprocess.run(["keytool", "-version"], capture_output=True, check=True)
        return True
    except (FileNotFoundError, subprocess.CalledProcessError):
        return False


def _gen_keystore(path: Path, alias: str, pw: str) -> bool:
    if not _keytool_available():
        print("[!] keytool not found — install JDK to generate a keystore")
        return False
    cmd = [
        "keytool", "-genkey", "-v",
        "-keystore", str(path),
        "-alias", alias,
        "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10000",
        "-storepass", pw, "-keypass", pw,
        "-dname", "CN=Unknown, OU=Unknown, O=Unknown, L=Unknown, ST=Unknown, C=US",
    ]
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode == 0:
        print(f"[+] Keystore       ->{path}")
        return True
    print(f"[!] keytool failed:\n{r.stderr.decode()}")
    return False


def _write_signing_props(path: Path, alias: str, pw: str) -> None:
    existing = LOCAL_PROPS.read_text("utf-8") if LOCAL_PROPS.exists() else ""

    existing = re.sub(
        r"\n?# --- personalize signing ---.*?# --- end personalize signing ---\n?",
        "",
        existing,
        flags=re.DOTALL,
    )
    block = (
        "\n# --- personalize signing ---\n"
        f"SIGNING_STORE_FILE={path}\n"
        f"SIGNING_STORE_PASSWORD={pw}\n"
        f"SIGNING_KEY_ALIAS={alias}\n"
        f"SIGNING_KEY_PASSWORD={pw}\n"
        "# --- end personalize signing ---\n"
    )
    LOCAL_PROPS.write_text(existing + block, "utf-8")
    print("[+] Signing config ->local.properties")


def do_reset() -> None:
    state = _load_state()
    _patch_setup_kt(ORIGINAL_PKG)
    if "name" in state:
        _patch_apk_strings(ORIGINAL_NAME)

    if LOCAL_PROPS.exists():
        text = LOCAL_PROPS.read_text("utf-8")
        text = re.sub(
            r"\n?# --- personalize signing ---.*?# --- end personalize signing ---\n?",
            "",
            text,
            flags=re.DOTALL,
        )
        LOCAL_PROPS.write_text(text, "utf-8")
    if STATE_FILE.exists():
        STATE_FILE.unlink()
    print("[OK] Reset to original Magisk identity")


def main() -> None:
    ap = argparse.ArgumentParser(
        description="Personalize Magisk Alpha: unique package name + signing key per user"
    )
    ap.add_argument("--package",       metavar="PKG",  help="Custom application ID")
    ap.add_argument("--name",          metavar="NAME", help="Custom app label")
    ap.add_argument("--preset",        action="store_true", help="Pick a random realistic preset")
    ap.add_argument("--native-prefix", metavar="PFX",  help="Native path/domain prefix (e.g. 'ab3x'). Default: random 4-char hex")
    ap.add_argument("--keystore",      metavar="PATH", default="signing.keystore",
                    help="Keystore output path (default: signing.keystore)")
    ap.add_argument("--native-only",   action="store_true", help="Only randomize native prefix; skip app ID/label/keystore")
    ap.add_argument("--reset",         action="store_true", help="Restore original identity")
    args = ap.parse_args()

    if args.reset:
        do_reset()
        return


    if args.preset:
        pkg, name = random.choice(PRESETS)
    elif args.package or args.name:
        pkg  = args.package or ORIGINAL_PKG
        name = args.name    or ORIGINAL_NAME
    else:
        pkg, name = _rand_pkg()


    if args.native_prefix:
        prefix = args.native_prefix
    else:
        prefix = "".join(random.choices(string.ascii_lowercase + string.digits, k=4))

    print(f"\n=== Magisk Alpha Personalizer ===")

    if args.native_only:
        print(f"Prefix  : {prefix}  (/data/adb/{prefix}, SELinux: u:r:{prefix}:s0)")
        print()
        _patch_native_prefix(prefix)
        print(f"\n[OK] Done!  Build with:  python build.py -vr all")
        return

    print(f"Package : {pkg}")
    print(f"Label   : {name}")
    print(f"Prefix  : {prefix}  (native paths: /data/adb/{prefix}, SELinux: u:r:{prefix}:s0)")
    print()

    _patch_setup_kt(pkg)
    _patch_apk_strings(name)
    _patch_native_prefix(prefix)
    _save_state(pkg, name, prefix)


    ks_path = Path(args.keystore).resolve()
    alias   = "app"
    pw      = "".join(random.choices(string.ascii_letters + string.digits, k=20))

    if not ks_path.exists():
        ok = _gen_keystore(ks_path, alias, pw)
    else:
        print(f"[~] Reusing keystore: {ks_path}")

        if LOCAL_PROPS.exists():
            for line in LOCAL_PROPS.read_text("utf-8").splitlines():
                if line.startswith("SIGNING_STORE_PASSWORD="):
                    pw = line.split("=", 1)[1].strip()
                    break
        ok = True

    if ok:
        _write_signing_props(ks_path, alias, pw)

    print(f"\n[OK] Done!  Build with:  python build.py -vr all")
    print(f"    Run again any time to rotate to a new identity.")
    print(f"    To restore original:  python personalize.py --reset\n")


if __name__ == "__main__":
    main()
