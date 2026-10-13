import os
from pathlib import Path
import struct
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
TARGET_ROM = Path(r"C:\Users\Seva\Documents\scrcpy-win64-v4.1\target_rom")
LINEAGE_ROM = Path(r"C:\Users\Seva\Documents\scrcpy-win64-v4.1\lineage-22.2-20260214-nightly-apollon-signed")


class FirmwareCompatibilityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.has_target_rom = TARGET_ROM.exists()
        cls.has_lineage_rom = LINEAGE_ROM.exists()

    def test_target_rom_boot_image_structure(self):
        if not self.has_target_rom:
            self.skipTest("Target ROM directory not available")

        boot_img = TARGET_ROM / "boot.img"
        self.assertTrue(boot_img.exists(), "boot.img must exist in target_rom")

        with open(boot_img, "rb") as f:
            header = f.read(4096)

        self.assertGreaterEqual(len(header), 1660)
        magic, k_sz, k_addr, r_sz, r_addr, s_sz, s_addr, t_addr, page_sz, hdr_ver = struct.unpack(
            "<8s9I", header[:44]
        )

        self.assertEqual(magic, b"ANDROID!", "Boot image magic must be ANDROID!")
        self.assertEqual(hdr_ver, 2, "Xiaomi Mi 10T Pro uses Android boot header v2")
        self.assertEqual(page_sz, 4096, "Boot image page size must be 4096")
        self.assertGreater(k_sz, 30_000_000, "Kernel size must be valid for Qualcomm Kona")
        self.assertGreater(r_sz, 1_000_000, "Ramdisk size must be valid")

        hdr_size = struct.unpack("<I", header[1644:1648])[0]
        self.assertEqual(hdr_size, 1660, "Boot header v2 size must be 1660")
        dtb_size = struct.unpack("<I", header[1648:1652])[0]
        self.assertGreater(dtb_size, 1_000_000, "Header v2 DTB size must be present")

    def test_target_rom_fstab_and_persist_isolation(self):
        if not self.has_target_rom:
            self.skipTest("Target ROM directory not available")

        fstab_path = TARGET_ROM / "extracted/vendor/etc/fstab.qcom"
        self.assertTrue(fstab_path.exists(), "fstab.qcom must exist in extracted vendor")

        content = fstab_path.read_text(encoding="utf-8", errors="ignore")
        self.assertIn("/metadata", content)
        self.assertIn("/data", content)
        self.assertIn("/mnt/vendor/persist", content)

        # Verify Reisenless does NOT use /persist as a preinit candidate
        mount_rs = (ROOT / "native/src/core/mount.rs").read_text(encoding="utf-8")
        self.assertNotIn('"/persist"', mount_rs)
        self.assertNotIn('"/mnt/vendor/persist"', mount_rs)
        self.assertNotIn("PartId::Persist", mount_rs)

    def test_checknv_neutralization(self):
        post_fs_data = (ROOT / "udonge/payload/post-fs-data.sh").read_text(encoding="utf-8")
        self.assertIn("checknv", post_fs_data)
        self.assertIn("mount -o bind /system/bin/true /system_ext/bin/checknv", post_fs_data)

        rules_rs = (ROOT / "native/src/sepolicy/rules.rs").read_text(encoding="utf-8")
        self.assertIn('deny(["init"], ["checknv_exec"], ["file"], ["execute"])', rules_rs)
        self.assertIn('deny(["checknv"], ["powerctl_prop"], ["property_service"], ["set"])', rules_rs)
        self.assertIn('deny(["checknv"], ["recovery_service"], ["service_manager"], ["find"])', rules_rs)

    def test_modem_carrier_service_isolation(self):
        targets_conf = (ROOT / "udonge/payload/defaults/targets.conf").read_text(encoding="utf-8")
        self.assertNotIn("com.google.android.ims", targets_conf.splitlines())

    def test_certified_pif_matches_target_rom(self):
        pif_conf = (ROOT / "udonge/payload/defaults/pif.conf").read_text(encoding="utf-8")
        self.assertNotIn("marlin", pif_conf)
        self.assertIn("Xiaomi/apollopro_global/apollo:12/RKQ1.211001.001/V14.0.4.0.SJDMIXM:user/release-keys", pif_conf)
        self.assertIn("MODEL=M2007J3SG", pif_conf)
        self.assertIn("BRAND=Xiaomi", pif_conf)
        self.assertIn("RELEASE=12", pif_conf)

        if self.has_target_rom:
            pro_prop = TARGET_ROM / "extracted/vendor/pro.build.prop"
            if pro_prop.exists():
                prop_content = pro_prop.read_text(encoding="utf-8", errors="ignore")
                self.assertIn("Xiaomi/apollopro_global/apollo", prop_content)
                self.assertIn("M2007J3SG", prop_content)

    def test_keybox_certificates_present_and_unrevoked(self):
        keybox_file = ROOT / "udonge/payload/defaults/keybox.xml"
        self.assertTrue(keybox_file.exists())

        tree = ET.parse(keybox_file)
        certs = list(tree.iter("Certificate"))
        self.assertGreaterEqual(len(certs), 3, "Keybox must contain complete certificate chains")

        keybox_text = keybox_file.read_text(encoding="utf-8")
        # Ensure blacklisted test serials are not present
        revoked_patterns = ["3207438651777393527", "10843619390107158798", "14765769813626195621159"]
        for revoked in revoked_patterns:
            self.assertNotIn(revoked, keybox_text)

    def test_bootstages_cold_boot_safety(self):
        bootstages_rs = (ROOT / "native/src/core/bootstages.rs").read_text(encoding="utf-8")
        self.assertNotIn('if self.sdk_int < 24', bootstages_rs)
        self.assertIn('secure_dir.mkdir(0o700).log_ok();', bootstages_rs)

    def test_zero_data_adb_invariant(self):
        app_funcs = (ROOT / "scripts/app_functions.sh").read_text(encoding="utf-8")
        self.assertNotIn("/data/adb", app_funcs)

        base_cpp = (ROOT / "native/src/base/base.cpp").read_text(encoding="utf-8")
        self.assertNotIn('"/adb"', base_cpp)

        targets_conf = (ROOT / "udonge/payload/defaults/targets.conf").read_text(encoding="utf-8")
        self.assertNotIn("/data/adb", targets_conf)

        pif_conf = (ROOT / "udonge/payload/defaults/pif.conf").read_text(encoding="utf-8")
        self.assertNotIn("/data/adb", pif_conf)

    def test_sepolicy_keystore2_allowed(self):
        rules_rs = (ROOT / "native/src/sepolicy/rules.rs").read_text(encoding="utf-8")
        self.assertIn('["keystore", "keystore2"]', rules_rs)

    def test_boot_key_bin_matches_target_rom_vbmeta(self):
        boot_key_file = ROOT / "udonge/payload/defaults/boot_key.bin"
        self.assertTrue(boot_key_file.exists(), "boot_key.bin must exist in defaults")
        boot_key_bytes = boot_key_file.read_bytes()
        self.assertEqual(len(boot_key_bytes), 32, "boot_key.bin must be 32 bytes (SHA-256)")
        self.assertEqual(
            boot_key_bytes.hex(),
            "c5d3c71bc70d58e3e0409ca9d9b34c0dbac1d2f09a5de948a4b8f090f1926965",
            "boot_key.bin must match Xiaomi Mi 10T Pro OEM root public key from vbmeta.img"
        )

    def test_keybox_heal_supports_import_strong(self):
        keybox_heal = (ROOT / "udonge/payload/keybox_heal.sh").read_text(encoding="utf-8")
        self.assertIn("import_strong)", keybox_heal)
        self.assertIn("/sdcard/keybox.xml", keybox_heal)
        self.assertIn("MEETS_STRONG_INTEGRITY achieved and locked!", keybox_heal)

    def test_post_fs_data_copies_boot_key(self):
        post_fs_data = (ROOT / "udonge/payload/post-fs-data.sh").read_text(encoding="utf-8")
        self.assertIn("boot_key.bin", post_fs_data)
        self.assertIn("sync_oem_boot_key", post_fs_data)
        self.assertNotIn("sed -i 's/[!?]*$//' \"$state/targets.conf\"", post_fs_data)

    def test_lineage22_boot_image_structure(self):
        if not self.has_lineage_rom:
            self.skipTest("LineageOS 22.2 directory not available")

        boot_img = LINEAGE_ROM / "boot.img"
        self.assertTrue(boot_img.exists(), "boot.img must exist in LineageOS 22.2")

        with open(boot_img, "rb") as f:
            header = f.read(4096)

        magic, k_sz, k_addr, r_sz, r_addr, s_sz, s_addr, t_addr, page_sz, hdr_ver = struct.unpack(
            "<8s9I", header[:44]
        )
        self.assertEqual(magic, b"ANDROID!", "Boot image magic must be ANDROID!")
        self.assertEqual(hdr_ver, 2, "LineageOS 22.2 apollon uses Android boot header v2")
        self.assertEqual(page_sz, 4096, "Boot image page size must be 4096")
        self.assertGreater(k_sz, 40_000_000, "Kernel size must be valid for Qualcomm Kona Snapdragon 865")
        self.assertGreater(r_sz, 1_500_000, "Ramdisk size must be valid")

        hdr_size = struct.unpack("<I", header[1644:1648])[0]
        self.assertEqual(hdr_size, 1660, "Boot header v2 size must be 1660")
        dtb_size = struct.unpack("<I", header[1648:1652])[0]
        self.assertGreater(dtb_size, 1_000_000, "Header v2 DTB size must be present")

    def test_lineage22_vbmeta_avb_structure(self):
        if not self.has_lineage_rom:
            self.skipTest("LineageOS 22.2 directory not available")

        vbmeta = LINEAGE_ROM / "vbmeta.img"
        self.assertTrue(vbmeta.exists(), "vbmeta.img must exist in LineageOS 22.2")
        with open(vbmeta, "rb") as f:
            header = f.read(128)
        self.assertEqual(header[:4], b"AVB0", "vbmeta.img must have AVB 2.0 magic")

    def test_lineage22_target_metadata_and_sdk(self):
        if not self.has_lineage_rom:
            self.skipTest("LineageOS 22.2 directory not available")

        metadata_file = LINEAGE_ROM / "META-INF/com/android/metadata"
        self.assertTrue(metadata_file.exists())
        content = metadata_file.read_text(encoding="utf-8")
        self.assertIn("post-sdk-level=35", content, "LineageOS 22.2 must be Android 15 (SDK 35)")
        self.assertIn("pre-device=apollon,apollo,apollopro", content)
        self.assertIn("Xiaomi/apollo_global/apollo:12/RKQ1.211001.001/V14.0.4.0.SJDMIXM", content)

    def test_lineage_property_cloaking_coverage(self):
        hooks_cpp = (ROOT / "udonge/native/hooks.cpp").read_text(encoding="utf-8")
        self.assertIn('strncmp(name, "ro.lineage", 10) == 0', hooks_cpp)

        spoof_cpp = (ROOT / "udonge/native/spoof.cpp").read_text(encoding="utf-8")
        self.assertIn("LINEAGE_APK_PATH", spoof_cpp)
        self.assertIn("lineageos/os/Build", spoof_cpp)

    def test_randomized_identities_unique_and_migratable(self):
        import build
        build.args = lambda: None
        build.args.config = ROOT / "config.prop"
        build.args.release = True
        build.args.verbose = 0
        build.load_config()

        ident = build._build_identity()
        secure_dir = ident["secureDir"]
        self.assertTrue(secure_dir.startswith("/data/."), f"Secure dir must start with /data/., got {secure_dir}")
        self.assertNotEqual(secure_dir, "/data/adb", "Secure dir must NEVER be /data/adb")
        self.assertNotEqual(secure_dir, "/data/.ms", "Secure dir must be randomized in release")

        # Verify legacy migration paths are preserved in config
        self.assertEqual(build.config.get("legacySecureDir"), "/data/.zsef")
        self.assertEqual(build.config.get("legacyDbName"), ".samudwxp")
        self.assertNotEqual(secure_dir, build.config.get("legacySecureDir"))


if __name__ == "__main__":
    unittest.main()
