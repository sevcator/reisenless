use crate::consts::{APP_PACKAGE_NAME, BUILD_STUB_NAME, BUILD_SU_CACHE, MAGISK_VER_CODE, SECURE_DIR};
use crate::daemon::{AID_APP_END, AID_APP_START, AID_USER_OFFSET, MagiskD, to_app_id};
use crate::ffi::{DbEntryKey, get_magisk_tmp};
use base::WalkResult::{Continue, Skip};
use base::{
    BufReadExt, Directory, FsPathBuilder, LoggedResult, ReadExt, ResultExt, Utf8CStrBuf,
    Utf8CString, cstr, error, fd_get_attr, warn,
};
use base::const_format::concatcp;
use bit_set::BitSet;
use nix::fcntl::OFlag;
use std::collections::BTreeMap;
use std::fs::File;
use std::io;
use std::io::{Cursor, Read, Seek, SeekFrom};
use std::os::fd::AsRawFd;
use std::time::Duration;

const EOCD_MAGIC: u32 = 0x06054B50;
const APK_SIGNING_BLOCK_MAGIC: [u8; 16] = *b"APK Sig Block 42";
const SIGNATURE_SCHEME_V2_MAGIC: u32 = 0x7109871A;
const PACKAGES_XML: &str = "/data/system/packages.xml";

macro_rules! bad_apk {
    ($msg:literal) => {
        io::Error::new(io::ErrorKind::InvalidData, concat!("cert: ", $msg))
    };
}























fn read_certificate(apk: &mut File, version: i32) -> Vec<u8> {
    let res = || -> io::Result<Vec<u8>> {
        let mut u32_val = 0u32;
        let mut u64_val = 0u64;


        for i in 0u16.. {
            let mut comment_sz = 0u16;
            apk.seek(SeekFrom::End(-(size_of_val(&comment_sz) as i64) - i as i64))?;
            apk.read_pod(&mut comment_sz)?;

            if comment_sz == i {
                apk.seek(SeekFrom::Current(-22))?;
                let mut magic = 0u32;
                apk.read_pod(&mut magic)?;
                if magic == EOCD_MAGIC {
                    break;
                }
            }
            if i == 0xffff {
                Err(bad_apk!("invalid APK format"))?;
            }
        }



        let mut central_dir_off = 0u32;
        apk.seek(SeekFrom::Current(12))?;
        apk.read_pod(&mut central_dir_off)?;


        if version >= 0 {
            let mut comment_sz = 0u16;
            apk.read_pod(&mut comment_sz)?;
            let mut comment = vec![0u8; comment_sz as usize];
            apk.read_exact(&mut comment)?;
            let mut comment = Cursor::new(&comment);
            let mut apk_ver = 0;
            comment.for_each_prop(|k, v| {
                if k == "versionCode" {
                    apk_ver = v.parse::<i32>().unwrap_or(0);
                    false
                } else {
                    true
                }
            });
            if version > apk_ver {
                Err(bad_apk!("APK version too low"))?;
            }
        }


        apk.seek(SeekFrom::Start((central_dir_off - 24) as u64))?;
        apk.read_pod(&mut u64_val)?;
        let mut magic = [0u8; 16];
        apk.read_exact(&mut magic)?;
        if magic != APK_SIGNING_BLOCK_MAGIC {
            Err(bad_apk!("invalid signing block magic"))?;
        }
        let mut signing_blk_sz = 0u64;
        apk.seek(SeekFrom::Current(
            -(u64_val as i64) - (size_of_val(&signing_blk_sz) as i64),
        ))?;
        apk.read_pod(&mut signing_blk_sz)?;
        if signing_blk_sz != u64_val {
            Err(bad_apk!("invalid signing block size"))?;
        }


        loop {
            apk.read_pod(&mut u64_val)?;
            if u64_val == signing_blk_sz {
                Err(bad_apk!("cannot find certificate"))?;
            }

            let mut id = 0u32;
            apk.read_pod(&mut id)?;
            if id == SIGNATURE_SCHEME_V2_MAGIC {

                apk.seek(SeekFrom::Current((size_of_val(&u32_val) * 3) as i64))?;

                apk.read_pod(&mut u32_val)?;
                apk.seek(SeekFrom::Current(u32_val as i64))?;

                apk.seek(SeekFrom::Current(size_of_val(&u32_val) as i64))?;
                apk.read_pod(&mut u32_val)?;

                let mut cert = vec![0; u32_val as usize];
                apk.read_exact(cert.as_mut())?;
                break Ok(cert);
            } else {

                apk.seek(SeekFrom::Current(
                    u64_val as i64 - (size_of_val(&id) as i64),
                ))?;
            }
        }
    }();
    res.log().unwrap_or(vec![])
}

