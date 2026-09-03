#[cfg(target_pointer_width = "64")]
use crate::consts::MAIN_BIN_NAME_32;
use crate::consts::{
    MAIN_BIN_NAME, MODULEMNT, MODULEROOT, MODULEUPGRADE, POLICY_BIN_NAME, WORKERDIR,
};
use crate::daemon::MagiskD;
use crate::ffi::{ModuleInfo, exec_module_scripts, exec_script, get_magisk_tmp};
use crate::mount::setup_module_mount;
use crate::resetprop::load_prop_file;
use crate::udonge::{UDONGE_MODULE_NAME, UDONGE_RUNTIME, transport_enabled as udonge_enabled};
use base::const_format::concatcp;
use base::{
    DirEntry, Directory, FsPathBuilder, LoggedResult, OsResult, ResultExt, SilentLogExt, Utf8CStr,
    Utf8CStrBuf, Utf8CString, WalkResult, clone_attr, cstr, debug, error, info, libc, raw_cstr,
    warn,
};
use nix::fcntl::OFlag;
use nix::mount::MsFlags;
use nix::unistd::UnlinkatFlags;
use std::collections::BTreeMap;
use std::os::fd::IntoRawFd;
use std::path::{Component, Path};
use std::ptr;
use std::sync::atomic::Ordering;

const MAGISK_BIN_INJECT_PARTITIONS: [&Utf8CStr; 4] = [
    cstr!("/system/"),
    cstr!("/vendor/"),
    cstr!("/product/"),
    cstr!("/system_ext/"),
];

const SECONDARY_READ_ONLY_PARTITIONS: [&Utf8CStr; 3] =
    [cstr!("/vendor"), cstr!("/product"), cstr!("/system_ext")];

type FsNodeMap = BTreeMap<String, FsNode>;

macro_rules! module_log {
    ($($args:tt)+) => {
        debug!("{:8}: {} <- {}", $($args)+)
    }
}

#[allow(unused_variables)]
fn bind_mount(reason: &str, src: &Utf8CStr, dest: &Utf8CStr, rec: bool) {
    module_log!(reason, dest, src);


    src.bind_mount_to(dest, rec).log_ok();
    dest.remount_mount_point_flags(MsFlags::MS_RDONLY).log_ok();
}

fn mount_dummy<'a>(
    reason: &str,
    src: &Utf8CStr,
    dest: &'a Utf8CStr,
    is_dir: bool,
) -> OsResult<'a, ()> {
    if is_dir {
        dest.mkdir(0o000)?;
    } else {
        dest.create(OFlag::O_CREAT | OFlag::O_RDONLY | OFlag::O_CLOEXEC, 0o000)?;
    }
    bind_mount(reason, src, dest, false);
    Ok(())
}








struct PathTracker<'a> {
    path: &'a mut dyn Utf8CStrBuf,
    len: usize,
}

impl PathTracker<'_> {
    fn from<'a>(path: &'a mut dyn Utf8CStrBuf) -> PathTracker<'a> {
        let len = path.len();
        PathTracker { path, len }
    }

    fn append(&mut self, name: &str) -> PathTracker<'_> {
        let len = self.path.len();
        self.path.append_path(name);
        PathTracker {
            path: self.path,
            len,
        }
    }

    fn reborrow(&mut self) -> PathTracker<'_> {
        Self::from(self.path)
    }
}

impl Drop for PathTracker<'_> {

    fn drop(&mut self) {
        self.path.truncate(self.len);
    }
}


struct ModulePaths<'a> {
    real: PathTracker<'a>,
    module: PathTracker<'a>,
    module_mnt: PathTracker<'a>,
}

