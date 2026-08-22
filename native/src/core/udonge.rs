use crate::consts::{
    BBPATH, BUILD_BUSYBOX_NAME, BUILD_UDONGE_ARCHIVE, BUILD_UDONGE_DIR, DATABIN,
    MAGISK_VERSION, SECURE_DIR,
};
use crate::ffi::{exec_script, exec_script_async, get_magisk_tmp};
use base::const_format::concatcp;
use base::{FsPathBuilder, ResultExt, cstr};
use std::process::{Command, Stdio};

pub const UDONGE_MODULE_NAME: &str = "@udonge";
pub const UDONGE_ROOT: &str = concatcp!(SECURE_DIR, "/", BUILD_UDONGE_DIR);
pub const UDONGE_RUNTIME: &str = concatcp!(UDONGE_ROOT, "/runtime");
const UDONGE_NEXT: &str = concatcp!(UDONGE_ROOT, "/runtime.new");
const UDONGE_OLD: &str = concatcp!(UDONGE_ROOT, "/runtime.old");
const UDONGE_DISABLED: &str = concatcp!(UDONGE_ROOT, "/state/disabled");
const UDONGE_UNLOADED: &str = concatcp!(UDONGE_ROOT, "/state/unloaded");
const HIDEAPPS_GLOBAL_LOADER: &str =
    concatcp!(UDONGE_ROOT, "/state/hideapps-global-loader-v2");

pub fn is_enabled() -> bool {
    runtime_complete(UDONGE_RUNTIME)
        && !cstr!(UDONGE_DISABLED).exists()
        && !cstr!(UDONGE_UNLOADED).exists()
}

pub fn is_hide_apps_target(process: &str) -> bool {
    let package = process.split_once(':').map_or(process, |(package, _)| package);
    let path = format!("{UDONGE_ROOT}/state/hideapps.conf");
    let Ok(config) = std::fs::read_to_string(path) else {
        return false;
    };
    config.lines().any(|line| {
        let mut fields = line.split('\t');
        match fields.next() {
            Some("R") => fields.next() == Some(package),
            Some("G") => {
                let manager = fields.next().unwrap_or_default();
                let hidden = fields.next().unwrap_or_default();
                let exempt = fields.next().unwrap_or_default();
                !hidden.is_empty()
                    && package != manager
                    && !exempt.split(',').any(|entry| entry == package)
            }
            _ => false,
        }
    })
}

fn runtime_file_exists(root: &str, name: &str) -> bool {
    cstr::buf::default()
        .join_path(root)
        .join_path(name)
        .exists()
}

fn runtime_complete(root: &str) -> bool {
    const COMMON: &[&str] = &[
        "version",
        "hideapps.dex",
        "post-fs-data.sh",
        "service.sh",
        "defaults/keybox.xml",
        "defaults/keybox_urls.conf",
        "defaults/pif.conf",
        "defaults/props.conf",
        "defaults/targets.conf",
    ];
    if COMMON.iter().any(|name| !runtime_file_exists(root, name)) {
        return false;
    }

    #[cfg(target_arch = "aarch64")]
    const ABI_FILES: &[&str] = &[
        "zygisk/arm64-v8a.so",
        "tee/arm64-v8a/inject",
        "tee/arm64-v8a/libTEESimulator.so",
        "tee/arm64-v8a/libcertgen.so",
        "tee/arm64-v8a/supervisor",
        "tee/classes.dex",
        "tee/daemon",
    ];
    #[cfg(target_arch = "arm")]
    const ABI_FILES: &[&str] = &[
        "zygisk/armeabi-v7a.so",
        "tee/armeabi-v7a/inject",
        "tee/armeabi-v7a/libTEESimulator.so",
        "tee/armeabi-v7a/supervisor",
        "tee/classes.dex",
        "tee/daemon",
    ];
    #[cfg(target_arch = "x86_64")]
    const ABI_FILES: &[&str] = &["zygisk/x86_64.so"];
    #[cfg(target_arch = "x86")]
    const ABI_FILES: &[&str] = &["zygisk/x86.so"];
    #[cfg(target_arch = "riscv64")]
    const ABI_FILES: &[&str] = &[];

    ABI_FILES.iter().all(|name| runtime_file_exists(root, name))
}

