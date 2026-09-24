use crate::apk_cert::read_certificate as read_apk_certificate;
use crate::consts::{
    APP_PACKAGE_NAME, BUILD_STUB_NAME, BUILD_SU_CACHE, MAGISK_VER_CODE, SECURE_DIR,
};
use crate::daemon::{AID_APP_END, AID_APP_START, MagiskD, to_app_id};
use crate::ffi::get_magisk_tmp;
use crate::manager_auth::{privileged_client_authorized, uid_owners_are_exclusive};
use base::WalkResult::{Continue, Skip};
use base::const_format::concatcp;
use base::{
    Directory, FsPathBuilder, LoggedResult, ResultExt, Utf8CStrBuf, Utf8CString, cstr, error,
};
use bit_set::BitSet;
use nix::fcntl::OFlag;
use std::collections::BTreeMap;
use std::fs::File;
use std::os::unix::fs::MetadataExt;
use std::time::Duration;

const PACKAGES_XML: &str = "/data/system/packages.xml";

fn process_uid(pid: i32) -> Option<i32> {
    let status = std::fs::read_to_string(format!("/proc/{pid}/status")).ok()?;
    crate::manager_auth::parse_status_id(&status, "Uid")
}

fn read_certificate(apk: &mut File, version: i32) -> Vec<u8> {
    read_apk_certificate(apk, version).log().unwrap_or_default()
}

fn find_apk_path(pkg: &str) -> LoggedResult<Utf8CString> {
    let mut buf = cstr::buf::default();
    let mut latest = None;
    Directory::open(cstr!("/data/app"))?.pre_order_walk(|e| {
        if !e.is_dir() {
            return Ok(Skip);
        }
        let name_bytes = e.name().as_bytes();
        if name_bytes.len() > pkg.len()
            && name_bytes.starts_with(pkg.as_bytes())
            && name_bytes[pkg.len()] == b'-'
        {
            let mut candidate = cstr::buf::default();
            e.resolve_path(&mut candidate)?;
            candidate.push_str("/base.apk");
            if let Ok(attr) = candidate.get_attr() {
                let timestamp = (attr.st.st_ctime, attr.st.st_ctime_nsec);
                if latest.is_none_or(|current| timestamp > current) {
                    buf.clear();
                    buf.push_str(candidate.as_str());
                    latest = Some(timestamp);
                }
            }
            return Ok(Skip);
        }
        if name_bytes.starts_with(b"~~") {
            return Ok(Continue);
        }
        Ok(Skip)
    })?;
    Ok(buf.to_owned())
}

const APK_CACHE_FILE: &str = concatcp!(SECURE_DIR, "/", BUILD_SU_CACHE);

fn find_orig_apk_path() -> LoggedResult<Utf8CString> {
    if let Ok(cached) = std::fs::read_to_string(APK_CACHE_FILE) {
        let cached = cached.trim();
        if !cached.is_empty() && std::path::Path::new(cached).exists() {
            let mut apk = cstr::buf::default();
            apk.push_str(cached);
            return Ok(apk.to_owned());
        }

        let _ = std::fs::remove_file(APK_CACHE_FILE);
    }

    let apk = find_apk_path(APP_PACKAGE_NAME)?;
    if !apk.is_empty() {
        let _ = std::fs::write(APK_CACHE_FILE, apk.to_string().as_bytes());
    }
    Ok(apk)
}

enum Status {
    Installed,
    NotInstalled,
    CertMismatch,
}

#[derive(Default)]
pub struct ManagerInfo {
    trusted_cert: Vec<u8>,
    tracked_files: BTreeMap<i32, TrackedFile>,
}

#[derive(Default)]
struct TrackedFile {
    path: Utf8CString,
    timestamp: Duration,
}

impl TrackedFile {
    fn new(path: Utf8CString) -> TrackedFile {
        let attr = match path.get_attr() {
            Ok(attr) => attr,
            Err(_) => return TrackedFile::default(),
        };
        let timestamp = Duration::new(attr.st.st_ctime as u64, attr.st.st_ctime_nsec as u32);
        TrackedFile { path, timestamp }
    }

