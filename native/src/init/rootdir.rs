use crate::consts::{BUILD_BACKUP_CONFIG, MAGISK_PROC_CON, MAIN_BIN_NAME, ROOTMNT, ROOTOVL};
use base::const_format::concatcp;
use crate::ffi::MagiskInit;
use base::nix::fcntl::OFlag;
use base::{
    BufReadExt, Directory, FsPathBuilder, LoggedResult, ResultExt, Utf8CStr, Utf8CString,
    clone_attr, cstr, debug,
};
use std::fs::File;
use std::io::{BufReader, Write};
use std::mem;
use std::os::fd::{FromRawFd, RawFd};

pub fn inject_magisk_rc(fd: RawFd, tmp_dir: &Utf8CStr) {
    debug!("Injecting magisk rc");

    let mut file = unsafe { File::from_raw_fd(fd) };

    write!(
        file,
        r#"
on post-fs-data
    exec {0} 0 0 -- {1}/{2} --post-fs-data

on property:vold.decrypt=trigger_restart_framework
    exec {0} 0 0 -- {1}/{2} --service

on nonencrypted
    exec {0} 0 0 -- {1}/{2} --service

on property:sys.boot_completed=1
    exec {0} 0 0 -- {1}/{2} --boot-complete
"#,
        MAGISK_PROC_CON, tmp_dir, MAIN_BIN_NAME
    )
    .ok();

    mem::forget(file)
}

pub struct OverlayAttr(Utf8CString, Utf8CString);

impl MagiskInit {
    pub(crate) fn parse_config_file(&mut self) {
        if let Ok(fd) = cstr!(concatcp!("/data/.backup/", BUILD_BACKUP_CONFIG))
            .open(OFlag::O_RDONLY)
        {
            let mut reader = BufReader::new(fd);
            reader.for_each_prop(|key, val| {
                if key == "PREINITDEVICE" {
                    self.preinit_dev = val.to_string();
                    return false;
                }
                true
            })
        }
    }

    fn mount_impl(
        &mut self,
        src_dir: &Utf8CStr,
        dest_dir: &Utf8CStr,
        mount_list: &mut String,
    ) -> LoggedResult<()> {
        let mut dir = Directory::open(src_dir)?;
        let mut con = cstr::buf::default();
        loop {
            match &dir.read()? {
                None => return Ok(()),
                Some(e) => {
                    let name = e.name();
                    let src = cstr::buf::dynamic(256).join_path(src_dir).join_path(name);
                    let dest = cstr::buf::dynamic(256).join_path(dest_dir).join_path(name);
                    if dest.exists() {
                        if e.is_dir() {

                            self.mount_impl(&src, &dest, mount_list)?;
                        } else {
                            debug!("Mount [{}] -> [{}]", src, dest);
                            clone_attr(&dest, &src)?;
                            dest.get_secontext(&mut con)?;
                            src.bind_mount_to(&dest, false)?;
                            self.overlay_con
                                .push(OverlayAttr(dest.to_owned(), con.to_owned()));
                            mount_list.push_str(dest.as_str());
                            mount_list.push('\n');
                        }
                    }
                }
            }
        }
    }

    pub(crate) fn mount_overlay(&mut self, dest: &Utf8CStr) {
        let mut mount_list = String::new();
        self.mount_impl(cstr!(ROOTOVL), dest, &mut mount_list)
            .log_ok();
        if let Ok(mut fd) = cstr!(ROOTMNT).create(OFlag::O_CREAT | OFlag::O_WRONLY, 0) {
            fd.write(mount_list.as_bytes()).log_ok();
        }
    }

    pub(crate) fn restore_overlay_contexts(&self) {
        self.overlay_con.iter().for_each(|attr| {
            let OverlayAttr(path, con) = attr;
            path.set_secontext(con).log_ok();
        })
    }
}
