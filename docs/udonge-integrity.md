# Udonge attestation loading on MIUI

Test device: Xiaomi Mi 10T Pro, `apollo`, MIUI 14 Android 12,
`V14.0.4.0.SJDMIXM`. The requested tester is Play Store > Settings > General >
Developer options > Check integrity.

## Findings and changes

The prior repair disabled the TEE service on every MIUI build. Udonge's enabled
switch therefore did not establish that attestation components had loaded.
Replace that blanket skip with guarded startup after Android boot completion.
Record the keystore process and its start time, and verify the library mapping
and a live service after one minute. Monitor keystore and `system_server`
continuously afterward. Stop the owned service tree and quarantine that payload
if a monitored process restarts or startup fails. Preserve private startup logs.

The bundled DEX also tries to inject into Tencent Soter. A wrapper now admits
only the recorded `/system/bin/keystore` or `/system/bin/keystore2` executable,
with a matching PID and start time. It rejects Soter, system_server, other
processes, malformed arguments, and stale process identities. Do not use
`/proc/PID/comm` for this check: this phone's keystore leader renames itself to
`Binder:PID_2`. Exclude all packages sharing a system UID from the TEE target
list, including OEM packages. Preserve hardware key behavior for untargeted UIDs.

An unsuccessful diagnostic injection was explained by a label mismatch: the
unpackaged source script used the generic library label, while this release's
policy defines a randomized label. AVC logs reported an `unlabeled` file and
rejected FD transfer. Using the configured label permitted FD transfer and
registration. The built payload already performs this substitution; this was
a diagnostic staging error, not an additional defect in the packaged release.

DroidGuard's Zygisk branch previously changed Java Build fields and unloaded
the module without installing native property hooks. Keep the library resident
when a profile is active, and install only its property hooks in that process.
Keep hooks inactive when Udonge/profile spoofing is disabled. Apply the same
restricted hook list to later native libraries loaded by Java Runtime.
DroidGuard's executable APK resides in GMS's private `app_dg_cache`, which the
old `/data/app/` scan omitted. Include private app library mappings for this
restricted hook profile; retain the existing full-hook restrictions elsewhere.

Update the bundled Canary profile from Google's Android Flash Tool metadata:
`ZP11.260918.007/16484274`, Canary 202610. The corresponding Pixel bulletin
specifies `2026-10-05`. Write explicit matching attestation patch levels to
private state rather than `system=prop`, which caused the bundled simulator to
derive boot/vendor patches from MIUI's real global properties. Do not change
MIUI's global fingerprint, boot state, or security patch properties.

The final property check caught another side effect inside the bundled DEX:
its BootStateManager changes global boot flags when `boot_props_mode` is
`force` (and also in normal AUTO mode outside its Oplus exception). Set the
mode explicitly to `off`. Reboot restores the bootloader-provided properties;
the restricted DroidGuard hooks supply only that app's property view. Include
real `orange`, `unlocked`, and flash-lock `0` values in device acceptance checks
instead of relying on the unchanged Xiaomi fingerprint alone.

A service loading failure previously set `tee-broken` and forced synthetic
generation. A loading failure does not establish broken hardware. Delegate
normal selection to the simulator's AUTO capability checks. Reserve forced
generation for the explicit `tee-hardware-broken` override.

## Storage

Udonge's scripts now receive their installation path through the build rather
than a canonical storage fallback. Require the native build to provide its
root macro. The keybox helper resolves only its own runtime parent; it no longer
scans unrelated directories under `/data` or falls back to a canonical root.
The synchronization script no longer searches canonical Magisk storage for
busybox. Native and DEX path substitutions preserve their binary layout and
checksums.

On this deployment all Udonge state remains under `/data/.zsef/.cmh`.
`/data/adb` is MIUI's encrypted directory and must retain its original inode;
it remains empty, with no Udonge alias or payload. Remaining canonical strings
in source describe migration compatibility, detection filters, or upstream
binary tokens to replace. They do not redirect this installation's storage.

## Verification and remaining limitation

Twelve regression tests pass, including positive and negative injection guard
cases, preservation of the encrypted canonical inode, private legacy cleanup,
ELF path lengths, DEX checksums, boot health confirmation, a disabled global boot
modifier, and exclusion of system UIDs in primary and secondary Android users.
The release native
daemon, modern manager, legacy manager, Android lint, and arm64 Clippy pass.
Shell syntax, Python syntax, and whitespace checks pass. Build logs and device
measurements are under `out/mi10t-integrity` and `out/mi10t-storage`.

The installed manager and flashed image match the build. The boot verifier
checks the unchanged stock kernel, DTB, command line, and layout, with encryption
and verity preserved. The current boot SHA-256 is:

```text
ed6f71686370b2aee963850b07f6f0c7dba341f2b7a37ad460b7e2283fcff252
```

On-device logs establish successful FD transfer, Binder interception,
keystore/TEE registration, and loading of `libcertgen.so`. Missing StrongBox
hardware is reported separately and does not prevent TEE initialization.
A read-only inspection of DroidGuard's property relocation confirms that
`__system_property_get` points into Udonge's resident mapped code rather than
the original libc function. Runtime status is `ready`.

Fresh Play Store requests still return only `MEETS_BASIC_INTEGRITY`, before
and after these repairs. **Strong Integrity remains unresolved.** The currently
loaded public certificate chain is unexpired and absent from Google's current
revocation list; that alone does not establish acceptance by Play Integrity.
The Play Store result does not expose a specific server rejection reason.
Do not claim Strong success from local loading or certificate checks.

Device verification confirms SELinux enforcing, unchanged Xiaomi system
identity, both SIMs `LOADED`, stable system services, and empty encrypted
`/data/adb` with inode `453`. No factory reset or modem partition write was
performed. The prior working boot image is retained locally as
`out/mi10t-integrity/previous-stable-boot.img`.

The final image passed two consecutive boots with the global boot modifier off.
Both runs cleared the boot-pending marker, loaded the attestation library, kept
Soter injection absent, and preserved the real boot properties and encrypted
directory inode. Measurements are `private-first-runtime-check.txt` and
`final-runtime-check.txt`; the Play Store verdict is saved in
`play-integrity-private.xml`. The temporary Android Shell superuser policy was
removed after verification.

Profile sources:

- [Android Flash Tool](https://flash.android.com/)
- [Pixel update bulletins](https://source.android.com/docs/security/bulletin/pixel)
- [Play Integrity verdict definitions](https://developer.android.com/google/play/integrity/verdicts)