    fn is_same(&self) -> bool {
        if self.path.is_empty() {
            return false;
        }
        let attr = match self.path.get_attr() {
            Ok(attr) => attr,
            Err(_) => return false,
        };
        let timestamp = Duration::new(attr.st.st_ctime as u64, attr.st.st_ctime_nsec as u32);
        timestamp == self.timestamp
    }
}

impl ManagerInfo {
    fn check_orig_uid(&mut self, daemon: &MagiskD, user: i32, uid: i32) -> bool {
        let Ok(apk) = find_apk_path(APP_PACKAGE_NAME) else {
            return false;
        };
        if apk.is_empty() {
            return false;
        }

        let cert = match apk.open(OFlag::O_RDONLY | OFlag::O_CLOEXEC) {
            Ok(mut fd) => read_certificate(&mut fd, MAGISK_VER_CODE),
            Err(_) => return false,
        };
        if cert.is_empty() || cert != self.trusted_cert {
            return false;
        }
        if daemon.get_package_uid(user, APP_PACKAGE_NAME) != uid {
            return false;
        }

        std::fs::write(APK_CACHE_FILE, apk.to_string().as_bytes()).ok();
        self.tracked_files.insert(user, TrackedFile::new(apk));
        true
    }

    fn check_orig(&mut self, user: i32) -> Status {
        let Ok(apk) = find_orig_apk_path() else {
            return Status::NotInstalled;
        };

        let cert = match apk.open(OFlag::O_RDONLY | OFlag::O_CLOEXEC) {
            Ok(mut fd) => read_certificate(&mut fd, MAGISK_VER_CODE),
            Err(_) => return Status::NotInstalled,
        };
        if cert.is_empty() || cert != self.trusted_cert {
            error!("pkg: APK signature mismatch: {}", apk);
            return Status::CertMismatch;
        }

        self.tracked_files.insert(user, TrackedFile::new(apk));
        Status::Installed
    }

    fn get_manager(&mut self, daemon: &MagiskD, user: i32) -> (i32, &'static str) {
        if let Some(file) = self.tracked_files.get(&user)
            && file.is_same()
        {
            if &file.path == PACKAGES_XML {
                return (-1, "");
            }

            let uid = daemon.get_package_uid(user, APP_PACKAGE_NAME);
            return if uid < 0 {
                (-1, "")
            } else {
                (uid, APP_PACKAGE_NAME)
            };
        }

        if matches!(self.check_orig(user), Status::Installed) {
            let uid = daemon.get_package_uid(user, APP_PACKAGE_NAME);
            return if uid < 0 {
                (-1, "")
            } else {
                (uid, APP_PACKAGE_NAME)
            };
        }

        self.tracked_files
            .insert(user, TrackedFile::new(PACKAGES_XML.into()));
        (-1, "")
    }
}

impl MagiskD {
    fn package_has_exclusive_uid(&self, user: i32, package: &str, uid: i32) -> bool {
        let root = format!("{}/{user}", self.app_data_dir());
        let Ok(entries) = std::fs::read_dir(root) else {
            return false;
        };
        let mut owners = Vec::new();
        for entry in entries {
            let Ok(entry) = entry else {
                return false;
            };
            let Ok(name) = entry.file_name().into_string() else {
                return false;
            };
            let Ok(metadata) = entry.metadata() else {
                return false;
            };
            owners.push((name, metadata.uid() as i32));
        }
        uid_owners_are_exclusive(&owners, package, uid)
    }

    fn get_package_uid(&self, user: i32, pkg: &str) -> i32 {
        let path = cstr::buf::default()
            .join_path(self.app_data_dir())
            .join_path_fmt(user)
            .join_path(pkg);
        path.get_attr()
            .map(|attr| attr.st.st_uid as i32)
            .unwrap_or(-1)
    }

