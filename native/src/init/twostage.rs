use crate::consts::REDIR_PATH;
use crate::ffi::MagiskInit;
use base::nix::fcntl::OFlag;
use base::{LoggedResult, MappedFile, MutBytesExt, ResultExt, cstr, debug, error};
use std::io::Write;

pub(crate) fn hexpatch_init_for_second_stage(writable: bool) {
    let init = if writable {
        MappedFile::open_rw(cstr!("/init"))
    } else {
        MappedFile::open(cstr!("/init"))
    };

    let Ok(mut init) = init else {
        error!("Failed to open /init for hexpatch");
        return;
    };

    let from = "/system/bin/init";
    let to = REDIR_PATH;
    let v = init.patch(from.as_bytes(), to.as_bytes());
    #[allow(unused_variables)]
    for off in &v {
        debug!("Patch @ {:#010X} [{}] -> [{}]", off, from, to);
    }

    if !writable {

        let src = cstr!("/init");
        let dest = cstr!("/data/init");
        let _ = || -> LoggedResult<()> {
            {
                let mut fd = dest.create(OFlag::O_CREAT | OFlag::O_WRONLY, 0)?;
                fd.write_all(init.as_ref())?;
            }
            let attr = src.follow_link().get_attr()?;
            dest.set_attr(&attr)?;
            dest.bind_mount_to(src, false)?;
            Ok(())
        }();
    }
}

impl MagiskInit {
    pub(crate) fn hijack_init_with_switch_root(&self) {
























        if self.config.force_normal_boot {
            cstr!("/first_stage_ramdisk/storage/self")
                .mkdirs(0o755)
                .log_ok();
            cstr!("/first_stage_ramdisk/storage/self/primary")
                .create_symlink_to(cstr!("/system/system/bin/init"))
                .log_ok();
            debug!("Symlink /first_stage_ramdisk/storage/self/primary -> /system/system/bin/init");
            cstr!("/first_stage_ramdisk/sdcard")
                .create(OFlag::O_RDONLY | OFlag::O_CREAT | OFlag::O_CLOEXEC, 0)
                .log_ok();
        } else {
            cstr!("/storage/self").mkdirs(0o755).log_ok();
            cstr!("/storage/self/primary")
                .create_symlink_to(cstr!("/system/system/bin/init"))
                .log_ok();
            debug!("Symlink /storage/self/primary -> /system/system/bin/init");
        }
        cstr!("/init").rename_to(cstr!("/sdcard")).log_ok();


        if cstr!("/sdcard")
            .bind_mount_to(cstr!("/sdcard"), false)
            .is_ok()
        {
            debug!("Bind mount /sdcard -> /sdcard");
        } else {

            cstr!(REDIR_PATH)
                .bind_mount_to(cstr!("/sdcard"), false)
                .log_ok();
            debug!("Bind mount {REDIR_PATH} -> /sdcard");
        }
    }
}
