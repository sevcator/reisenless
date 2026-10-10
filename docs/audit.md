# Reisenless audit

The review covers the build scripts and workflow, Android app and shared core,
native module activation, Udonge hooks and shell workers. Existing uncommitted
fixes and their regressions are included in the update.

## Resulting behavior

- Udonge defaults to off. Its boot and service entry points require explicit
  enablement and reject disabled or pending-reboot profiles before doing work.
- Disabling the profile stops its workers. Disabled ROM hiding clears stale
  runtime keywords. Daily background work remains a separate opt-in choice.
- Udonge no longer enables USB debugging, starts `adbd`, rewrites USB settings,
  or deletes unrelated files under `/data/local/tmp`.
- Module processing takes the minimal path when all modules are disabled or
  Zygisk-only modules are inactive. Pending removals and updates still run.
- The app and daemon agree on Zygisk defaulting to off, including emulators.
- The modern manager skips automatic update requests for offline, disabled,
  removed or incompatible modules and clears stale module listings when the
  root environment becomes inactive.
- Package metadata and icons are cached; terminal output is bounded; downloads
  and WebUI commands have owned cancellation; native snapshots and worker locks
  retain the existing race and lifetime fixes covered by the regression suite.

## Comments and repository cleanup

`COMMENTARIES.txt` contains 994 archive entries with original source locations.
Language-parser checks compared the surrounding syntax before and after
removal. Python documentation strings were removed with AST equivalence checks.
Shebangs remain executable; compiler-consumed Rust documentation is expressed
as attributes. Upstream submodules, binary assets and Markdown documentation
retain their original contents.

Local reference downloads, browser scratch data, old audit plans, diagnostic
dumps, build outputs, local configuration and signing material are excluded from
publication. Required source, tests, assets, submodules and licenses remain.

## Validation

Local verification includes the complete signed release build, APK signer and
embedded trust-anchor checks, native certificate parsing, payload and identity
audits, Android unit tests and lint, and Rust Clippy for debug and release on
ARM, ARM64, x86 and x86_64. The Android suite contains 62 tests; the optional
device test is skipped when no usable ADB device is available.

Host checks include 7 build-command tests, 31 worker tests, 2 feature-activation
tests, 14 native authorization/certificate tests, module directory fixtures,
framework reflection and package-policy fixtures, the real grant method with
authentication doubles, and download scheduling/session ownership checks.
Python, XML, TOML and shell syntax are also checked.

The Actions workflow runs release builds, host regressions, Android tests and
lint, and Rust Clippy, then uploads both manager APKs and test/lint reports.
Signing secrets remain external to the repository.

The ADB device was initially offline. After reconnection, the rooted Pixel 9a
passed worker singleton and owned-child shutdown checks, persistent-service
ownership checks, native file-cache and hook regressions, and the previously
skipped WebUI command-cancellation test. All 20 APK unit tests passed with no
skips, and APK lint completed without errors. Both optional boot/service entry
points also exited without creating state or workers for unrequested, disabled
and pending-reboot fixture profiles. Temporary device fixtures were removed.

Sleep/lock verification deferral was skipped because the device was awake and
unlocked; the host regression suite covers that branch. The installed daemon
still reported build `9477a9dc`. No new installation, flash or reboot was
performed, so boot activation of the updated build remains unverified. Battery
consumption, every device/ROM combination and remote integrity acceptance are
not established by these checks. Android lint retains existing warnings; checks
complete without errors. Rust Clippy completes without warnings.
