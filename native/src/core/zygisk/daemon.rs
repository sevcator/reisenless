#[cfg(target_pointer_width = "64")]
use crate::consts::MAIN_BIN_NAME_32;
use crate::consts::{MAIN_BIN_NAME, MODULEROOT, ZYGISKLDR};
use crate::daemon::{MagiskD, to_user_id};
use crate::ffi::{ZygiskRequest, ZygiskStateFlags, get_magisk_tmp, update_deny_flags};
use crate::resetprop::{get_prop, set_prop};
use crate::udonge::{
    UDONGE_MODULE_NAME, UDONGE_ROOT, UDONGE_RUNTIME, is_enabled as udonge_enabled,
    is_hide_apps_target,
};
use crate::socket::{IpcRead, UnixSocketExt};
use base::libc::STDOUT_FILENO;
use base::{
    Directory, FsPathBuilder, LoggedResult, ResultExt, Utf8CStr, WriteExt, cstr, fork_dont_care,
    libc, log_err, raw_cstr, warn,
};
use nix::fcntl::OFlag;
use std::fmt::Write;
use std::fs::File;
use std::os::fd::{AsRawFd, FromRawFd, RawFd};
use std::os::unix::net::UnixStream;
use std::ptr;
use std::sync::atomic::Ordering;

const NBPROP: &Utf8CStr = cstr!("ro.dalvik.vm.native.bridge");
const UNMOUNT_MASK: u32 =
    ZygiskStateFlags::ProcessOnDenyList.repr | ZygiskStateFlags::DenyListEnforced.repr;

fn is_udonge_target(process: &str) -> bool {
    if is_hide_apps_target(process) {
        return true;
    }
    let process = process.split_once(':').map_or(process, |(package, _)| package);
    matches!(
        process,
        "com.google.android.gms.unstable"
            | "com.eltavine.duckdetector"
            | "ru.nspk.mirpay"
            | "ru.nspk.sbpay"
            | "ru.sberbankmobile"
            | "com.idamob.tinkoff.android"
            | "ru.vtb24.mobilebanking.android"
            | "ru.alfabank.mobile.android"
            | "ru.gazprombank.android.mobilebank.app"
            | "ru.raiffeisennews"
            | "ru.rosbank.android"
            | "ru.mkb.mobile"
            | "ru.rshb.dbo"
            | "ru.letobank.Prometheus"
            | "com.openbank"
            | "ru.sovcombank.halva"
            | "com.sovcombank.club"
            | "ru.yoo.money"
            | "com.yandex.bank"
            | "ru.ozon.fintech.finance"
            | "com.qiwi.wallet"
            | "com.axlebolt.standoff2"
    )
}

pub fn zygisk_should_load_module(flags: u32) -> bool {
    flags & ZygiskStateFlags::ProcessIsMagiskApp.repr == 0
}

#[allow(unused_variables)]
fn exec_zygiskd(is_64_bit: bool, remote: UnixStream) {

    unsafe {
        libc::fcntl(remote.as_raw_fd(), libc::F_SETFD, 0);
    }



    #[cfg(target_pointer_width = "64")]
    let magisk = if is_64_bit {
        MAIN_BIN_NAME
    } else {
        MAIN_BIN_NAME_32
    };

    #[cfg(target_pointer_width = "32")]
    let magisk = MAIN_BIN_NAME;

    let exe = cstr::buf::new::<64>()
        .join_path(get_magisk_tmp())
        .join_path(magisk);

    let mut fd_str = cstr::buf::new::<16>();
    write!(fd_str, "{}", remote.as_raw_fd()).ok();
    unsafe {
        libc::execl(
            exe.as_ptr(),
            raw_cstr!(""),
            raw_cstr!("zygisk"),
            raw_cstr!("companion"),
            fd_str.as_ptr(),
            ptr::null() as *const libc::c_char,
        );
        libc::exit(-1);
    }
}

#[derive(Default)]
pub struct ZygiskState {
    pub lib_name: String,
    sockets: (Option<UnixStream>, Option<UnixStream>),
    start_count: u32 = 1,
}