impl ModulePaths<'_> {
    fn new<'a>(
        real: &'a mut dyn Utf8CStrBuf,
        module: &'a mut dyn Utf8CStrBuf,
        module_mnt: &'a mut dyn Utf8CStrBuf,
    ) -> ModulePaths<'a> {
        real.append_path("/");
        module.append_path(MODULEROOT);
        module_mnt
            .append_path(get_magisk_tmp())
            .append_path(MODULEMNT);
        ModulePaths {
            real: PathTracker::from(real),
            module: PathTracker::from(module),
            module_mnt: PathTracker::from(module_mnt),
        }
    }

    fn set_module(&mut self, module: &str) -> ModulePaths<'_> {
        ModulePaths {
            real: self.real.reborrow(),
            module: self.module.append(module),
            module_mnt: self.module_mnt.append(module),
        }
    }

    fn append(&mut self, name: &str) -> ModulePaths<'_> {
        ModulePaths {
            real: self.real.append(name),
            module: self.module.append(name),
            module_mnt: self.module_mnt.append(name),
        }
    }


    fn real(&self) -> &Utf8CStr {
        self.real.path
    }


    fn module(&self) -> &Utf8CStr {
        self.module.path
    }


    fn module_mnt(&self) -> &Utf8CStr {
        self.module_mnt.path
    }
}


struct MountPaths<'a> {
    real: PathTracker<'a>,
    worker: PathTracker<'a>,
}

impl MountPaths<'_> {
    fn new<'a>(real: &'a mut dyn Utf8CStrBuf, worker: &'a mut dyn Utf8CStrBuf) -> MountPaths<'a> {
        real.append_path("/");
        worker.append_path(get_magisk_tmp()).append_path(WORKERDIR);
        MountPaths {
            real: PathTracker::from(real),
            worker: PathTracker::from(worker),
        }
    }

    fn append(&mut self, name: &str) -> MountPaths<'_> {
        MountPaths {
            real: self.real.append(name),
            worker: self.worker.append(name),
        }
    }

    fn reborrow(&mut self) -> MountPaths<'_> {
        MountPaths {
            real: self.real.reborrow(),
            worker: self.worker.reborrow(),
        }
    }


    fn real(&self) -> &Utf8CStr {
        self.real.path
    }


    fn worker(&self) -> &Utf8CStr {
        self.worker.path
    }
}

enum FsNode {
    Directory { children: FsNodeMap },
    File { src: Utf8CString },
    Symlink { target: Utf8CString },
    MagiskLink,
    Whiteout,
}

impl FsNode {
    fn new_dir() -> FsNode {
        FsNode::Directory {
            children: BTreeMap::new(),
        }
    }

    fn collect(&mut self, mut paths: ModulePaths) -> LoggedResult<()> {
        let FsNode::Directory { children } = self else {
            return Ok(());
        };
        let mut dir = Directory::open(paths.module())?;

        while let Some(entry) = dir.read()? {
            let entry_paths = paths.append(entry.name());
            let path = entry_paths.module();
            if entry.is_dir() {
                let node = children
                    .entry(entry.name().to_string())
                    .or_insert_with(FsNode::new_dir);
                node.collect(entry_paths)?;
            } else if entry.is_symlink() {

                let mut link = cstr::buf::default();
                path.read_link(&mut link)?;
                children
                    .entry(entry.name().to_string())
                    .or_insert_with(|| FsNode::Symlink {
                        target: link.to_owned(),
                    });
            } else {
                if entry.is_char_device() {
                    let attr = path.get_attr()?;
                    if attr.is_whiteout() {
                        children
                            .entry(entry.name().to_string())
                            .or_insert_with(|| FsNode::Whiteout);
                        continue;
                    }
                }
                if entry_paths.real().exists() {
                    clone_attr(entry_paths.real(), path)?;
                }
                children
                    .entry(entry.name().to_string())
                    .or_insert_with(|| FsNode::File {

                        src: entry_paths.module_mnt().to_owned(),
                    });
            }
        }

        Ok(())
    }





    fn parent_should_be_tmpfs(&self, target_path: &Utf8CStr) -> bool {
        match self {
            FsNode::Directory { .. } | FsNode::File { .. } => {
                if let Ok(attr) = target_path.get_attr() {
                    attr.is_symlink()
                } else {
                    true
                }
            }
            _ => true,
        }
    }

