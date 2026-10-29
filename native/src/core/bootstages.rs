use crate::consts::{
    APP_PACKAGE_NAME, BBPATH, BIN32_DATABIN_NAME, BUILD_BUSYBOX_NAME, DATABIN, MAGISK_VER_CODE,
    MAGISK_VERSION, MAIN_BIN_NAME, MAIN_BIN_NAME_32, MODULEROOT, POLICY_BIN_NAME,
    POLICY_DATABIN_NAME, SECURE_DIR,
};
use crate::daemon::MagiskD;
use crate::ffi::{
    DbEntryKey, DbValues, RequestCode, check_key_combo, exec_common_scripts, exec_module_scripts,
    get_magisk_tmp, initialize_denylist,
};
use crate::module::disable_modules;
use crate::mount::{clean_mounts, setup_preinit_dir};
use crate::resetprop::get_prop;
use crate::selinux::restorecon;
use base::const_format::concatcp;
use base::{BufReadExt, Directory, FsPathBuilder, ResultExt, Utf8CStr, cstr, error, info};
use bitflags::bitflags;
use nix::fcntl::OFlag;
use std::io::BufReader;
use std::os::unix::net::UnixStream;
use std::os::unix::process::CommandExt;
use std::process::{Command, Stdio};
use std::sync::atomic::Ordering;

bitflags! {
    #[derive(Default)]
    pub struct BootState : u32 {
        const PostFsDataDone = 1 << 0;
        const LateStartDone = 1 << 1;
        const BootComplete = 1 << 2;
        const SafeMode = 1 << 3;
    }
}

impl MagiskD {
    fn setup_magisk_env(&self) -> bool {
        info!("* Initializing Magisk environment");

        let mut buf = cstr::buf::default();

        let app_bin_dir = buf
            .append_path(self.app_data_dir())
            .append_path("0")
            .append_path(APP_PACKAGE_NAME)
            .append_path("install");

        let busybox = cstr!(concatcp!(DATABIN, "/", BUILD_BUSYBOX_NAME));
        if !busybox.exists() && app_bin_dir.exists() {
            if app_bin_dir.copy_to(cstr!(DATABIN)).is_err() {
                return false;
            }
            if busybox.exists() {
                app_bin_dir.remove_all().log_ok();
            }
        }

        cstr!(SECURE_DIR).follow_link().chmod(0o700).log_ok();
        cstr!(DATABIN).mkdir(0o755).log_ok();
        cstr!(MODULEROOT).mkdir(0o755).log_ok();
        cstr!(concatcp!(SECURE_DIR, "/post-fs-data.d"))
            .mkdir(0o755)
            .log_ok();
        cstr!(concatcp!(SECURE_DIR, "/service.d"))
            .mkdir(0o755)
            .log_ok();
        restorecon();

        let adb_dir = cstr!("/data/adb");
        adb_dir.mkdir(0o700).log_ok();
        adb_dir.follow_link().chmod(0o700).log_ok();

        fn ensure_adb_symlink(source: &Utf8CStr, target: &Utf8CStr) {
            if let Ok(attr) = source.get_attr() {
                if attr.is_dir() {
                    if let Ok(mut dir) = Directory::open(source) {
                        let is_empty = dir.read().ok().flatten().is_none();
                        if is_empty {
                            source.remove_all().log_ok();
                            source.create_symlink_to(target).log_ok();
                        }
                    }
                } else if attr.is_symlink() {
                    let mut current = cstr::buf::new::<128>();
                    if source.read_link(&mut current).is_ok() && current.as_cstr() != target {
                        source.remove().log_ok();
                        source.create_symlink_to(target).log_ok();
                    }
                }
            } else {
                source.create_symlink_to(target).log_ok();
            }
        }

        ensure_adb_symlink(cstr!("/data/adb/magisk"), cstr!(DATABIN));
        ensure_adb_symlink(cstr!("/data/adb/modules"), cstr!(MODULEROOT));
        ensure_adb_symlink(
            cstr!("/data/adb/modules_update"),
            cstr!(concatcp!(SECURE_DIR, "/modules_update")),
        );
        ensure_adb_symlink(
            cstr!("/data/adb/post-fs-data.d"),
            cstr!(concatcp!(SECURE_DIR, "/post-fs-data.d")),
        );
        ensure_adb_symlink(
            cstr!("/data/adb/service.d"),
            cstr!(concatcp!(SECURE_DIR, "/service.d")),
        );

        if !busybox.exists() {
            return false;
        }

        let tmp_bb = buf.append_path(get_magisk_tmp()).append_path(BBPATH);
        tmp_bb.mkdirs(0o755).ok();
        tmp_bb.append_path(BUILD_BUSYBOX_NAME);
        busybox.copy_to(tmp_bb).ok();
        tmp_bb.follow_link().chmod(0o755).log_ok();

        Command::new(&tmp_bb)
            .arg0("busybox")
            .arg("--install")
            .arg("-s")
            .arg(tmp_bb.parent_dir().unwrap_or_default())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .log_ok();

        let bb_symlink = buf
            .append_path(get_magisk_tmp())
            .append_path(BBPATH)
            .append_path("busybox");
        if !bb_symlink.exists() {
            bb_symlink
                .create_symlink_to(cstr!(concatcp!("./", BUILD_BUSYBOX_NAME)))
                .log_ok();
        }

        let databin_bb = cstr!(concatcp!(DATABIN, "/busybox"));
        if !databin_bb.exists() {
            databin_bb
                .create_symlink_to(cstr!(concatcp!("./", BUILD_BUSYBOX_NAME)))
                .log_ok();
        }

        let legacy_magisk_dir = cstr!(concatcp!(SECURE_DIR, "/magisk"));
        if !legacy_magisk_dir.exists() {
            legacy_magisk_dir.create_symlink_to(cstr!(DATABIN)).log_ok();
        }

        let bin32 = cstr!(concatcp!(DATABIN, "/", BIN32_DATABIN_NAME));
        if bin32.exists() {
            let tmp = buf
                .append_path(get_magisk_tmp())
                .append_path(MAIN_BIN_NAME_32);
            bin32.copy_to(tmp).log_ok();
        }
        let mpol = cstr!(concatcp!(DATABIN, "/", POLICY_DATABIN_NAME));
        if mpol.exists() {
            let tmp = buf
                .append_path(get_magisk_tmp())
                .append_path(POLICY_BIN_NAME);
            mpol.copy_to(tmp).log_ok();
        }

        let resetprop = buf.append_path(get_magisk_tmp()).append_path("resetprop");
        if !resetprop.exists() {
            resetprop
                .create_symlink_to(cstr!(concatcp!("./", MAIN_BIN_NAME)))
                .log_ok();
        }

        true
    }

