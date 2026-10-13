# MIUI additional setup: persistent `/data/adb` policy failure

Investigated and tested on a Xiaomi Mi 10T Pro (`apollo`), MIUI
`V14.0.4.0.SJDMIXM`, Android 12, on 2026-10-12. Changes are based on
repository revision `19c70e92`.

## Recorded failure and matching code path

The saved stock recovery log at
`out/mi10t-recovery/20261011-221404/stock-recovery.log` records:

```text
reason is [set_policy_failed:/data/adb]
Command: "/system/bin/recovery" "--prompt_and_wipe_data" "--reason=set_policy_failed:/data/adb"
```

The saved data layout from the same investigation shows `/data/adb` containing
`tricky_store -> /data/.gacl/.thj/state`.

The additional installation cleanup in `scripts/app_functions.sh` could remove
the empty OEM `/data/adb` directory in both `cleanup_migrated_layout` and
`cleanup_obsolete_frameworks`. Udonge's `service.sh` could then recreate it and
populate it with its TEE state symlink. Removing the original directory removes
its inode and encryption policy. A recreated, populated directory can prevent
MIUI from applying its required fscrypt policy at the next boot.

This source path matches the recorded recovery failure and explains its
persistence after restoring stock boot: the changed directory is on `/data`.
The recovered log establishes an encryption-policy failure; it does not establish
that modem NV partitions were corrupted. The original directory's policy before
the historical failure was not captured, so the exact deletion/recreation
sequence is reconstructed from the code and saved layout.

## Changes

- Preserve the canonical `/data/adb` directory during migration and foreign
  framework cleanup. Continue cleaning the identified framework contents and
  owned private legacy directories.
- Remove Udonge's creation of the canonical directory and TEE symlink.
- Rewrite native TEE library paths as well as DEX paths to the private Udonge
  state directory. Preserve ELF byte lengths and regenerate DEX checksums.
- Package `udonge.bin` from the configured output directory. A custom output
  configuration previously selected an older payload from the default `out`.
- Start Udonge at boot completion when the normal late-start stage has not run.
  Preserve the safe-mode restriction and handle boot completion only once.
- Confirm a stable boot independently of keybox downloads and TEE startup.
  Check the boot ID, `system_server` PID/start time, enabled state, and boot
  completion for two minutes before clearing the matching pending marker.
- Restore the manager's signature permission for non-exported dynamic receivers.
  Removing that permission caused the superuser app picker to crash on this
  Android 12 phone. Release validation now requires its declaration and use.

At this initial repair stage the TEE simulator remained disabled on MIUI.
The later attestation loading repair replaces that restriction with guarded
startup; see [Udonge integrity investigation](udonge-integrity.md). OEM boot
properties and services remain protected.

## Verification

Ten regression tests in `tests/test_udonge_storage.py` pass. The directory
preservation tests fail against the original cleanup functions, demonstrating
the regression. Tests also cover private legacy cleanup, actual native TEE
libraries, DEX checksums, path size constraints, stable boot confirmation,
`system_server` restart rejection, and preservation of another boot's marker.

The release native daemon, modern manager, and legacy manager compile. Release
signing, embedded trust, payload, identity, native alignment, and manifest checks
pass. Android app lint and arm64 release Clippy pass. Shell syntax, Python syntax,
and Git whitespace checks pass.

The final APK and boot image are in `out/mi10t-storage/`. Boot verification checks
that the matching stock kernel, DTB, command line, and layout are unchanged;
encryption and verity are retained; and embedded native binaries, stub, and
Udonge payload match the APK. The final boot SHA-256 is:

```text
b0ff72ffc673b42adb3aece1f9f101f0bcc383c0a44a2dd855f23d955c48fa4f
```

The corrected manager is installed and the verified image is flashed. Additional
setup completed, including a final refresh using the same `fix_env` function to
install the rebuilt binaries. Runtime checks confirm:

- MIUI boots, `system_server` remains stable, and both SIMs reach `LOADED`.
- Udonge is enabled, with no disabled or failed-boot marker; its healthy boot
  marker clears automatically.
- `/data/adb` remains empty, has inode `453`, and retains its encrypted attribute
  and `adb_data_file` SELinux label across setup and reboot.
- The flashed boot partition, installed daemon, runtime service, and payload ID
  match the final build.
- SELinux remains enforcing and the device retains its Xiaomi MIUI fingerprint.
- The manager starts without an additional-setup prompt and its superuser picker
  works with the required signature permission granted.

The final image passed two consecutive boots, including a reboot after the final
additional-setup refresh. Saved measurements are
`final-image-verified-runtime.json` and `final-repeat-verified-runtime.json` in
`out/mi10t-storage/`. The repeated boot retained the same encrypted directory
inode, both loaded SIMs, and enabled Udonge; the pending marker cleared again.

The temporary superuser grant to Android Shell used for runtime inspection was
revoked after verification.

The device checks establish success on this phone and firmware. They do not
establish compatibility with every MIUI build or a Play Integrity verdict.
No factory reset or modem partition write was performed during this repair.