    fn children(&mut self) -> Option<&mut FsNodeMap> {
        match self {
            FsNode::Directory { children } => Some(children),
            _ => None,
        }
    }

    fn commit(&mut self, mut path: MountPaths, is_root_dir: bool) -> LoggedResult<()> {
        match self {
            FsNode::Directory { children } => {
                let mut is_tmpfs = false;


                children.retain(|name, node| {
                    if name == ".replace" {
                        return if is_root_dir {
                            warn!("Unable to replace '{}', ignore request", path.real());
                            false
                        } else {
                            is_tmpfs = true;
                            true
                        };
                    }

                    let path = path.append(name);
                    if node.parent_should_be_tmpfs(path.real()) {
                        if is_root_dir {

                            warn!("Unable to add '{}', skipped", path.real());
                            return false;
                        }
                        is_tmpfs = true;
                    }
                    true
                });

                if is_tmpfs {
                    self.commit_tmpfs(path.reborrow())?;


                    bind_mount("move", path.worker(), path.real(), true);
                } else {
                    for (name, node) in children {
                        let path = path.append(name);
                        node.commit(path, false)?;
                    }
                }
            }
            FsNode::File { src } => {
                bind_mount("mount", src, path.real(), false);
            }
            _ => {
                error!("Unable to handle '{}': parent should be tmpfs", path.real());
            }
        }

        Ok(())
    }

    fn commit_tmpfs(&mut self, mut path: MountPaths) -> LoggedResult<()> {
        match self {
            FsNode::Directory { children } => {
                path.worker().mkdirs(0o000)?;
                if path.real().exists() {
                    clone_attr(path.real(), path.worker())?;
                } else if let Some(p) = path.worker().parent_dir() {
                    let parent = Utf8CString::from(p);
                    clone_attr(&parent, path.worker())?;
                }


                if let Some(FsNode::File { src }) = children.remove(".replace")
                    && let Some(replace_dir) = src.parent_dir()
                {
                    for (name, node) in children {
                        let path = path.append(name);
                        match node {
                            FsNode::Directory { .. } => {


                                let src = Utf8CString::from(replace_dir).join_path(name);
                                mount_dummy("mount", &src, path.worker(), true)?;
                            }
                            _ => node.commit_tmpfs(path)?,
                        }
                    }


                    return Ok(());
                }


                if let Ok(mut dir) = Directory::open(path.real()) {
                    while let Ok(Some(entry)) = dir.read() {
                        if children.contains_key(entry.name().as_str()) {

                            continue;
                        }

                        let path = path.append(entry.name());

                        if entry.is_dir() {




                            FsNode::new_dir().commit_tmpfs(path)?;
                        } else if entry.is_symlink() {
                            let mut link = cstr::buf::default();
                            entry.read_link(&mut link).log_ok();
                            FsNode::Symlink {
                                target: link.to_owned(),
                            }
                            .commit_tmpfs(path)?;
                        } else {

                            mount_dummy("mirror", path.real(), path.worker(), false)?;
                        }
                    }
                }


                for (name, node) in children {
                    let path = path.append(name);
                    node.commit_tmpfs(path)?;
                }
            }
            FsNode::File { src } => {
                mount_dummy("mount", src, path.worker(), false)?;
            }
            FsNode::Symlink { target } => {
                module_log!("mklink", path.worker(), target);
                path.worker().create_symlink_to(target)?;
                if path.real().exists() {
                    clone_attr(path.real(), path.worker())?;
                }
            }
            FsNode::MagiskLink => {
                if let Some(name) = path.real().file_name()
                    && name == "sp"
                {
                    module_log!("mklink", path.worker(), concatcp!("./", POLICY_BIN_NAME));
                    path.worker()
                        .create_symlink_to(cstr!(concatcp!("./", POLICY_BIN_NAME)))?;
                } else {
                    module_log!("mklink", path.worker(), concatcp!("./", MAIN_BIN_NAME));
                    path.worker()
                        .create_symlink_to(cstr!(concatcp!("./", MAIN_BIN_NAME)))?;
                }
            }
            FsNode::Whiteout => {
                module_log!("delete", path.real(), "null");
            }
        }
        Ok(())
    }
}