impl ZygiskState {
    fn connect_zygiskd(&mut self, mut client: UnixStream, daemon: &MagiskD) -> LoggedResult<()> {
        let is_64_bit: bool = client.read_decodable()?;
        let socket = if is_64_bit {
            &mut self.sockets.1
        } else {
            &mut self.sockets.0
        };

        if let Some(fd) = socket {

            let mut pfd = libc::pollfd {
                fd: fd.as_raw_fd(),
                events: 0,
                revents: 0,
            };
            if unsafe { libc::poll(&mut pfd, 1, 0) } != 0 || pfd.revents != 0 {

                *socket = None;
            }
        }

        if let Some(fd) = socket {
            fd.send_fds(&[client.as_raw_fd()])?;
        } else {

            let (mut local, remote) = UnixStream::pair()?;
            if fork_dont_care() == 0 {
                exec_zygiskd(is_64_bit, remote);
            }
            if let Some((module_fds, _opened)) = daemon.get_module_fds(is_64_bit, false, true) {
                local.send_fds(&module_fds)?;
            }
            if local.read_decodable::<i32>()? != 0 {
                return log_err!();
            }
            local.send_fds(&[client.as_raw_fd()])?;
            *socket = Some(local);
        }
        Ok(())
    }

    pub fn reset(&mut self, mut restore: bool) {
        if restore {
            self.start_count = 1;
        } else {
            self.sockets = (None, None);
            self.start_count += 1;
            if self.start_count > 3 {
                warn!("zygote crashed too many times, rolling-back");
                restore = true;
            }
        }

        if restore {
            self.restore_prop();
        } else {
            self.set_prop();
        }
    }

    pub fn set_prop(&mut self) {
        if !self.lib_name.is_empty() {
            return;
        }
        let orig = get_prop(NBPROP);
        self.lib_name = if orig.is_empty() || orig == "0" {
            ZYGISKLDR.to_string()
        } else {
            ZYGISKLDR.to_string() + &orig
        };
        set_prop(NBPROP, Utf8CStr::from_string(&mut self.lib_name));



        if get_prop(cstr!("ro.maple.enable")) == "1" {
            set_prop(cstr!("ro.maple.enable"), cstr!("0"));
        }
    }

    pub fn restore_prop(&mut self) {
        let mut orig = "0".to_string();
        if self.lib_name.len() > ZYGISKLDR.len() {
            orig = self.lib_name[ZYGISKLDR.len()..].to_string();
        }
        set_prop(NBPROP, Utf8CStr::from_string(&mut orig));
        self.lib_name.clear();
    }
}

impl MagiskD {
    pub fn zygisk_handler(&self, mut client: UnixStream) {
        let _ = || -> LoggedResult<()> {
            let code = ZygiskRequest {
                repr: client.read_decodable()?,
            };
            match code {
                ZygiskRequest::GetInfo => self.get_process_info(client)?,
                ZygiskRequest::ConnectCompanion => self
                    .zygisk
                    .lock()
                    .connect_zygiskd(client, self)
                    .log_with_msg(|w| w.write_str("runtime worker startup error"))?,
                ZygiskRequest::GetModDir => self.get_mod_dir(client)?,
                _ => {}
            }
            Ok(())
        }();
    }

    fn open_udonge_module(is_64_bit: bool) -> Option<File> {
        #[cfg(target_arch = "aarch64")]
        let abi = if is_64_bit {
            "arm64-v8a.so"
        } else {
            "armeabi-v7a.so"
        };
        #[cfg(target_arch = "arm")]
        let abi = if is_64_bit { return None } else { "armeabi-v7a.so" };
        #[cfg(target_arch = "x86_64")]
        let abi = if is_64_bit { "x86_64.so" } else { "x86.so" };
        #[cfg(target_arch = "x86")]
        let abi = if is_64_bit { return None } else { "x86.so" };
        #[cfg(target_arch = "riscv64")]
        return None;

        let source = File::open(format!("{UDONGE_RUNTIME}/zygisk/{abi}")).ok()?;
        let memfd = unsafe {
            libc::syscall(
                libc::SYS_memfd_create,
                raw_cstr!("jit-cache"),
                libc::MFD_CLOEXEC,
            ) as RawFd
        };
        if memfd < 0 {
            return None;
        }
        let copied = unsafe {
            libc::sendfile(
                memfd,
                source.as_raw_fd(),
                ptr::null_mut(),
                i32::MAX as usize,
            ) >= 0
                && libc::lseek(memfd, 0, libc::SEEK_SET) >= 0
        };
        if !copied {
            unsafe { libc::close(memfd) };
            return None;
        }
        Some(unsafe { File::from_raw_fd(memfd) })
    }

