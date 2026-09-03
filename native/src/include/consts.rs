#![allow(dead_code)]
use base::const_format::concatcp;

#[path = "../../out/generated/flags.rs"]
mod flags;

pub const POST_FS_DATA_WAIT_TIME: i32 = 40;
pub const APPLET_NAMES: &[&str] = &["su", "resetprop"];


pub use flags::*;
pub const MAGISK_FULL_VER: &str = concatcp!(MAGISK_VERSION, "(", MAGISK_VER_CODE, ")");

pub const APP_PACKAGE_NAME: &str = BUILD_APP_PACKAGE_NAME;


pub const REDIR_PATH: &str = BUILD_REDIR_PATH;

pub const POLICY_DATABIN_NAME: &str = BUILD_POLICY_NAME;

pub const BIN32_DATABIN_NAME: &str = BUILD_BIN32_NAME;


pub const SECURE_DIR: &str = BUILD_SECURE_DIR;
pub const MODULEROOT: &str = concatcp!(SECURE_DIR, "/modules");
pub const MODULEUPGRADE: &str = concatcp!(SECURE_DIR, "/modules_update");
pub const DATABIN: &str = concatcp!(SECURE_DIR, "/", BUILD_DATA_DIR);
pub const MAGISKDB: &str = concatcp!(SECURE_DIR, "/", BUILD_DB_NAME);


pub const INTERNAL_DIR: &str = BUILD_INTERNAL_DIR;
pub const MAIN_CONFIG: &str = concatcp!(INTERNAL_DIR, "/config");
pub const PREINITMIRR: &str = concatcp!(INTERNAL_DIR, "/preinit");
pub const MODULEMNT: &str = concatcp!(INTERNAL_DIR, "/modules");
pub const WORKERDIR: &str = concatcp!(INTERNAL_DIR, "/worker");
pub const BBPATH: &str = concatcp!(INTERNAL_DIR, "/", BUILD_BUSYBOX_NAME);
pub const DEVICEDIR: &str = concatcp!(INTERNAL_DIR, "/device");
pub const MAIN_SOCKET: &str = concatcp!(DEVICEDIR, "/", BUILD_SOCKET_NAME);
pub const PREINITDEV: &str = concatcp!(DEVICEDIR, "/preinit");
pub const ROOTOVL: &str = concatcp!(INTERNAL_DIR, "/rootdir");
pub const ROOTMNT: &str = concatcp!(ROOTOVL, "/.mount_list");
pub const SELINUXMOCK: &str = concatcp!(INTERNAL_DIR, "/selinux");


pub const MAIN_BIN_NAME: &str = BUILD_ID;
pub const MAIN_BIN_NAME_32: &str = concatcp!(BUILD_ID, "32");
pub const POLICY_BIN_NAME: &str = concatcp!(BUILD_ID, "p");
pub const DAEMON_PROC_NAME: &str = concatcp!(BUILD_ID, "d");

pub const WORKER_SOURCE: &str = BUILD_ID;


pub const ZYGISKLDR: &str = concatcp!("lib", BUILD_ID, "z.so");


pub const RAMDISK_BIN_NAME: &str = BUILD_RAMDISK_NAME;

pub const BACKUP_CONFIG: &str = concatcp!(".backup/", BUILD_BACKUP_CONFIG);


pub const SEPOL_PROC_DOMAIN: &str = BUILD_PROC_DOMAIN;
pub const MAGISK_PROC_CON: &str = concatcp!("u:r:", SEPOL_PROC_DOMAIN, ":s0");

pub const SEPOL_FILE_TYPE: &str = BUILD_FILE_TYPE;
pub const MAGISK_FILE_CON: &str = concatcp!("u:object_r:", SEPOL_FILE_TYPE, ":s0");