fn get_path_env() -> String {
    std::env::var_os("PATH")
        .and_then(|s| s.into_string().ok())
        .unwrap_or_default()
}

fn inject_magisk_bins(system: &mut FsNode, is_emulator: bool) {
    fn inject(children: &mut FsNodeMap) {
        let mut path = cstr::buf::default().join_path(get_magisk_tmp());



        let len = path.len();
        path.append_path(MAIN_BIN_NAME);
        children.insert(
            MAIN_BIN_NAME.to_string(),
            FsNode::File {
                src: path.to_owned(),
            },
        );

        path.truncate(len);
        path.append_path(POLICY_BIN_NAME);
        children.insert(
            POLICY_BIN_NAME.to_string(),
            FsNode::File {
                src: path.to_owned(),
            },
        );


        children.insert("su".to_string(), FsNode::MagiskLink);
        children.insert("resetprop".to_string(), FsNode::MagiskLink);
        children.insert("sp".to_string(), FsNode::MagiskLink);
    }


    fn strip_system_prefix(orig_item: &str) -> String {
        match orig_item.strip_prefix("/system/") {
            Some(rest) => format!("/{rest}"),
            None => orig_item.to_string(),
        }
    }

    let path_env = get_path_env();
    let mut candidates = vec![];

    for orig_item in path_env.split(':') {

        if !MAGISK_BIN_INJECT_PARTITIONS
            .iter()
            .any(|p| orig_item.starts_with(p.as_str()))
        {
            continue;
        }

        if orig_item.starts_with("/system/apex/") {
            continue;
        }


        if is_emulator && orig_item.starts_with("/system/xbin") {
            continue;
        }


        let su_path = Utf8CString::from(format!("{orig_item}/su"));
        if su_path.exists() {
            let item = strip_system_prefix(orig_item);
            candidates.push((item, 0));
            break;
        }

        let path = Utf8CString::from(orig_item);
        if let Ok(attr) = path.get_attr()
            && (attr.st.st_mode & 0x0001) != 0
            && let Ok(mut dir) = Directory::open(&path)
        {
            let mut count = 0;
            if dir
                .pre_order_walk(|e| {
                    if e.is_file() {
                        count += 1;
                    }
                    Ok(WalkResult::Continue)
                })
                .is_err()
            {

                continue;
            }
            let item = strip_system_prefix(orig_item);
            candidates.push((item, count));
        }
    }


    candidates.sort_by_key(|&(_, count)| count);

    'path_loop: for candidate in candidates {
        let components = Path::new(&candidate.0)
            .components()
            .filter(|c| matches!(c, Component::Normal(_)))
            .filter_map(|c| c.as_os_str().to_str());

        let mut curr = match system {
            FsNode::Directory { children } => children,
            _ => continue,
        };

        for dir in components {
            let node = curr.entry(dir.to_owned()).or_insert_with(FsNode::new_dir);
            match node {
                FsNode::Directory { children } => curr = children,
                _ => continue 'path_loop,
            }
        }


        inject(curr);
        return;
    }


    let node = system
        .children()
        .map(|c| c.entry("bin".to_string()).or_insert_with(FsNode::new_dir));
    if let Some(FsNode::Directory { children }) = node {
        inject(children)
    }
}