fn runtime_version_matches(root: &str) -> bool {
    let version_path = cstr::buf::default().join_path(root).join_path("version");
    std::fs::read_to_string(&version_path)
        .map(|version| version.trim() == MAGISK_VERSION)
        .unwrap_or(false)
}

pub fn setup_runtime() {
    let buffer = cstr::buf::default();
    let ramdisk_archive = buffer.join_path(get_magisk_tmp()).join_path(BUILD_UDONGE_ARCHIVE);
    let persistent_archive = cstr::buf::default().join_path(DATABIN).join_path(BUILD_UDONGE_ARCHIVE);
    let archive = if ramdisk_archive.exists() {
        &ramdisk_archive
    } else {
        &persistent_archive
    };

    cstr!(UDONGE_ROOT).mkdirs(0o700).log_ok();
    cstr!(UDONGE_ROOT).follow_link().chmod(0o700).log_ok();

    if !cstr!(UDONGE_RUNTIME).exists() && cstr!(UDONGE_OLD).exists() {
        cstr!(UDONGE_OLD)
            .rename_to(cstr!(UDONGE_RUNTIME))
            .log_ok();
    }

    let installed = runtime_complete(UDONGE_RUNTIME)
        && runtime_version_matches(UDONGE_RUNTIME);

    if !installed && archive.exists() {
        cstr!(UDONGE_NEXT).remove_all().ok();
        cstr!(UDONGE_NEXT).mkdirs(0o700).log_ok();

        let busybox = cstr::buf::default()
            .join_path(get_magisk_tmp())
            .join_path(BBPATH)
            .join_path(BUILD_BUSYBOX_NAME);
        let extracted = Command::new(&busybox)
            .arg("unzip")
            .arg("-oq")
            .arg(archive)
            .arg("-d")
            .arg(UDONGE_NEXT)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .map(|status| status.success())
            .unwrap_or(false);
        let verified = extracted
            && runtime_complete(UDONGE_NEXT)
            && runtime_version_matches(UDONGE_NEXT);
        if verified {
            cstr!(UDONGE_OLD).remove_all().ok();
            let backed_up = !cstr!(UDONGE_RUNTIME).exists()
                || cstr!(UDONGE_RUNTIME)
                    .rename_to(cstr!(UDONGE_OLD))
                    .log()
                    .is_ok();
            if backed_up
                && cstr!(UDONGE_NEXT)
                    .rename_to(cstr!(UDONGE_RUNTIME))
                    .log()
                    .is_ok()
            {
                cstr!(UDONGE_OLD).remove_all().ok();
                cstr!(UDONGE_UNLOADED).remove().ok();
            } else {
                if !cstr!(UDONGE_RUNTIME).exists() {
                    cstr!(UDONGE_OLD)
                        .rename_to(cstr!(UDONGE_RUNTIME))
                        .log_ok();
                }
                cstr!(UDONGE_NEXT).remove_all().ok();
            }
        } else {
            cstr!(UDONGE_NEXT).remove_all().ok();
        }
    }

    if runtime_complete(UDONGE_RUNTIME) {
        cstr!(UDONGE_UNLOADED).remove().ok();
        if let Ok(boot_id) = std::fs::read_to_string("/proc/sys/kernel/random/boot_id") {
            std::fs::write(HIDEAPPS_GLOBAL_LOADER, boot_id).log_ok();
            cstr!(HIDEAPPS_GLOBAL_LOADER)
                .follow_link()
                .chmod(0o600)
                .log_ok();
        }
    }

    if is_enabled() {
        let post_fs_data = cstr::buf::default()
            .join_path(UDONGE_RUNTIME)
            .join_path("post-fs-data.sh");
        if post_fs_data.exists() {
            post_fs_data.follow_link().chmod(0o700).log_ok();
            exec_script(&post_fs_data);
        }
    }
}

pub fn run_service() {
    if !runtime_version_matches(UDONGE_RUNTIME) {
        setup_runtime();
    }
    if !is_enabled() {
        return;
    }
    let service = cstr::buf::default()
        .join_path(UDONGE_RUNTIME)
        .join_path("service.sh");
    if service.exists() {
        service.follow_link().chmod(0o700).log_ok();
        exec_script_async(&service);
    }
}
