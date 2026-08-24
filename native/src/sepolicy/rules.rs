use crate::SePolicy;
use crate::consts::{BUILD_UDONGE_FILE_TYPE, SEPOL_FILE_TYPE, SEPOL_PROC_DOMAIN};
use crate::ffi::Xperm;
use base::{LogLevel, set_log_level_state};

macro_rules! rules {
    (@args all) => {
        vec![]
    };
    (@args xall) => {
        vec![Xperm { low: 0x0000, high: 0xFFFF, reset: false }]
    };
    (@args svcmgr) => {
        vec!["servicemanager", "vndservicemanager", "hwservicemanager"]
    };
    (@args [proc]) => {
        vec![SEPOL_PROC_DOMAIN]
    };
    (@args [file]) => {
        vec![SEPOL_FILE_TYPE]
    };
    (@args proc) => {
        SEPOL_PROC_DOMAIN
    };
    (@args file) => {
        SEPOL_FILE_TYPE
    };
    (@args [$($arg:tt)*]) => {
        vec![$($arg)*]
    };
    (@args $arg:expr) => {
        $arg
    };
    (@stmt $self:ident) => {};
    (@stmt $self:ident $action:ident($($args:tt),*); $($res:tt)*) => {
        $self.$action($(rules!(@args $args)),*);
        rules!{@stmt $self $($res)* }
    };
    (use $self:ident; $($res:tt)*) => {{
        rules!{@stmt $self $($res)* }
    }};
}

impl SePolicy {
    pub fn magisk_rules(&mut self) {

        set_log_level_state(LogLevel::Warn, false);
        rules! {
            use self;

            deny(all, ["kernel"], ["security"], ["load_policy"]);
            type_(proc, ["domain"]);
            typeattribute([proc], ["mlstrustedsubject", "netdomain", "appdomain"]);
            type_(file, ["file_type"]);
            typeattribute([file], ["mlstrustedobject"]);

            type_(BUILD_UDONGE_FILE_TYPE, ["file_type"]);
            allow(["keystore"], [BUILD_UDONGE_FILE_TYPE], ["file"],
                ["open", "read", "getattr", "map", "execute"]);


            allow(["domain"], [file],
                ["file", "dir", "fifo_file", "chr_file", "lnk_file", "sock_file"], all);


            allow([proc], [
                "fs_type", "dev_type", "file_type", "domain",
                "service_manager_type", "hwservice_manager_type", "vndservice_manager_type",
                "port_type", "node_type", "property_type"
            ], all, all);


            allowxperm([proc], ["fs_type", "dev_type", "file_type", "domain"],
                ["blk_file", "fifo_file", "chr_file"], xall);
            allowxperm([proc], [proc], ["tcp_socket", "udp_socket", "rawip_socket"], xall);


            allow(svcmgr, [proc], ["dir"], ["search"]);
            allow(svcmgr, [proc], ["file"], ["open", "read", "map"]);
            allow(svcmgr, [proc], ["process"], ["getattr"]);
            allow(["domain"], [proc], ["binder"], ["call", "transfer"]);


            allow(["domain"], [proc], ["process"], ["sigchld"]);
            allow(["domain"], [proc], ["fd"], ["use"]);
            allow(["domain"], [proc], ["fifo_file"], ["write", "read", "open", "getattr"]);


            allow(["zygote", "shell", "platform_app",
                "system_app", "priv_app", "untrusted_app", "untrusted_app_all"],
                [proc], ["unix_stream_socket"], ["connectto", "getopt"]);



            allow(["init", "zygote", "shell"], ["tmpfs"], ["file"], all);


            allow(["kernel"], ["rootfs", "tmpfs"], ["chr_file"], ["write"]);


            allow(["kernel"], ["tmpfs"], ["fifo_file"], ["open", "read", "write"]);


            allow(["rootfs"], ["labeledfs", "tmpfs"], ["filesystem"], ["associate"]);
            allow([file], ["pipefs", "devpts"], ["filesystem"], ["associate"]);
            allow(["kernel"], all, ["file"], ["relabelto"]);
            allow(["kernel"], ["tmpfs"], ["file"], ["relabelfrom"]);


            allow(["kernel"], ["kernel"], ["process"], ["setcurrent"]);
            allow(["kernel"], [proc], ["process"], ["dyntransition"]);


            allow(["init"], [proc], ["process"], all);


            allow(["zygote"], ["zygote"], ["process"], ["execmem"]);
            allow(["zygote"], ["fs_type"], ["filesystem"], ["unmount"]);


            dontaudit(["llkd"], [proc], ["process"], ["ptrace"]);


            deny(["init"], ["adb_data_file"], ["dir"], ["search"]);
            deny(["vendor_init"], ["adb_data_file"], ["dir"], ["search"]);
        }

        #[cfg(any())]
        self.strip_dontaudit();

        set_log_level_state(LogLevel::Warn, true);
    }
}