    pub fn preserve_stub_apk(&self) {
        let mut info = self.manager_info.lock();

        let apk = cstr::buf::default()
            .join_path(get_magisk_tmp())
            .join_path(BUILD_STUB_NAME);

        if let Ok(mut fd) = apk.open(OFlag::O_RDONLY | OFlag::O_CLOEXEC) {
            // The embedded trust-anchor stub has its own low versionCode and
            // is not versioned like the randomized full manager release APK.
            info.trusted_cert = read_certificate(&mut fd, -1);
        }

        apk.remove().log_ok();
    }

    pub fn get_manager_uid(&self, user: i32) -> i32 {
        let mut info = self.manager_info.lock();
        let (uid, _) = info.get_manager(self, user);
        uid
    }

    pub fn is_manager_uid(&self, user: i32, uid: i32) -> bool {
        let mut info = self.manager_info.lock();
        let manager_uid = {
            let (manager_uid, _) = info.get_manager(self, user);
            manager_uid
        };
        manager_uid == uid || info.check_orig_uid(self, user, uid)
    }

    /// Authenticate a direct manager client with Android's package identity.
    ///
    /// The embedded APK certificate establishes the trusted installed package,
    /// the kernel-provided UID binds the socket peer to that package, and the
    /// SELinux MLS/MCS level binds the process to the package data domain.
    pub fn is_privileged_client(&self, user: i32, uid: i32, pid: i32, peer_context: &str) -> bool {
        if uid == 0 {
            return true;
        }
        let package_identity_matches = self.is_manager_uid(user, uid);
        if !package_identity_matches {
            error!("manager auth: package identity rejected for uid={uid}");
            return false;
        }
        if !self.package_has_exclusive_uid(user, APP_PACKAGE_NAME, uid) {
            error!("manager auth: package uid is not exclusive for uid={uid}");
            return false;
        }

        let data_path = cstr::buf::default()
            .join_path(self.app_data_dir())
            .join_path_fmt(user)
            .join_path(APP_PACKAGE_NAME);
        let Ok(data_attr) = data_path.get_attr() else {
            error!("manager auth: package data directory missing for uid={uid}");
            return false;
        };
        let process_uid = process_uid(pid);
        let authorized = privileged_client_authorized(
            uid,
            Some(data_attr.st.st_uid as i32),
            package_identity_matches,
            peer_context,
            data_attr.con.as_str(),
            process_uid,
        );
        if !authorized {
            error!(
                "manager auth: peer context/uid mismatch uid={uid} process_uid={process_uid:?} peer={peer_context} data={}",
                data_attr.con
            );
        }
        authorized
    }

    pub fn get_manager(&self, user: i32) -> (i32, String) {
        let mut info = self.manager_info.lock();
        let (uid, pkg) = info.get_manager(self, user);
        (uid, pkg.to_string())
    }

    pub fn ensure_manager(&self) {
        let mut info = self.manager_info.lock();
        let _ = info.get_manager(self, 0);
    }

    pub fn get_app_no_list(&self) -> BitSet {
        let mut list = BitSet::new();
        let _ = || -> LoggedResult<()> {
            let mut app_data_dir = Directory::open(self.app_data_dir())?;

            loop {
                let entry = match app_data_dir.read()? {
                    None => break,
                    Some(e) => e,
                };
                let mut user_dir = match entry.open_as_dir() {
                    Err(_) => continue,
                    Ok(dir) => dir,
                };

                loop {
                    match user_dir.read()? {
                        None => break,
                        Some(e) => {
                            let mut entry_path = cstr::buf::default();
                            e.resolve_path(&mut entry_path)?;
                            let attr = entry_path.get_attr()?;
                            let app_id = to_app_id(attr.st.st_uid as i32);
                            if (AID_APP_START..=AID_APP_END).contains(&app_id) {
                                let app_no = app_id - AID_APP_START;
                                list.insert(app_no as usize);
                            }
                        }
                    }
                }
            }
            Ok(())
        }();
        list
    }
}
