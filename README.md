# Reisenless

Reisenless is an Android root manager based on Magisk, with built-in app hiding
and optional Udonge features. Source and dependency licenses are in `LICENSE`
and `THIRD_PARTY_NOTICES`.

Udonge is off by default. Enable it in Settings and reboot to activate its
optional runtime. Daily background updates and ROM hiding are separate choices.
USB debugging remains under the user's control. Disabled modules do not run,
and third-party Zygisk modules require the Zygisk setting. Zygisk defaults to off
on physical devices and emulators.

For an upgrade, install the new APK over the existing manager, select direct
install, and reboot. Keep the same private identity seed, repository namespace,
and signing key between releases so Android updates the existing package and
the root installation reuses its paths. User settings, modules, and boot recovery
backups are retained; replaced runtime payloads and installation staging are
removed after a successful upgrade. After the new root completes boot, migrated
Magisk and explicitly configured previous private installations are cleaned up.
Obsolete Magisk manager APKs are removed once per upgrade; opening the manager
retries failed APK removals. Conflicting user files are retained under the current
private directory's `.upgrade-preserved`; stock boot backups keep their recovery
copies.

## Build

Use Python 3.12 or newer, a JDK, and an Android SDK. GitHub Actions uses Python
3.14 and JDK 21. Set a private `identitySeed` in `config.prop` or provide
`REISENLESS_IDENTITY_SEED` before release builds. Clone with submodules:

```sh
git clone --recurse-submodules https://github.com/sevcator/reisenless.git
cd reisenless
python build.py ndk
python build.py all -r
```

`build.py` prepares its environment. Prefix standalone compiler and Gradle
commands with `scripts/env.py`; run Gradle commands from `app/`:

```sh
python build.py gen
cd app
python ../scripts/env.py ./gradlew :core:lintDebug :apk:lintDebug :hideapps:lintDebug
```

Windows uses `gradlew.bat`. Set `ANDROID_HOME` to the Android SDK directory for
standalone commands. `config.prop.sample` supplies optional local build settings.
Local configuration, signing material, downloaded reference projects, device
dumps and build outputs are excluded from Git.

## Release signing and CI

Local builds preserve a private signing identity under `.private/manager-signing`.
The Actions workflow requires these repository secrets:

- `REISENLESS_IDENTITY_SEED`
- `REISENLESS_KEYSTORE_BASE64`
- `REISENLESS_KEYSTORE_PASSWORD`
- `REISENLESS_KEY_ALIAS`
- `REISENLESS_KEY_PASSWORD`

`REISENLESS_CERT_SHA256` optionally pins the public certificate digest. Signing
secrets are required for release workflows, including pull-request builds.
Release validation checks the APK signer, embedded trust anchor, native
authorization parser, packaged payload and generated identities. The workflow
also runs Android lint and Rust Clippy.

Builds write local artifacts under `out/`. Device installation is a separate,
explicit action. Removed source comments are archived with their original
locations in [`docs/COMMENTARIES.txt`](docs/COMMENTARIES.txt). External submodules
retain their upstream source.
