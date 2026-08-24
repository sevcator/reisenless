mod daemon;

use crate::thread::ThreadPool;
use base::{fd_get_attr, libc};
pub use daemon::{ZygiskState, zygisk_should_load_module};
use std::os::fd::RawFd;

#[unsafe(no_mangle)]
extern "C" fn exec_companion_entry(client: RawFd, companion_handler: extern "C" fn(RawFd)) {
    ThreadPool::exec_task(move || {
        let Ok(s1) = fd_get_attr(client) else {
            return;
        };

        companion_handler(client);





        if let Ok(s2) = fd_get_attr(client)
            && s1.st.st_dev == s2.st.st_dev
            && s1.st.st_ino == s2.st.st_ino
        {
            unsafe { libc::close(client) };
        }
    });
}