fn inject_zygisk_bins(name: &str, system: &mut FsNode) {
    #[cfg(target_pointer_width = "64")]
    let has_32_bit = cstr!("/system/bin/linker").exists();

    #[cfg(target_pointer_width = "32")]
    let has_32_bit = true;

    if has_32_bit {
        let lib = system
            .children()
            .map(|c| c.entry("lib".to_string()).or_insert_with(FsNode::new_dir));
        if let Some(FsNode::Directory { children }) = lib {
            let mut bin_path = cstr::buf::default().join_path(get_magisk_tmp());

            #[cfg(target_pointer_width = "64")]
            bin_path.append_path(MAIN_BIN_NAME_32);

            #[cfg(target_pointer_width = "32")]
            bin_path.append_path(MAIN_BIN_NAME);




            if bin_path.exists() {
                children.insert(
                    name.to_string(),
                    FsNode::File {
                        src: bin_path.to_owned(),
                    },
                );
            }
        }
    }

    #[cfg(target_pointer_width = "64")]
    if cstr!("/system/bin/linker64").exists() {
        let lib64 = system
            .children()
            .map(|c| c.entry("lib64".to_string()).or_insert_with(FsNode::new_dir));
        if let Some(FsNode::Directory { children }) = lib64 {
            let bin_path = cstr::buf::default()
                .join_path(get_magisk_tmp())
                .join_path(MAIN_BIN_NAME);

            children.insert(
                name.to_string(),
                FsNode::File {
                    src: bin_path.to_owned(),
                },
            );
        }
    }
}

fn upgrade_modules() -> LoggedResult<()> {
    let mut upgrade = Directory::open(cstr!(MODULEUPGRADE)).silent()?;
    let root = Directory::open(cstr!(MODULEROOT))?;
    while let Some(e) = upgrade.read()? {
        if !e.is_dir() {
            continue;
        }
        let module_name = e.name();
        let mut disable = false;

        if root.contains_path(module_name) {
            let module = root.open_as_dir_at(module_name)?;

            disable = module.contains_path(cstr!("disable"));
            module.remove_all()?;
            root.unlink_at(module_name, UnlinkatFlags::RemoveDir)?;
        }
        info!("Upgrade / New module: {module_name}");
        e.rename_to(&root, module_name)?;
        if disable {
            let path = cstr::buf::default()
                .join_path(module_name)
                .join_path("disable");
            let _ = root.open_as_file_at(
                &path,
                OFlag::O_RDONLY | OFlag::O_CREAT | OFlag::O_CLOEXEC,
                0,
            )?;
        }
    }
    upgrade.remove_all()?;
    cstr!(MODULEUPGRADE).remove()?;
    Ok(())
}

fn for_each_module(mut func: impl FnMut(&DirEntry) -> LoggedResult<()>) -> LoggedResult<()> {
    let mut root = Directory::open(cstr!(MODULEROOT))?;
    while let Some(ref e) = root.read()? {
        if e.is_dir() && e.name() != ".core" {
            func(e)?;
        }
    }
    Ok(())
}

fn has_external_module_work() -> bool {
    if cstr!(MODULEUPGRADE).exists() {
        return true;
    }
    let Ok(mut root) = Directory::open(cstr!(MODULEROOT)) else {
        return false;
    };
    while let Ok(Some(entry)) = root.read() {
        if entry.is_dir() && entry.name() != ".core" {
            return true;
        }
    }
    false
}

pub fn disable_modules() {
    for_each_module(|e| {
        let dir = e.open_as_dir()?;
        dir.open_as_file_at(
            cstr!("disable"),
            OFlag::O_RDONLY | OFlag::O_CREAT | OFlag::O_CLOEXEC,
            0,
        )?;
        Ok(())
    })
    .log_ok();
}

fn run_uninstall_script(module_name: &Utf8CStr) {
    let script = cstr::buf::default()
        .join_path(MODULEROOT)
        .join_path(module_name)
        .join_path("uninstall.sh");
    exec_script(&script);
}

pub fn remove_modules() {
    for_each_module(|e| {
        let dir = e.open_as_dir()?;
        if dir.contains_path(cstr!("uninstall.sh")) {
            run_uninstall_script(e.name());
        }
        Ok(())
    })
    .log_ok();
    cstr!(MODULEROOT).remove_all().log_ok();
}