    fn post_fs_data(&self) -> bool {
        info!("** post-fs-data mode running");

        self.preserve_stub_apk();

        let secure_dir = cstr!(SECURE_DIR);
        if !secure_dir.exists() {
            secure_dir.mkdir(0o700).log_ok();
            if !secure_dir.exists() {
                error!("* {} is not present and cannot be created, abort", SECURE_DIR);
                return true;
            }
        }

        self.prune_su_access();

        if !self.setup_magisk_env() {
            error!("* Magisk environment incomplete, abort");
            return true;
        }

        let boot_cnt = self.get_db_setting(DbEntryKey::BootloopCount);
        self.set_db_setting(DbEntryKey::BootloopCount, boot_cnt + 1)
            .log()
            .ok();
        let safe_mode = boot_cnt >= 2
            || get_prop(cstr!("persist.sys.safemode")) == "1"
            || get_prop(cstr!("ro.sys.safemode")) == "1"
            || check_key_combo();

        if safe_mode {
            info!("* Safe mode triggered");

            disable_modules();
            self.set_db_setting(DbEntryKey::ZygiskConfig, 0).log_ok();

            self.zygisk_enabled.store(false, Ordering::Release);
            self.zygote_injection_enabled
                .store(false, Ordering::Release);
            self.handle_modules();
            clean_mounts();
            return true;
        }

        exec_common_scripts(cstr!("post-fs-data"));

        let features = self.get_db_settings().unwrap_or_default();
        let enable_zygisk = features.zygisk;
        self.zygisk_enabled
            .store(enable_zygisk, Ordering::Release);

        self.zygote_injection_enabled.store(
            enable_zygisk,
            Ordering::Release,
        );
        initialize_denylist(features.sulist);
        self.handle_modules();
        clean_mounts();

        false
    }