fn find_apk_path(pkg: &str) -> LoggedResult<Utf8CString> {
    let mut buf = cstr::buf::default();
    let mut latest = None;
    Directory::open(cstr!("/data/app"))?.pre_order_walk(|e| {
        if !e.is_dir() {
            return Ok(Skip);
        }
        let name_bytes = e.name().as_bytes();
        if name_bytes.starts_with(pkg.as_bytes()) && name_bytes[pkg.len()] == b'-' {
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

pub struct ManagerInfo {
    stub_apk_fd: Option<File>,
    trusted_cert: Vec<u8>,
    repackaged_app_id: i32,
    repackaged_pkg: String,
    repackaged_cert: Vec<u8>,
    tracked_files: BTreeMap<i32, TrackedFile>,
}

impl Default for ManagerInfo {
    fn default() -> Self {
        ManagerInfo {
            stub_apk_fd: None,
            trusted_cert: Vec::new(),
            repackaged_app_id: -1,
            repackaged_pkg: String::new(),
            repackaged_cert: Vec::new(),
            tracked_files: BTreeMap::new(),
        }
    }
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

    fn check_dyn(&mut self, daemon: &MagiskD, user: i32, pkg: &str) -> Status {
        let apk = cstr::buf::default()
            .join_path(daemon.app_data_dir())
            .join_path_fmt(user)
            .join_path(pkg)
            .join_path("dyn")
            .join_path("current.apk");
        let uid: i32;
        let cert = match apk.open(OFlag::O_RDONLY | OFlag::O_CLOEXEC) {
            Ok(mut fd) => {
                uid = fd_get_attr(fd.as_raw_fd())
                    .map(|attr| attr.st.st_uid as i32)
                    .unwrap_or(-1);
                read_certificate(&mut fd, MAGISK_VER_CODE)
            }
            Err(_) => {
                warn!("pkg: no dyn APK, ignore");
                return Status::NotInstalled;
            }
        };

        if cert.is_empty() || cert != self.trusted_cert {
            error!("pkg: dyn APK signature mismatch: {}", apk);
            #[cfg(all(feature = "check-signature", not(debug_assertions)))]
            {
                return Status::CertMismatch;
            }
        }

        self.repackaged_app_id = to_app_id(uid);
        self.tracked_files
            .insert(user, TrackedFile::new(apk.to_owned()));
        Status::Installed
    }

    fn check_stub(&mut self, user: i32, pkg: &str) -> Status {
        let Ok(apk) = find_apk_path(pkg) else {
            return Status::NotInstalled;
        };

        let cert = match apk.open(OFlag::O_RDONLY | OFlag::O_CLOEXEC) {
            Ok(mut fd) => read_certificate(&mut fd, -1),
            Err(_) => return Status::NotInstalled,
        };

        if cert.is_empty() || (pkg == self.repackaged_pkg && cert != self.repackaged_cert) {
            error!("pkg: repackaged APK signature invalid: {}", apk);
            return Status::CertMismatch;
        }

        self.repackaged_pkg.clear();
        self.repackaged_pkg.push_str(pkg);
        self.repackaged_cert = cert;
        self.tracked_files.insert(user, TrackedFile::new(apk));
        Status::Installed
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
            #[cfg(all(feature = "check-signature", not(debug_assertions)))]
            {
                return Status::CertMismatch;
            }
        }

        self.tracked_files.insert(user, TrackedFile::new(apk));
        Status::Installed
    }

    fn get_manager(&mut self, daemon: &MagiskD, user: i32) -> (i32, &str) {
        let db_pkg = daemon.get_db_string(DbEntryKey::SuManager);


        if db_pkg != self.repackaged_pkg {
            self.tracked_files.remove(&user);
        }

        if let Some(file) = self.tracked_files.get(&user)
            && file.is_same()
        {

            if &file.path == PACKAGES_XML {
                return (-1, "");
            }

            if file.path.starts_with(daemon.app_data_dir().as_str()) {
                return (
                    user * AID_USER_OFFSET + self.repackaged_app_id,
                    &self.repackaged_pkg,
                );
            }

            if !self.repackaged_pkg.is_empty() {
                return if matches!(
                    self.check_dyn(daemon, user, self.repackaged_pkg.clone().as_str()),
                    Status::Installed
                ) {
                    (
                        user * AID_USER_OFFSET + self.repackaged_app_id,
                        &self.repackaged_pkg,
                    )
                } else {
                    (-1, "")
                };
            }

            let uid = daemon.get_package_uid(user, APP_PACKAGE_NAME);
            return if uid < 0 {
                (-1, "")
            } else {
                (uid, APP_PACKAGE_NAME)
            };
        }

        if !db_pkg.is_empty() {
            match self.check_stub(user, &db_pkg) {
                Status::Installed => {
                    if matches!(self.check_dyn(daemon, user, &db_pkg), Status::Installed) {
                        return (
                            user * AID_USER_OFFSET + self.repackaged_app_id,
                            &self.repackaged_pkg,
                        );
                    }
                    daemon.rm_db_string(DbEntryKey::SuManager).ok();
                }
                Status::NotInstalled => {
                    daemon.rm_db_string(DbEntryKey::SuManager).ok();
                }
                Status::CertMismatch => {
                    daemon.rm_db_string(DbEntryKey::SuManager).ok();
                }
            }
        }

        self.repackaged_pkg.clear();
        self.repackaged_cert.clear();

        match self.check_orig(user) {
            Status::Installed => {
                let uid = daemon.get_package_uid(user, APP_PACKAGE_NAME);
                return if uid < 0 {
                    (-1, "")
                } else {
                    (uid, APP_PACKAGE_NAME)
                };
            }
            Status::CertMismatch => {}
            Status::NotInstalled => {}
        }


        self.tracked_files
            .insert(user, TrackedFile::new(PACKAGES_XML.into()));

        (-1, "")
    }
}

impl MagiskD {
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
            info.trusted_cert = read_certificate(&mut fd, MAGISK_VER_CODE);

            fd.seek(SeekFrom::Start(0)).log_ok();
            info.stub_apk_fd = Some(fd);
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
