use crate::consts::{BUILD_INIT_LD_NAME, PREINITMIRR, SELINUXMOCK};
use crate::ffi::{MagiskInit, preload_ack, preload_lib, preload_policy, split_plat_cil};
use base::const_format::concatcp;
use base::nix::fcntl::OFlag;
use base::{
    BytesExt, LibcReturn, LoggedResult, MappedFile, ResultExt, Utf8CStr, cstr, debug, error, info,
    libc, raw_cstr,
};
use magiskpolicy::ffi::SePolicy;
use std::io::{Read, Write};
use std::ptr;
use std::thread::sleep;
use std::time::Duration;

const MOCK_VERSION: &Utf8CStr = cstr!(concatcp!(SELINUXMOCK, "/version"));
const MOCK_LOAD: &Utf8CStr = cstr!(concatcp!(SELINUXMOCK, "/load"));
const MOCK_ENFORCE: &Utf8CStr = cstr!(concatcp!(SELINUXMOCK, "/enforce"));
const MOCK_REQPROT: &Utf8CStr = cstr!(concatcp!(SELINUXMOCK, "/checkreqprot"));

const SELINUX_MNT: &str = "/sys/fs/selinux";
const SELINUX_ENFORCE: &Utf8CStr = cstr!(concatcp!(SELINUX_MNT, "/enforce"));
const SELINUX_LOAD: &Utf8CStr = cstr!(concatcp!(SELINUX_MNT, "/load"));
const SELINUX_REQPROT: &Utf8CStr = cstr!(concatcp!(SELINUX_MNT, "/checkreqprot"));

enum SePatchStrategy {




    LdPreload,



    SelinuxFs,




    Legacy,
}









fn mock_fifo(target: &Utf8CStr, mock: &Utf8CStr) -> LoggedResult<()> {
    debug!("Hijack [{}]", target);
    mock.mkfifo(0o666)?;
    mock.bind_mount_to(target, false).log()
}

fn mock_file(target: &Utf8CStr, mock: &Utf8CStr) -> LoggedResult<()> {
    debug!("Hijack [{}]", target);
    drop(mock.create(OFlag::O_RDONLY, 0o666)?);
    mock.bind_mount_to(target, false).log()
}

impl MagiskInit {
    pub(crate) fn handle_sepolicy(&mut self) {
        self.handle_sepolicy_impl().ok();
    }

    fn cleanup_and_load(&self, rules: &str) {

        cstr!("/init").unmount().ok();
        SELINUX_LOAD.unmount().log_ok();
        SELINUX_ENFORCE.unmount().ok();
        SELINUX_REQPROT.unmount().ok();

        let mut sepol = SePolicy::from_file(MOCK_LOAD);
        sepol.magisk_rules();
        sepol.load_rules(rules);
        sepol.to_file(SELINUX_LOAD);


        cstr!("/init")
            .follow_link()
            .set_secontext(cstr!("u:object_r:init_exec:s0"))
            .ok();


        self.restore_overlay_contexts();
    }