    fn get_module_fds(
        &self,
        is_64_bit: bool,
        udonge_only: bool,
        allow_udonge: bool,
    ) -> Option<(Vec<RawFd>, Vec<File>)> {
        self.module_list.get().map(|module_list| {
            let mut fds = Vec::with_capacity(module_list.len() + 1);
            let mut opened = Vec::with_capacity(1);
            let mut has_udonge = false;
            for module in module_list {
                let mut fd = if is_64_bit { module.z64 } else { module.z32 };
                if module.name == UDONGE_MODULE_NAME {
                    has_udonge = true;
                    if !udonge_enabled() || (udonge_only && !allow_udonge) {
                        fd = -1;
                    } else if let Some(file) = Self::open_udonge_module(is_64_bit) {
                        fd = file.as_raw_fd();
                        opened.push(file);
                    }
                } else if udonge_only {
                    fd = -1;
                }
                fds.push(if fd < 0 { STDOUT_FILENO } else { fd });
            }
            if !has_udonge && udonge_enabled() && (!udonge_only || allow_udonge) {
                let fd = Self::open_udonge_module(is_64_bit)
                    .map(|file| {
                        let fd = file.as_raw_fd();
                        opened.push(file);
                        fd
                    })
                    .unwrap_or(STDOUT_FILENO);
                fds.push(fd);
            }
            (fds, opened)
        })
    }

    fn get_process_info(&self, mut client: UnixStream) -> LoggedResult<()> {
        let uid: i32 = client.read_decodable()?;
        let process: String = client.read_decodable()?;
        let is_64_bit: bool = client.read_decodable()?;
        let mut flags: u32 = 0;
        update_deny_flags(uid, &process, &mut flags);
        if self.get_manager_uid(to_user_id(uid)) == uid {
            flags |= ZygiskStateFlags::ProcessIsMagiskApp.repr
        }
        if self.uid_granted_root(uid) {
            flags |= ZygiskStateFlags::ProcessGrantedRoot.repr
        }


        client.write_pod(&flags)?;


        if zygisk_should_load_module(flags)
            && let Some((module_fds, _opened)) = self.get_module_fds(
                is_64_bit,
                flags & UNMOUNT_MASK == UNMOUNT_MASK,
                is_udonge_target(&process),
            )
        {
            client.send_fds(&module_fds)?;
        }


        if uid != 1000 || process != "system_server" {
            return Ok(());
        }


        let failed_ids: Vec<i32> = client.read_decodable()?;
        if let Some(module_list) = self.module_list.get() {
            for id in failed_ids {
                let Some(module) = module_list.get(id as usize) else {
                    continue;
                };
                let path = if module.name == UDONGE_MODULE_NAME {
                    cstr::buf::default().join_path(UDONGE_ROOT).join_path("state")
                } else {
                    cstr::buf::default()
                        .join_path(MODULEROOT)
                        .join_path(&module.name)
                        .join_path("zygisk")
                };

                if let Ok(dir) = Directory::open(&path) {
                    dir.open_as_file_at(cstr!("unloaded"), OFlag::O_CREAT | OFlag::O_RDONLY, 0o644)
                        .log()
                        .ok();
                }
            }
        }

        Ok(())
    }

    fn get_mod_dir(&self, mut client: UnixStream) -> LoggedResult<()> {
        let id: i32 = client.read_decodable()?;
        let Some(module) = self
            .module_list
            .get()
            .and_then(|list| list.get(id as usize))
        else {
            return Ok(());
        };
        let dir = if module.name == UDONGE_MODULE_NAME {
            cstr::buf::default().join_path(UDONGE_RUNTIME)
        } else {
            cstr::buf::default()
                .join_path(MODULEROOT)
                .join_path(&module.name)
        };
        let fd = dir.open(OFlag::O_RDONLY | OFlag::O_CLOEXEC)?;
        client.send_fds(&[fd.as_raw_fd()])?;
        Ok(())
    }
}


impl MagiskD {
    pub fn zygisk_enabled(&self) -> bool {
        self.zygisk_enabled.load(Ordering::Acquire)
    }
}