    fn late_start(&self) {
        info!("** late_start service mode running");

        exec_common_scripts(cstr!("service"));
        if let Some(module_list) = self.module_list.get()
            && module_list
                .iter()
                .any(|module| !module.name.starts_with('@'))
        {
            exec_module_scripts(cstr!("service"), module_list);
        }
    }

    fn boot_complete(&self) {
        info!("** boot-complete triggered");

        self.set_db_setting(DbEntryKey::BootloopCount, 0).log_ok();

        let secure_dir = cstr!(SECURE_DIR);
        if !secure_dir.exists() {
            secure_dir.mkdir(0o700).log_ok();
        }

        setup_preinit_dir();
        self.ensure_manager();
        if self.zygote_injection_enabled.load(Ordering::Relaxed) {
            self.zygisk.lock().reset(true);
        }
    }

    fn cleanup_upgrade(&self) {
        let mut valid = false;
        let mut rows = 0;
        let mut check = |_: &[String], values: &DbValues| {
            valid = values.get_text(0) == "ok";
            rows += 1;
        };
        if self.db_exec_with_rows("PRAGMA quick_check;", &[], &mut check) != 0
            || !valid
            || rows != 1
        {
            error!("* Obsolete root cleanup deferred: database check failed");
            return;
        }
        let result = Command::new(concatcp!(DATABIN, "/", BUILD_BUSYBOX_NAME))
            .arg0("busybox")
            .args([
                "sh",
                "-c",
                ". \"$1/app_functions.sh\" && . \"$1/util_functions.sh\" && \
                 [ \"$MAGISK_VER\" = \"$2\" ] && [ \"$MAGISK_VER_CODE\" = \"$3\" ] && \
                 cleanup_upgrade \"$2\" \"$4\" \"$3\"",
                "upgrade-cleanup",
                DATABIN,
                MAGISK_VERSION,
                &MAGISK_VER_CODE.to_string(),
                APP_PACKAGE_NAME,
            ])
            .env("ASH_STANDALONE", "1")
            .env("ROOT_TMP", get_magisk_tmp().as_str())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .log();
        if result.is_ok_and(|status| status.success()) {
            info!("* Obsolete root installation cleanup complete");
        } else {
            error!("* Obsolete root installation cleanup deferred");
        }
    }

    pub fn boot_stage_handler(&self, client: UnixStream, code: RequestCode) {
        let mut state = self.boot_stage_lock.lock();

        match code {
            RequestCode::POST_FS_DATA => {
                if check_data() && !state.contains(BootState::PostFsDataDone) {
                    if self.post_fs_data() {
                        state.insert(BootState::SafeMode);
                    }
                    state.insert(BootState::PostFsDataDone);
                }
            }
            RequestCode::LATE_START => {
                drop(client);
                if state.contains(BootState::PostFsDataDone) && !state.contains(BootState::SafeMode)
                {
                    self.late_start();
                    state.insert(BootState::LateStartDone);
                }
            }
            RequestCode::BOOT_COMPLETE => {
                drop(client);
                if state.contains(BootState::PostFsDataDone)
                    && !state.contains(BootState::BootComplete)
                {
                    state.insert(BootState::BootComplete);
                    self.boot_complete();
                    if !state.contains(BootState::SafeMode) {
                        self.cleanup_upgrade();
                    }
                }
            }
            _ => {}
        }
    }
}

fn check_data() -> bool {
    if let Ok(file) = cstr!("/proc/mounts").open(OFlag::O_RDONLY | OFlag::O_CLOEXEC) {
        let mut mnt = false;
        BufReader::new(file).for_each_line(|line| {
            if line.contains(" /data ") && !line.contains("tmpfs") {
                mnt = true;
                return false;
            }
            true
        });
        if !mnt {
            return false;
        }
        let crypto = get_prop(cstr!("ro.crypto.state"));
        return if !crypto.is_empty() {
            if crypto != "encrypted" {
                true
            } else {
                !get_prop(cstr!("init.svc.vold")).is_empty()
            }
        } else {
            true
        };
    }
    false
}