fn collect_modules(zygisk_enabled: bool, open_zygisk: bool) -> Vec<ModuleInfo> {
    let mut modules = Vec::new();

    #[allow(unused_mut)]
    for_each_module(|e| {
        let name = e.name();
        let dir = e.open_as_dir()?;
        if dir.contains_path(cstr!("remove")) {
            info!("{name}: remove");
            if dir.contains_path(cstr!("uninstall.sh")) {
                run_uninstall_script(name);
            }
            dir.remove_all()?;
            e.unlink()?;
            return Ok(());
        }
        dir.unlink_at(cstr!("update"), UnlinkatFlags::NoRemoveDir)
            .ok();
        if dir.contains_path(cstr!("disable")) {
            return Ok(());
        }

        let mut z32 = -1;
        let mut z64 = -1;

        let is_zygisk = dir.contains_path(cstr!("zygisk"));

        if zygisk_enabled {

            if name == "riru-core" || dir.contains_path(cstr!("riru")) {
                return Ok(());
            }

            if open_zygisk && is_zygisk {
                #[cfg(target_arch = "arm")]
                {
                    z32 = open_fd_safe(&dir, cstr!("zygisk/armeabi-v7a.so"));
                }
                #[cfg(target_arch = "aarch64")]
                {
                    z32 = open_fd_safe(&dir, cstr!("zygisk/armeabi-v7a.so"));
                    z64 = open_fd_safe(&dir, cstr!("zygisk/arm64-v8a.so"));
                }
                #[cfg(target_arch = "x86")]
                {
                    z32 = open_fd_safe(&dir, cstr!("zygisk/x86.so"));
                }
                #[cfg(target_arch = "x86_64")]
                {
                    z32 = open_fd_safe(&dir, cstr!("zygisk/x86.so"));
                    z64 = open_fd_safe(&dir, cstr!("zygisk/x86_64.so"));
                }
                #[cfg(target_arch = "riscv64")]
                {
                    z64 = open_fd_safe(&dir, cstr!("zygisk/riscv64.so"));
                }
                dir.unlink_at(cstr!("zygisk/unloaded"), UnlinkatFlags::NoRemoveDir)
                    .ok();
            }
        } else {

            if is_zygisk {
                info!("{name}: ignore");
                return Ok(());
            }
        }
        modules.push(ModuleInfo {
            name: name.to_string(),
            z32,
            z64,
        });
        Ok(())
    })
    .log_ok();

    modules
}

fn open_fd_safe(dir: &Directory, name: &Utf8CStr) -> i32 {
    dir.open_as_file_at(name, OFlag::O_RDONLY | OFlag::O_CLOEXEC, 0)
        .log()
        .map(IntoRawFd::into_raw_fd)
        .unwrap_or(-1)
}

fn append_udonge(modules: &mut Vec<ModuleInfo>) {
    if !udonge_enabled() {
        return;
    }
    let Ok(dir) = Directory::open(cstr!(UDONGE_RUNTIME)) else {
        return;
    };
    #[cfg(target_arch = "arm")]
    let (z32, z64) = (open_fd_safe(&dir, cstr!("zygisk/armeabi-v7a.so")), -1);
    #[cfg(target_arch = "aarch64")]
    let (z32, z64) = (
        open_fd_safe(&dir, cstr!("zygisk/armeabi-v7a.so")),
        open_fd_safe(&dir, cstr!("zygisk/arm64-v8a.so")),
    );
    #[cfg(target_arch = "x86")]
    let (z32, z64) = (open_fd_safe(&dir, cstr!("zygisk/x86.so")), -1);
    #[cfg(target_arch = "x86_64")]
    let (z32, z64) = (
        open_fd_safe(&dir, cstr!("zygisk/x86.so")),
        open_fd_safe(&dir, cstr!("zygisk/x86_64.so")),
    );
    #[cfg(target_arch = "riscv64")]
    let (z32, z64) = (-1, -1);
    if z32 >= 0 || z64 >= 0 {
        modules.push(ModuleInfo {
            name: UDONGE_MODULE_NAME.to_string(),
            z32,
            z64,
        });
    }
}