    fn handle_sepolicy_impl(&mut self) -> LoggedResult<()> {
        cstr!(SELINUXMOCK).mkdir(0o711)?;

        let mut rules = String::new();
        let mut policy_ver = cstr!("/selinux_version");
        let rule_file = cstr!(concatcp!("/data/", PREINITMIRR, "/sepolicy.rule"));
        if rule_file.exists() {
            debug!("Loading custom sepolicy patch: [{}]", rule_file);
            rule_file
                .open(OFlag::O_RDONLY)?
                .read_to_string(&mut rules)?;
        }



        let strat: SePatchStrategy;

        if cstr!("/system/bin/init").exists() {
            strat = SePatchStrategy::LdPreload;
        } else {
            let init = MappedFile::open(cstr!("/init"))?;
            if init.contains(split_plat_cil().as_str().as_bytes()) {

                strat = SePatchStrategy::SelinuxFs;
            } else if init.contains(policy_ver.as_bytes()) {

                strat = SePatchStrategy::Legacy;
            } else if init.contains(cstr!("/sepolicy_version").as_bytes()) {

                policy_ver = cstr!("/sepolicy_version");
                strat = SePatchStrategy::Legacy;
            } else {
                error!("Unknown sepolicy setup, abort...");
                return Ok(());
            }
        }



        match strat {
            SePatchStrategy::LdPreload => {
                info!("SePatchStrategy: LD_PRELOAD");

                cstr!(BUILD_INIT_LD_NAME).copy_to(preload_lib())?;
                unsafe {
                    libc::setenv(raw_cstr!("LD_PRELOAD"), preload_lib().as_ptr(), 1);
                }
                preload_ack().mkfifo(0o666)?;
            }
            SePatchStrategy::SelinuxFs => {
                info!("SePatchStrategy: SELINUXFS");

                if !SELINUX_ENFORCE.exists() {



                    cstr!("/proc").remount_with_data(cstr!("hidepid=2,gid=3009"))?;


                    self.mount_list.retain(|s| s != "/proc" && s != "/sys");


                    unsafe {
                        libc::mount(
                            raw_cstr!("selinuxfs"),
                            raw_cstr!(SELINUX_MNT),
                            raw_cstr!("selinuxfs"),
                            0,
                            ptr::null(),
                        )
                        .check_err()?;
                    }
                }

                mock_file(SELINUX_LOAD, MOCK_LOAD)?;
                mock_fifo(SELINUX_ENFORCE, MOCK_ENFORCE)?;
            }
            SePatchStrategy::Legacy => {
                info!("SePatchStrategy: LEGACY");

                if !policy_ver.exists() {

                    drop(policy_ver.create(OFlag::O_RDONLY, 0o666)?);
                }




                mock_fifo(policy_ver, MOCK_VERSION)?;
            }
        }


        let pid = unsafe { libc::fork() };
        if pid != 0 {
            return Ok(());
        }



        let wait = Duration::from_millis(100);

        if matches!(strat, SePatchStrategy::Legacy) {

            while !SELINUX_ENFORCE.exists() {

                sleep(wait);
            }









            mock_file(SELINUX_LOAD, MOCK_LOAD)?;
            mock_fifo(SELINUX_REQPROT, MOCK_REQPROT)?;


            drop(MOCK_VERSION.open(OFlag::O_WRONLY)?);

            policy_ver.unmount()?;




        }



        match strat {
            SePatchStrategy::LdPreload => {

                let mut ack_fd = preload_ack().open(OFlag::O_WRONLY)?;

                let mut sepol = SePolicy::from_file(preload_policy());

                // Keep a copy of the original policy for future use
                preload_policy().copy_to(MOCK_LOAD)?;

                // Remove the files before loading the policy
                preload_policy().remove()?;
                preload_ack().remove()?;

                sepol.magisk_rules();
                sepol.load_rules(&rules);
                sepol.to_file(SELINUX_LOAD);

                self.restore_overlay_contexts();


                ack_fd.write_all("0".as_bytes())?;
            }
            SePatchStrategy::SelinuxFs => {

                let mut mock_enforce = MOCK_ENFORCE.open(OFlag::O_WRONLY)?;

                self.cleanup_and_load(&rules);


                let mut data = vec![];
                SELINUX_ENFORCE
                    .open(OFlag::O_RDONLY)?
                    .read_to_end(&mut data)?;
                mock_enforce.write_all(&data)?;
            }
            SePatchStrategy::Legacy => {
                let mut sz = 0_usize;

                loop {
                    let attr = MOCK_LOAD.get_attr()?;
                    if sz != 0 && sz == attr.st.st_size as usize {
                        break;
                    }
                    sz = attr.st.st_size as usize;

                    sleep(wait);
                }

                self.cleanup_and_load(&rules);



                SELINUX_REQPROT
                    .open(OFlag::O_WRONLY)?
                    .write_all("0".as_bytes())?;
                let mut v = vec![];
                MOCK_REQPROT.open(OFlag::O_RDONLY)?.read_to_end(&mut v)?;
            }
        }





        std::process::exit(0);
    }
}