fn convert_zygisk_modules_to_memfd(modules: &mut [ModuleInfo]) {
    let mut use_memfd = true;
    let mut convert = |fd: i32| -> i32 {
        if fd < 0 {
            return fd;
        }
        if use_memfd {
            let memfd = unsafe {
                libc::syscall(
                    libc::SYS_memfd_create,
                    raw_cstr!("jit-cache"),
                    libc::MFD_CLOEXEC,
                ) as i32
            };
            if memfd >= 0 {
                unsafe {
                    let copied = libc::sendfile(memfd, fd, ptr::null_mut(), i32::MAX as usize) >= 0
                        && libc::lseek(memfd, 0, libc::SEEK_SET) >= 0;
                    if copied {
                        libc::close(fd);
                        return memfd;
                    }
                    libc::close(memfd);
                }
            }
            use_memfd = false;
        }
        fd
    };
    modules.iter_mut().for_each(|module| {
        module.z32 = convert(module.z32);
        module.z64 = convert(module.z64);
    });
}

impl MagiskD {
    pub fn handle_modules(&self) {
        let zygisk = self.zygisk_enabled.load(Ordering::Acquire);
        let inject_builtins = self.zygote_injection_enabled.load(Ordering::Acquire);
        if !has_external_module_work() {
            let mut modules = Vec::new();
            let needs_core_mount = inject_builtins
                || get_magisk_tmp() != "/sbin"
                || get_path_env().split(':').all(|path| path != "/sbin");
            if needs_core_mount {
                setup_module_mount();
                self.apply_modules(&modules);
            }
            if inject_builtins {
                append_udonge(&mut modules);
                convert_zygisk_modules_to_memfd(&mut modules);
            }
            self.module_list.set(modules).ok();
            return;
        }

        setup_module_mount();
        upgrade_modules().ok();

        let modules = collect_modules(zygisk, false);
        exec_module_scripts(cstr!("post-fs-data"), &modules);


        let mut modules = collect_modules(zygisk, true);
        self.apply_modules(&modules);
        if inject_builtins {
            append_udonge(&mut modules);
            convert_zygisk_modules_to_memfd(&mut modules);
        }

        self.module_list.set(modules).ok();
    }

    fn apply_modules(&self, module_list: &[ModuleInfo]) {
        let mut system = FsNode::new_dir();


        let mut buf1 = cstr::buf::dynamic(256);
        let mut buf2 = cstr::buf::dynamic(256);
        let mut buf3 = cstr::buf::dynamic(256);

        let mut paths = ModulePaths::new(&mut buf1, &mut buf2, &mut buf3);






        for info in module_list {
            let mut paths = paths.set_module(&info.name);


            let prop = paths.append("system.prop");
            if prop.module().exists() {
                load_prop_file(prop.module());
            }
            drop(prop);


            let skip = paths.append("skip_mount");
            if skip.module().exists() {
                continue;
            }
            drop(skip);


            let sys = paths.append("system");
            if sys.module().exists() {
                info!("{}: loading module files", &info.name);
                system.collect(sys).log_ok();
            }
        }









        if get_magisk_tmp() != "/sbin" || get_path_env().split(":").all(|s| s != "/sbin") {
            inject_magisk_bins(&mut system, self.is_emulator);
        }


        if self.zygote_injection_enabled.load(Ordering::Acquire) {
            let mut zygisk = self.zygisk.lock();
            zygisk.set_prop();
            inject_zygisk_bins(&zygisk.lib_name, &mut system);
        }









        let mut roots = BTreeMap::new();
        if let FsNode::Directory { children } = &mut system {
            for dir in SECONDARY_READ_ONLY_PARTITIONS {

                if let Ok(attr) = dir.get_attr()
                    && attr.is_dir()
                {
                    let name = dir.trim_start_matches('/');
                    if let Some(root) = children.remove(name) {
                        roots.insert(name, root);
                    }
                }
            }
        }
        roots.insert("system", system);

        drop(paths);
        let mut paths = MountPaths::new(&mut buf1, &mut buf2);

        for (dir, mut root) in roots {







            let paths = paths.append(dir);
            root.commit(paths, true).log_ok();
        }
    }
}
