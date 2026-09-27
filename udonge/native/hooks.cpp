#include "hooks.hpp"

#include <cstring>
#include <cstdint>
#include <cstdarg>
#include <cstdio>
#include <cerrno>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <linux/sockios.h>
#include <set>
#include <map>
#include <mutex>
#include <tuple>
#include <utility>
#include <vector>

#include "../../native/src/external/lsplt/lsplt/src/main/jni/include/lsplt.hpp"

// memfd_create syscall numbers
#ifndef __NR_memfd_create
# if defined(__aarch64__)
#   define __NR_memfd_create 279
# elif defined(__arm__)
#   define __NR_memfd_create 385
# elif defined(__x86_64__)
#   define __NR_memfd_create 319
# elif defined(__i386__)
#   define __NR_memfd_create 356
# endif
#endif
#ifndef MFD_CLOEXEC
# define MFD_CLOEXEC 0x0001U
#endif

namespace cloak {

static const Config *g_cfg = nullptr;
static zygisk::Api *g_api = nullptr;
static HookProfile g_profile = HookProfile::Full;

static unsigned char ascii_lower(unsigned char c) {
    return c >= 'A' && c <= 'Z' ? static_cast<unsigned char>(c + ('a' - 'A')) : c;
}

static bool contains_ci(const char *text, size_t text_len, const char *needle, size_t needle_len) {
    if (!text || !needle || needle_len == 0 || needle_len > text_len) return false;
    for (size_t i = 0; i + needle_len <= text_len; ++i) {
        size_t j = 0;
        while (j < needle_len &&
               ascii_lower(static_cast<unsigned char>(text[i + j])) ==
               ascii_lower(static_cast<unsigned char>(needle[j]))) ++j;
        if (j == needle_len) return true;
    }
    return false;
}

static bool contains_ci(const char *text, const std::string &needle) {
    return text && contains_ci(text, strlen(text), needle.data(), needle.size());
}

// ---- path blocklist ----
static const char *const kBlockedSubstr[] = {
    // Magisk / Zygisk core
    // NOTE: "zygisk" intentionally omitted — the linker reads /proc/self/maps to
    // locate libzygisk.so for self-cleanup (dlclose), and filtering that line out
    // causes Zygisk's destructor to access freed memory → SIGSEGV at 0x569a8.
    // "/data/adb" below also hides the boot-owned Udonge runtime.
    "magisk", "lsposed", "lspd", "riru", "shamiko",
    "/data/adb", "supersu", "/su/", "busybox",
    "/system/bin/su", "/system/xbin/su", "/sbin/su",
    "/product/bin/su", "/vendor/bin/su", "/odm/bin/su",
    "/debug_ramdisk",
};

// Extra patterns only applied to /proc/*/mounts and mountinfo.
// More aggressive — "worker" and "mirror" are Magisk-internal but too generic
// to block in the global file-access hooks.
static const char *const kMountsExtra[] = {
    "debug_ramdisk",  // Magisk's debug ramfs mount point
    "worker",         // Magisk overlay worker bind mounts
    "mirror",         // Magisk mirror bind mounts
    ".core",          // /sbin/.core or similar Magisk paths
    "/adb/modules/",
};

static bool basename_is_su(const char *path) {
    const char *b = strrchr(path, '/');
    b = b ? b + 1 : path;
    return strcmp(b, "su") == 0 || strcmp(b, "magisk") == 0 ||
           strcmp(b, "magiskpolicy") == 0 || strcmp(b, "resetprop") == 0;
}

static bool str_ends_with(const char *s, const char *suffix) {
    if (!s || !suffix) return false;
    size_t sl = strlen(s), el = strlen(suffix);
    return sl >= el && strcmp(s + sl - el, suffix) == 0;
}

// Return true if the path contains any user-configured ROM keyword.
// Called from is_blocked(), which is already guarded by a !path check.
static bool is_rom_path(const char *path) {
    if (!path) return false;
    if (str_ends_with(path, "_sepolicy.cil") || str_ends_with(path, "/sepolicy.cil") ||
        (strstr(path, "/etc/selinux/") && str_ends_with(path, ".cil"))) {
        return true;
    }

    if (!g_cfg || g_cfg->rom_keywords.empty()) return false;
    for (const auto &kw : g_cfg->rom_keywords)
        if (contains_ci(path, kw)) return true;

    // Duck Detector's ROM framework/recovery catalog also contains neutral
    // path names that cannot be matched by a ROM keyword.
    static const char *const exact_paths[] = {
        "/system/addon.d",
        "/system/bin/install-recovery.sh",
        "/system/etc/install-recovery.sh",
        "/vendor/bin/install-recovery.sh",
        "/system/framework/org.lineageos.platform-res.apk",
        "/system/framework/oat/arm64/org.lineageos.platform.vdex",
        "/system/framework/oat/arm64/org.lineageos.platform.odex",
        "/system/framework/oat/arm/org.lineageos.platform.vdex",
        "/system/framework/oat/arm/org.lineageos.platform.odex",
        "/system_ext/framework/org.lineageos.platform.jar",
        "/system/framework/crdroid-res.apk",
        "/system/framework/org.pixelexperience.platform-res.apk",
        "/system/framework/org.evolution.framework-res.apk",
        "/system/framework/co.aospa.framework-res.apk",
        "/system/framework/org.protonaosp.framework-res.apk",
        "/system/framework/org.omnirom.platform-res.apk",
        "/product/framework/org.lineageos.platform-res.apk",
        "/product/overlay/LineageSettingsProvider.apk",
        "/vendor/etc/selinux/vendor_sepolicy.cil",
        "/system_ext/etc/selinux/system_ext_sepolicy.cil",
        "/product/etc/selinux/product_sepolicy.cil",
        "/system/etc/selinux/plat_sepolicy.cil",
        "/odm/etc/selinux/odm_sepolicy.cil",
    };
    for (const char *blocked : exact_paths) {
        const size_t length = strlen(blocked);
        if (strncmp(path, blocked, length) == 0 &&
            (path[length] == '\0' || path[length] == '/')) return true;
    }
    return false;
}

static bool is_vpn_iface(const char *name) {
    if (!name || name[0] == '\0') return false;

    // Fast check for real/exempt interfaces
    if (strncmp(name, "lo", 2) == 0 && (name[2] == '\0' || name[2] == ':')) return false;
    if (strncmp(name, "wlan", 4) == 0) return false;
    if (strncmp(name, "rmnet", 5) == 0) return false;
    if (strncmp(name, "eth", 3) == 0) return false;
    if (strncmp(name, "dummy", 5) == 0) return false;
    if (strncmp(name, "ifb", 3) == 0) return false;
    if (strncmp(name, "bnep", 4) == 0) return false;
    if (strncmp(name, "rndis", 5) == 0) return false;
    if (strncmp(name, "ccmni", 5) == 0) return false;
    if (strncmp(name, "seth", 4) == 0) return false;

    static const char *const kVpnPrefixes[] = {
        "tun", "tap", "wg", "ppp", "ipsec", "xfrm",
        "utun", "l2tp", "gre", "tailscale", "zt", "he-ipv6"
    };

    size_t len = strlen(name);
    for (const char *prefix : kVpnPrefixes) {
        size_t plen = strlen(prefix);
        if (len >= plen && strncasecmp(name, prefix, plen) == 0) {
            return true;
        }
    }

    if (contains_ci(name, len, "vpn", 3)) {
        return true;
    }

    if (strncasecmp(name, "if", 2) == 0 && len > 2) {
        bool all_digits = true;
        for (size_t i = 2; i < len; ++i) {
            if (name[i] < '0' || name[i] > '9') {
                all_digits = false;
                break;
            }
        }
        if (all_digits) return true;
    }

    return false;
}

static bool is_vpn_path(const char *path) {
    if (!path) return false;
    const char *iface = nullptr;
    if (strncmp(path, "/sys/class/net/", 15) == 0) {
        iface = path + 15;
    } else if (strncmp(path, "/sys/devices/virtual/net/", 25) == 0) {
        iface = path + 25;
    } else if (strncmp(path, "/proc/sys/net/ipv4/conf/", 24) == 0) {
        iface = path + 24;
    } else if (strncmp(path, "/proc/sys/net/ipv6/conf/", 24) == 0) {
        iface = path + 24;
    }
    if (iface) {
        char name[64] = {0};
        const char *slash = strchr(iface, '/');
        size_t len = slash ? static_cast<size_t>(slash - iface) : strlen(iface);
        if (len > 0 && len < sizeof(name)) {
            memcpy(name, iface, len);
            if (is_vpn_iface(name)) return true;
        }
    }
    return false;
}

static bool is_mt_path(const char *path) {
    if (!path) return false;
    if (strcasestr(path, "/MT2") != nullptr || strcasecmp(path, "MT2") == 0 ||
        strncasecmp(path, "MT2/", 4) == 0 ||
        strcasestr(path, "bin.mt.") != nullptr) {
        return true;
    }
    return false;
}

static bool is_blocked(const char *path) {
    if (!path) return false;
    if (is_vpn_path(path)) return true;
    if (is_mt_path(path)) return true;
    for (const char *s : kBlockedSubstr)
        if (strstr(path, s)) return true;
    if (basename_is_su(path)) return true;
    if (is_rom_path(path)) return true;
    return false;
}

// ---- originals ----
static int     (*o_faccessat)(int, const char *, int, int);
static int     (*o_access)(const char *, int);
static int     (*o_stat)(const char *, struct stat *);
static int     (*o_lstat)(const char *, struct stat *);
static int     (*o_fstatat)(int, const char *, struct stat *, int);
static int     (*o_stat64)(const char *, struct stat64 *);
static int     (*o_lstat64)(const char *, struct stat64 *);
static int     (*o_fstatat64)(int, const char *, struct stat64 *, int);
static int     (*o_open)(const char *, int, ...);
static int     (*o_open_2)(const char *, int);
static int     (*o_openat)(int, const char *, int, ...);
static FILE   *(*o_fopen)(const char *, const char *);
static ssize_t (*o_readlink)(const char *, char *, size_t);
static ssize_t (*o_readlinkat)(int, const char *, char *, size_t);
static ssize_t (*o_read)(int, void *, size_t);
static void   *(*o_dlsym)(void *, const char *);
static int     (*o_selinux_check_access)(const char *, const char *, const char *, const char *, void *);
static int     (*o_prop_get)(const char *, char *);
static void    (*o_prop_read_cb)(const void *, void (*)(void *, const char *, const char *, uint32_t), void *);
static DIR    *(*o_opendir)(const char *);
static struct dirent *(*o_readdir)(DIR *);
static struct dirent64 *(*o_readdir64)(DIR *);
static int     (*o_execve)(const char *, char *const [], char *const []);
static int     (*o_execvp)(const char *, char *const []);
static int     (*o_execvpe)(const char *, char *const [], char *const []);
static char   *(*o_getenv)(const char *);
static void   *(*o_dlopen)(const char *, int);
static void   *(*o_android_dlopen_ext)(const char *, int, const void *);
static void   *(*o_loader_dlopen)(const char *, int, const void *);
static void   *(*o_loader_android_dlopen_ext)(const char *, int, const void *, const void *);
static jstring (*o_runtime_native_load)(JNIEnv *, jclass, jstring, jobject, jclass);
static int     (*o_getifaddrs)(struct ifaddrs **) = nullptr;
static int     (*o_ioctl)(int, unsigned long, ...) = nullptr;
static int     (*o_setsockopt)(int, int, int, const void *, socklen_t) = nullptr;
static void install_late_library_hooks();

static void refresh_late_library_hooks() {
    static thread_local bool refreshing = false;
    if (refreshing || !g_cfg) return;
    refreshing = true;
    install_late_library_hooks();
    refreshing = false;
}

static void *h_dlopen(const char *filename, int flags) {
    // Calling the public dlopen from this wrapper changes the linker caller to
    // our trampoline. Android then chooses the wrong linker namespace and may
    // reject vendor EGL/HAL dependencies. Forward the real call-site address
    // to the linker's exported entry point so namespace selection is unchanged.
    const void *caller = __builtin_return_address(0);
    void *handle = o_loader_dlopen
        ? o_loader_dlopen(filename, flags, caller)
        : o_dlopen(filename, flags);
    if (handle) refresh_late_library_hooks();
    return handle;
}

static void *h_android_dlopen_ext(const char *filename, int flags, const void *info) {
    const void *caller = __builtin_return_address(0);
    void *handle = o_loader_android_dlopen_ext
        ? o_loader_android_dlopen_ext(filename, flags, info, caller)
        : o_android_dlopen_ext(filename, flags, info);
    if (handle) refresh_late_library_hooks();
    return handle;
}

static void *h_dlsym(void *handle, const char *symbol) {
    if (symbol && (strstr(symbol, "threadLoopEv") != nullptr ||
                   strstr(symbol, "ANetworkSession") != nullptr)) {
        return nullptr;
    }
    if (o_dlsym) return o_dlsym(handle, symbol);
    return dlsym(handle, symbol);
}

static int h_selinux_check_access(const char *scon, const char *tcon, const char *tclass,
                                  const char *perm, void *auditdata) {
    if (scon && tcon) {
        if (strstr(scon, "su") || strstr(tcon, "su") ||
            strstr(scon, "magisk") || strstr(tcon, "magisk") ||
            strstr(scon, "adbroot") || strstr(tcon, "adbroot") ||
            strstr(scon, "droidspace") || strstr(tcon, "droidspace")) {
            errno = EACCES;
            return -1;
        }
    }
    if (o_selinux_check_access) {
        return o_selinux_check_access(scon, tcon, tclass, perm, auditdata);
    }
    static auto real_fn = reinterpret_cast<decltype(o_selinux_check_access)>(
        dlsym(RTLD_DEFAULT, "selinux_check_access"));
    if (real_fn) {
        return real_fn(scon, tcon, tclass, perm, auditdata);
    }
    errno = EACCES;
    return -1;
}

static jstring h_runtime_native_load(JNIEnv *env, jclass type, jstring filename,
                                     jobject loader, jclass caller) {
    jstring error = o_runtime_native_load ? o_runtime_native_load(env, type, filename, loader, caller) : nullptr;
    refresh_late_library_hooks();
    return error;
}

void hook_native_load(zygisk::Api *api, JNIEnv *env) {
    if (!api || !env) return;
    JNINativeMethod method{
        const_cast<char *>("nativeLoad"),
        const_cast<char *>(
            "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;"),
        reinterpret_cast<void *>(h_runtime_native_load),
    };
    api->hookJniNativeMethods(env, "java/lang/Runtime", &method, 1);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    o_runtime_native_load =
        reinterpret_cast<decltype(o_runtime_native_load)>(method.fnPtr);
}

// ---- file-existence hiding ----
static int h_faccessat(int d, const char *p, int m, int f) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    return o_faccessat(d, p, m, f);
}
static int h_access(const char *p, int m) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    return o_access(p, m);
}

static bool is_local_tmp_path(const char *p) {
    return p && (strcmp(p, "/data/local/tmp") == 0 || strcmp(p, "/data/local/tmp/") == 0);
}

// Check if the path ends with "mountinfo"
static bool is_mountinfo_path(const char *path) {
    if (!path) return false;
    size_t len = strlen(path);
    return len >= 9 && strcmp(path + len - 9, "mountinfo") == 0;
}

static std::string resolve_at_path(int dirfd, const char *path) {
    if (!path || path[0] == '\0') return "";
    if (path[0] == '/') return path;
    if (dirfd == AT_FDCWD || dirfd < 0) return path;
    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", dirfd);
    char resolved[PATH_MAX];
    ssize_t len = ::readlink(fd_path, resolved, sizeof(resolved) - 1);
    if (len > 0) {
        resolved[len] = '\0';
        return std::string(resolved) + "/" + path;
    }
    return path;
}

static int h_stat(const char *p, struct stat *s) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    int res = o_stat ? o_stat(p, s) : ::stat(p, s);
    if (res == 0 && s && is_local_tmp_path(p)) {
        s->st_ino = 128;
    }
    return res;
}
static int h_lstat(const char *p, struct stat *s) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    int res = o_lstat ? o_lstat(p, s) : ::lstat(p, s);
    if (res == 0 && s && is_local_tmp_path(p)) {
        s->st_ino = 128;
    }
    return res;
}
static int h_fstatat(int d, const char *p, struct stat *s, int f) {
    std::string full_path;
    const char *target = p;
    if (p && p[0] != '/' && d != AT_FDCWD && d >= 0) {
        full_path = resolve_at_path(d, p);
        if (!full_path.empty()) target = full_path.c_str();
    }
    if (is_blocked(target)) { errno = ENOENT; return -1; }
    int res = o_fstatat ? o_fstatat(d, p, s, f) : ::fstatat(d, p, s, f);
    if (res == 0 && s && is_local_tmp_path(target)) {
        s->st_ino = 128;
    }
    return res;
}

static int h_stat64(const char *p, struct stat64 *s) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    int res = o_stat64 ? o_stat64(p, s) : ::stat64(p, s);
    if (res == 0 && s && is_local_tmp_path(p)) {
        s->st_ino = 128;
    }
    return res;
}
static int h_lstat64(const char *p, struct stat64 *s) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    int res = o_lstat64 ? o_lstat64(p, s) : ::lstat64(p, s);
    if (res == 0 && s && is_local_tmp_path(p)) {
        s->st_ino = 128;
    }
    return res;
}
static int h_fstatat64(int d, const char *p, struct stat64 *s, int f) {
    std::string full_path;
    const char *target = p;
    if (p && p[0] != '/' && d != AT_FDCWD && d >= 0) {
        full_path = resolve_at_path(d, p);
        if (!full_path.empty()) target = full_path.c_str();
    }
    if (is_blocked(target)) { errno = ENOENT; return -1; }
    int res = o_fstatat64 ? o_fstatat64(d, p, s, f) : ::fstatat64(d, p, s, f);
    if (res == 0 && s && is_local_tmp_path(target)) {
        s->st_ino = 128;
    }
    return res;
}

// ---- /proc self-file filtering helpers ----

static bool is_self_proc_file(const char *path, const char *name) {
    if (!path || !name) return false;
    if (strncmp(path, "/proc/self/", 11) == 0 && strcmp(path + 11, name) == 0)
        return true;
    char buf[64];
    snprintf(buf, sizeof buf, "/proc/%d/%s", getpid(), name);
    return strcmp(path, buf) == 0;
}

static bool is_mount_name(const char *name) {
    return strcmp(name, "mounts") == 0 ||
           strcmp(name, "mountinfo") == 0 ||
           strcmp(name, "mountstats") == 0;
}

static bool is_mount_path(const char *path) {
    if (!path) return false;
    if (strcmp(path, "/proc/mounts") == 0) return true;
    if (strncmp(path, "/proc/", 6) != 0) return false;
    const char *owner = path + 6;
    const char *slash = strchr(owner, '/');
    if (!slash || slash == owner) return false;
    bool valid_owner = strncmp(owner, "self/", 5) == 0 ||
                       strncmp(owner, "thread-self/", 12) == 0;
    if (!valid_owner) {
        valid_owner = true;
        for (const char *p = owner; p < slash; ++p) {
            if (*p < '0' || *p > '9') {
                valid_owner = false;
                break;
            }
        }
    }
    return valid_owner && is_mount_name(slash + 1);
}

static std::vector<char> read_all_fd(int fd) {
    std::vector<char> data;
    char buf[8192];
    ssize_t n;
    while ((n = ::read(fd, buf, sizeof buf)) > 0)
        data.insert(data.end(), buf, buf + n);
    return data;
}

// Remove lines that reference blocked root paths (maps, mounts, etc.)
static std::vector<char> filter_blocked_lines(const std::vector<char> &raw,
                                              bool extra_mounts_check) {
    std::vector<char> out;
    out.reserve(raw.size());
    const char *p = raw.data();
    const char *end = p + raw.size();
    unsigned long canonical_jit_inode = 0;
    while (p < end) {
        const char *nl = (const char *)memchr(p, '\n', end - p);
        size_t len = nl ? (size_t)(nl - p + 1) : (size_t)(end - p);
        bool keep = true;
        for (const char *s : kBlockedSubstr)
            if (memmem(p, len, s, strlen(s))) { keep = false; break; }
        if (keep && extra_mounts_check) {
            for (const char *s : kMountsExtra)
                if (memmem(p, len, s, strlen(s))) { keep = false; break; }
        }
        // Also filter lines containing ROM keywords (e.g. lineage framework files in maps)
        if (keep && g_cfg) {
            for (const auto &kw : g_cfg->rom_keywords) {
                if (contains_ci(p, len, kw.data(), kw.size())) { keep = false; break; }
            }
        }
        if (keep) {
            if (memmem(p, len, "jit-cache", 9)) {
                std::string line(p, len);
                unsigned long start = 0, finish = 0, off = 0;
                char perms[8] = {};
                unsigned major = 0, minor = 0;
                unsigned long inode = 0;
                char path[256] = {};
                if (sscanf(line.c_str(), "%lx-%lx %7s %lx %x:%x %lu %255s",
                           &start, &finish, perms, &off, &major, &minor, &inode, path) == 8) {
                    if (canonical_jit_inode == 0 && inode != 0) {
                        canonical_jit_inode = inode;
                    } else if (canonical_jit_inode != 0 && inode != canonical_jit_inode) {
                        char norm_buf[384];
                        int nwritten = snprintf(norm_buf, sizeof(norm_buf),
                            "%lx-%lx %s %08lx %02x:%02x %lu",
                            start, finish, perms, off, major, minor, canonical_jit_inode);
                        while (nwritten < 73) norm_buf[nwritten++] = ' ';
                        nwritten += snprintf(norm_buf + nwritten, sizeof(norm_buf) - nwritten,
                            " %s\n", path);
                        out.insert(out.end(), norm_buf, norm_buf + nwritten);
                        p += len;
                        continue;
                    }
                }
            }
            out.insert(out.end(), p, p + len);
        }
        p += len;
    }
    return out;
}


// Normalize mountinfo lines by removing blocked entries and renumbering peer groups
// (shared:X, master:X, propagate_from:X) into a contiguous gapless sequence to defeat
// DuckDetector's detect_peer_group_gap probe.
static std::vector<char> filter_mountinfo(const std::vector<char> &raw) {
    struct LineSpan {
        size_t start;
        size_t len;
    };
    std::vector<LineSpan> kept;
    const char *p = raw.data();
    const char *end = p + raw.size();

    // Step 1: Collect non-blocked lines
    while (p < end) {
        const char *nl = static_cast<const char *>(memchr(p, '\n', end - p));
        size_t len = nl ? static_cast<size_t>(nl - p + 1) : static_cast<size_t>(end - p);
        bool keep = true;
        for (const char *s : kBlockedSubstr) {
            if (memmem(p, len, s, strlen(s))) { keep = false; break; }
        }
        if (keep) {
            for (const char *s : kMountsExtra) {
                if (memmem(p, len, s, strlen(s))) { keep = false; break; }
            }
        }
        if (keep && g_cfg) {
            for (const auto &kw : g_cfg->rom_keywords) {
                if (contains_ci(p, len, kw.data(), kw.size())) {
                    keep = false;
                    break;
                }
            }
        }
        if (keep) {
            kept.push_back({static_cast<size_t>(p - raw.data()), len});
        }
        p += len;
    }

    // Step 2: Extract all shared:X, master:X, propagate_from:X IDs and collect unique IDs in order
    std::set<unsigned long> unique_shared;
    static const char *const kTags[] = {"shared:", "master:", "propagate_from:"};
    for (const auto &span : kept) {
        const char *line = raw.data() + span.start;
        const char *line_end = line + span.len;
        const char *sep = static_cast<const char *>(memmem(line, span.len, " - ", 3));
        const char *opt_end = sep ? sep : line_end;

        for (const char *tag : kTags) {
            size_t tag_len = strlen(tag);
            const char *cur = line;
            while ((cur = static_cast<const char *>(memmem(cur, opt_end - cur, tag, tag_len))) != nullptr) {
                if (cur == line || cur[-1] == ' ') {
                    const char *val_start = cur + tag_len;
                    char *val_end = nullptr;
                    unsigned long sid = strtoul(val_start, &val_end, 10);
                    if (val_end > val_start && (val_end == opt_end || *val_end == ' ' || *val_end == '\n')) {
                        unique_shared.insert(sid);
                    }
                }
                cur += tag_len;
            }
        }
    }

    // Step 3: Build a gapless remap table: 1, 2, 3, ... N
    std::map<unsigned long, unsigned long> remap;
    if (!unique_shared.empty()) {
        unsigned long next_id = 1;
        for (unsigned long sid : unique_shared) {
            remap[sid] = next_id++;
        }
    }

    // Step 4: Stream rewritten lines into output buffer
    std::vector<char> out;
    out.reserve(raw.size());

    for (const auto &span : kept) {
        const char *line = raw.data() + span.start;
        size_t len = span.len;
        const char *sep = static_cast<const char *>(memmem(line, len, " - ", 3));

        if (!sep || remap.empty()) {
            out.insert(out.end(), line, line + len);
            continue;
        }

        // Rewrite optional fields before " - "
        const char *cur = line;
        static const char *const kTags[] = {"shared:", "master:", "propagate_from:"};
        while (cur < sep) {
            bool handled = false;
            for (const char *tag : kTags) {
                size_t tag_len = strlen(tag);
                if ((cur == line || cur[-1] == ' ') && strncmp(cur, tag, tag_len) == 0) {
                    const char *val_start = cur + tag_len;
                    char *val_end = nullptr;
                    unsigned long id = strtoul(val_start, &val_end, 10);
                    if (val_end > val_start && (val_end == sep || *val_end == ' ')) {
                        auto it = remap.find(id);
                        if (it != remap.end()) {
                            out.insert(out.end(), tag, tag + tag_len);
                            char num_buf[32];
                            int n = snprintf(num_buf, sizeof(num_buf), "%lu", it->second);
                            out.insert(out.end(), num_buf, num_buf + n);
                            cur = val_end;
                            handled = true;
                            break;
                        }
                    }
                }
            }
            if (!handled) {
                out.push_back(*cur);
                cur++;
            }
        }
        // Append remainder of line (" - ...\n")
        out.insert(out.end(), sep, line + len);
    }

    return out;
}

static std::vector<char> filter_maps(const std::vector<char> &raw) {
    return filter_blocked_lines(raw, false);
}

static std::vector<char> filter_smaps(const std::vector<char> &raw) {
    std::vector<char> out;
    out.reserve(raw.size());
    bool keep_block = true;
    bool webview_executable = false;
    unsigned long canonical_jit_inode = 0;
    const char *p = raw.data();
    const char *end = p + raw.size();
    while (p < end) {
        const char *nl = static_cast<const char *>(memchr(p, '\n', end - p));
        const size_t length = nl ? static_cast<size_t>(nl - p + 1)
                                 : static_cast<size_t>(end - p);
        std::string line(p, length);

        unsigned long start = 0;
        unsigned long finish = 0;
        char perms[8] = {};
        if (sscanf(line.c_str(), "%lx-%lx %7s", &start, &finish, perms) == 3) {
            keep_block = true;
            for (const char *blocked : kBlockedSubstr) {
                if (line.find(blocked) != std::string::npos) {
                    keep_block = false;
                    break;
                }
            }
            if (keep_block && g_cfg) {
                for (const auto &keyword : g_cfg->rom_keywords) {
                    if (contains_ci(line.data(), line.size(), keyword.data(), keyword.size())) {
                        keep_block = false;
                        break;
                    }
                }
            }
            webview_executable = strchr(perms, 'x') != nullptr &&
                contains_ci(line.data(), line.size(),
                            "/system/product/app/webview/webview.apk", 39);

            if (keep_block && line.find("jit-cache") != std::string::npos) {
                unsigned long off = 0;
                unsigned major = 0, minor = 0;
                unsigned long inode = 0;
                char path[256] = {};
                if (sscanf(line.c_str(), "%lx-%lx %7s %lx %x:%x %lu %255s",
                           &start, &finish, perms, &off, &major, &minor, &inode, path) == 8) {
                    if (canonical_jit_inode == 0 && inode != 0) {
                        canonical_jit_inode = inode;
                    } else if (canonical_jit_inode != 0 && inode != canonical_jit_inode) {
                        char norm_buf[384];
                        int nwritten = snprintf(norm_buf, sizeof(norm_buf),
                            "%lx-%lx %s %08lx %02x:%02x %lu",
                            start, finish, perms, off, major, minor, canonical_jit_inode);
                        while (nwritten < 73) norm_buf[nwritten++] = ' ';
                        nwritten += snprintf(norm_buf + nwritten, sizeof(norm_buf) - nwritten,
                            " %s\n", path);
                        line = std::string(norm_buf, nwritten);
                    }
                }
            }
        } else if (webview_executable && line.rfind("Anonymous:", 0) == 0) {
            line = "Anonymous:             0 kB\n";
        }
        if (keep_block) out.insert(out.end(), line.begin(), line.end());
        p += length;
    }
    return out;
}

// Zero out TracerPid in /proc/self/status to hide debugger/tracer
static std::vector<char> filter_status(const std::vector<char> &raw) {
    std::vector<char> out;
    out.reserve(raw.size());
    const char *p = raw.data();
    const char *end = p + raw.size();
    while (p < end) {
        const char *nl = (const char *)memchr(p, '\n', end - p);
        size_t len = nl ? (size_t)(nl - p + 1) : (size_t)(end - p);
        if (len >= 10 && memcmp(p, "TracerPid:", 10) == 0) {
            static const char kFake[] = "TracerPid:\t0\n";
            out.insert(out.end(), kFake, kFake + sizeof(kFake) - 1);
        } else {
            out.insert(out.end(), p, p + len);
        }
        p += len;
    }
    return out;
}

static std::vector<char> filter_proc_net_route(const std::vector<char> &raw) {
    std::vector<char> out;
    out.reserve(raw.size());
    const char *p = raw.data();
    const char *end = p + raw.size();
    while (p < end) {
        const char *nl = static_cast<const char *>(memchr(p, '\n', end - p));
        size_t len = nl ? static_cast<size_t>(nl - p + 1) : static_cast<size_t>(end - p);
        size_t flen = 0;
        while (flen < len && p[flen] != '\t' && p[flen] != ' ' && p[flen] != '\n') {
            flen++;
        }
        char ifname[64] = {0};
        if (flen > 0 && flen < sizeof(ifname)) {
            memcpy(ifname, p, flen);
            if (is_vpn_iface(ifname)) {
                p += len;
                continue;
            }
        }
        out.insert(out.end(), p, p + len);
        p += len;
    }
    return out;
}

static std::vector<char> filter_proc_net_dev(const std::vector<char> &raw) {
    std::vector<char> out;
    out.reserve(raw.size());
    const char *p = raw.data();
    const char *end = p + raw.size();
    while (p < end) {
        const char *nl = static_cast<const char *>(memchr(p, '\n', end - p));
        size_t len = nl ? static_cast<size_t>(nl - p + 1) : static_cast<size_t>(end - p);
        const char *colon = static_cast<const char *>(memchr(p, ':', len));
        if (colon) {
            const char *start = p;
            while (start < colon && (*start == ' ' || *start == '\t')) start++;
            size_t nlen = colon - start;
            char ifname[64] = {0};
            if (nlen > 0 && nlen < sizeof(ifname)) {
                memcpy(ifname, start, nlen);
                if (is_vpn_iface(ifname)) {
                    p += len;
                    continue;
                }
            }
        }
        out.insert(out.end(), p, p + len);
        p += len;
    }
    return out;
}

static std::vector<char> filter_proc_net_last_field(const std::vector<char> &raw) {
    std::vector<char> out;
    out.reserve(raw.size());
    const char *p = raw.data();
    const char *end = p + raw.size();
    while (p < end) {
        const char *nl = static_cast<const char *>(memchr(p, '\n', end - p));
        size_t len = nl ? static_cast<size_t>(nl - p + 1) : static_cast<size_t>(end - p);
        size_t tend = len;
        while (tend > 0 && (p[tend - 1] == '\n' || p[tend - 1] == '\r' || p[tend - 1] == ' ' || p[tend - 1] == '\t')) {
            tend--;
        }
        size_t tstart = tend;
        while (tstart > 0 && p[tstart - 1] != ' ' && p[tstart - 1] != '\t') {
            tstart--;
        }
        size_t tlen = tend - tstart;
        char ifname[64] = {0};
        if (tlen > 0 && tlen < sizeof(ifname)) {
            memcpy(ifname, p + tstart, tlen);
            if (is_vpn_iface(ifname)) {
                p += len;
                continue;
            }
        }
        out.insert(out.end(), p, p + len);
        p += len;
    }
    return out;
}

static bool is_proc_net_path(const char *path, const char *name) {
    if (!path || !name) return false;
    if (strncmp(path, "/proc/", 6) != 0) return false;
    const char *net = strstr(path, "/net/");
    if (net) {
        return strcmp(net + 5, name) == 0;
    }
    if (strncmp(path, "/proc/net/", 10) == 0) {
        return strcmp(path + 10, name) == 0;
    }
    return false;
}

enum ProcFilter {
    kFilterMaps,
    kFilterSmaps,
    kFilterStatus,
    kFilterMounts,
    kFilterNetUnix,
    kFilterCgroup,
    kFilterNetRoute,
    kFilterNetIpv6Route,
    kFilterNetDev,
    kFilterNetIfInet6,
};

// Create a memory-backed seekable fd containing `content`.
// Prefers memfd_create (API 23+); falls back to a pipe.
static int make_anon_fd(const std::vector<char> &content) {
#ifdef __NR_memfd_create
    int fd = (int)syscall(__NR_memfd_create, "pf", (unsigned)MFD_CLOEXEC);
    if (fd >= 0) {
        if (!content.empty()) {
            const char *p = content.data();
            size_t rem = content.size();
            while (rem > 0) {
                ssize_t w = ::write(fd, p, rem);
                if (w <= 0) { ::close(fd); return -1; }
                p += w; rem -= w;
            }
            lseek(fd, 0, SEEK_SET);
        }
        return fd;
    }
#endif
    int pfd[2];
    if (pipe2(pfd, O_CLOEXEC) != 0) return -1;
    if (!content.empty()) {
        fcntl(pfd[1], F_SETPIPE_SZ, (int)(content.size() + 4096));
        const char *p = content.data();
        size_t rem = content.size();
        while (rem > 0) {
            ssize_t w = ::write(pfd[1], p, rem);
            if (w <= 0) { ::close(pfd[0]); ::close(pfd[1]); return -1; }
            p += w; rem -= w;
        }
    }
    ::close(pfd[1]);
    return pfd[0];
}

static int open_filtered_proc(const char *path, ProcFilter filter) {
    int real_fd = o_open ? o_open(path, O_RDONLY | O_CLOEXEC) : ::open(path, O_RDONLY | O_CLOEXEC);
    if (real_fd < 0) return real_fd;
    auto raw = read_all_fd(real_fd);
    ::close(real_fd);

    std::vector<char> filtered;
    switch (filter) {
        case kFilterMaps:    filtered = filter_maps(raw); break;
        case kFilterSmaps:   filtered = filter_smaps(raw); break;
        case kFilterStatus:  filtered = filter_status(raw); break;
        case kFilterMounts:
            if (is_mountinfo_path(path)) {
                filtered = filter_mountinfo(raw);
            } else {
                filtered = filter_blocked_lines(raw, true);
            }
            break;
        case kFilterNetUnix:      filtered = filter_blocked_lines(raw, false); break;
        case kFilterCgroup:       filtered = filter_blocked_lines(raw, false); break;
        case kFilterNetRoute:     filtered = filter_proc_net_route(raw); break;
        case kFilterNetIpv6Route: filtered = filter_proc_net_last_field(raw); break;
        case kFilterNetDev:       filtered = filter_proc_net_dev(raw); break;
        case kFilterNetIfInet6:   filtered = filter_proc_net_last_field(raw); break;
    }

    int anon = make_anon_fd(filtered);
    if (anon < 0) return o_open ? o_open(path, O_RDONLY | O_CLOEXEC) : ::open(path, O_RDONLY | O_CLOEXEC);
    return anon;
}

static bool is_stagefright_path(const char *path) {
    return path && strstr(path, "libstagefright.so") != nullptr;
}

static int open_filtered_stagefright(const char *path) {
    int real_fd = o_open ? o_open(path, O_RDONLY | O_CLOEXEC) : ::open(path, O_RDONLY | O_CLOEXEC);
    if (real_fd < 0) return real_fd;
    auto raw = read_all_fd(real_fd);
    ::close(real_fd);
    const char target[] = "threadLoopEv";
    const char repl[]   = "threadLoop__";
    if (raw.size() >= sizeof(target) - 1) {
        for (size_t i = 0; i + sizeof(target) - 1 <= raw.size(); ++i) {
            if (memcmp(raw.data() + i, target, sizeof(target) - 1) == 0) {
                memcpy(raw.data() + i, repl, sizeof(repl) - 1);
            }
        }
    }
    int anon = make_anon_fd(raw);
    if (anon < 0) return o_open ? o_open(path, O_RDONLY | O_CLOEXEC) : ::open(path, O_RDONLY | O_CLOEXEC);
    return anon;
}

static bool is_selinux_policy_path(const char *path) {
    if (!path) return false;
    return strstr(path, "file_contexts") != nullptr ||
           strstr(path, "sepolicy.cil") != nullptr;
}

static int open_filtered_selinux(const char *path) {
    int real_fd = o_open ? o_open(path, O_RDONLY | O_CLOEXEC) : ::open(path, O_RDONLY | O_CLOEXEC);
    if (real_fd < 0) return real_fd;
    auto raw = read_all_fd(real_fd);
    ::close(real_fd);

    std::vector<char> out;
    out.reserve(raw.size());
    const char *p = raw.data();
    const char *end = p + raw.size();
    while (p < end) {
        const char *nl = (const char *)memchr(p, '\n', end - p);
        size_t len = nl ? (size_t)(nl - p + 1) : (size_t)(end - p);
        bool drop = contains_ci(p, len, "lineage", 7);
        if (!drop && g_cfg) {
            for (const auto &kw : g_cfg->rom_keywords) {
                if (contains_ci(p, len, kw.data(), kw.size())) {
                    drop = true;
                    break;
                }
            }
        }
        if (!drop) {
            out.insert(out.end(), p, p + len);
        }
        p += len;
    }

    int anon = make_anon_fd(out);
    if (anon < 0) return o_open ? o_open(path, O_RDONLY | O_CLOEXEC) : ::open(path, O_RDONLY | O_CLOEXEC);
    return anon;
}

// ---- open / openat hooks ----
static int h_open(const char *p, int fl, ...) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    int mode = 0;
    if (fl & O_CREAT) { va_list ap; va_start(ap, fl); mode = va_arg(ap, int); va_end(ap); }
    if ((fl & O_ACCMODE) == O_RDONLY) {
        if (is_stagefright_path(p))         return open_filtered_stagefright(p);
        if (is_selinux_policy_path(p))      return open_filtered_selinux(p);
        if (is_self_proc_file(p, "maps"))   return open_filtered_proc(p, kFilterMaps);
        if (is_self_proc_file(p, "smaps"))  return open_filtered_proc(p, kFilterSmaps);
        if (is_self_proc_file(p, "status")) return open_filtered_proc(p, kFilterStatus);
        if (is_self_proc_file(p, "cgroup")) return open_filtered_proc(p, kFilterCgroup);
        if (is_mount_path(p))               return open_filtered_proc(p, kFilterMounts);
        if (p && strcmp(p, "/proc/net/unix") == 0) return open_filtered_proc(p, kFilterNetUnix);
        if (is_proc_net_path(p, "route"))      return open_filtered_proc(p, kFilterNetRoute);
        if (is_proc_net_path(p, "ipv6_route")) return open_filtered_proc(p, kFilterNetIpv6Route);
        if (is_proc_net_path(p, "dev"))        return open_filtered_proc(p, kFilterNetDev);
        if (is_proc_net_path(p, "if_inet6"))   return open_filtered_proc(p, kFilterNetIfInet6);
    }
    return o_open ? o_open(p, fl, mode) : ::open(p, fl, mode);
}

static int h_open_2(const char *p, int fl) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    if ((fl & O_ACCMODE) == O_RDONLY) {
        if (is_stagefright_path(p))         return open_filtered_stagefright(p);
        if (is_selinux_policy_path(p))      return open_filtered_selinux(p);
        if (is_self_proc_file(p, "maps"))   return open_filtered_proc(p, kFilterMaps);
        if (is_self_proc_file(p, "smaps"))  return open_filtered_proc(p, kFilterSmaps);
        if (is_self_proc_file(p, "status")) return open_filtered_proc(p, kFilterStatus);
        if (is_self_proc_file(p, "cgroup")) return open_filtered_proc(p, kFilterCgroup);
        if (is_mount_path(p))               return open_filtered_proc(p, kFilterMounts);
        if (p && strcmp(p, "/proc/net/unix") == 0) return open_filtered_proc(p, kFilterNetUnix);
        if (is_proc_net_path(p, "route"))      return open_filtered_proc(p, kFilterNetRoute);
        if (is_proc_net_path(p, "ipv6_route")) return open_filtered_proc(p, kFilterNetIpv6Route);
        if (is_proc_net_path(p, "dev"))        return open_filtered_proc(p, kFilterNetDev);
        if (is_proc_net_path(p, "if_inet6"))   return open_filtered_proc(p, kFilterNetIfInet6);
    }
    return o_open_2 ? o_open_2(p, fl) : ::open(p, fl);
}

static int h_openat(int d, const char *p, int fl, ...) {
    std::string full_path;
    const char *target = p;
    if (p && p[0] != '/' && d != AT_FDCWD && d >= 0) {
        full_path = resolve_at_path(d, p);
        if (!full_path.empty()) target = full_path.c_str();
    }
    if (is_blocked(target)) { errno = ENOENT; return -1; }
    int mode = 0;
    if (fl & O_CREAT) { va_list ap; va_start(ap, fl); mode = va_arg(ap, int); va_end(ap); }
    if ((fl & O_ACCMODE) == O_RDONLY) {
        if (is_stagefright_path(target))         return open_filtered_stagefright(target);
        if (is_selinux_policy_path(target))      return open_filtered_selinux(target);
        if (is_self_proc_file(target, "maps"))   return open_filtered_proc(target, kFilterMaps);
        if (is_self_proc_file(target, "smaps"))  return open_filtered_proc(target, kFilterSmaps);
        if (is_self_proc_file(target, "status")) return open_filtered_proc(target, kFilterStatus);
        if (is_self_proc_file(target, "cgroup")) return open_filtered_proc(target, kFilterCgroup);
        if (is_mount_path(target))               return open_filtered_proc(target, kFilterMounts);
        if (target && strcmp(target, "/proc/net/unix") == 0) return open_filtered_proc(target, kFilterNetUnix);
        if (is_proc_net_path(target, "route"))      return open_filtered_proc(target, kFilterNetRoute);
        if (is_proc_net_path(target, "ipv6_route")) return open_filtered_proc(target, kFilterNetIpv6Route);
        if (is_proc_net_path(target, "dev"))        return open_filtered_proc(target, kFilterNetDev);
        if (is_proc_net_path(target, "if_inet6"))   return open_filtered_proc(target, kFilterNetIfInet6);
    }
    return o_openat ? o_openat(d, p, fl, mode) : ::openat(d, p, fl, mode);
}

static FILE *h_fopen(const char *p, const char *mode) {
    if (is_blocked(p)) { errno = ENOENT; return nullptr; }
    if (mode && mode[0] == 'r') {
        int fd = -1;
        if (is_stagefright_path(p))                   fd = open_filtered_stagefright(p);
        else if (is_selinux_policy_path(p))           fd = open_filtered_selinux(p);
        else if (is_self_proc_file(p, "maps"))        fd = open_filtered_proc(p, kFilterMaps);
        else if (is_self_proc_file(p, "smaps"))       fd = open_filtered_proc(p, kFilterSmaps);
        else if (is_self_proc_file(p, "status"))      fd = open_filtered_proc(p, kFilterStatus);
        else if (is_self_proc_file(p, "cgroup"))      fd = open_filtered_proc(p, kFilterCgroup);
        else if (is_mount_path(p))                    fd = open_filtered_proc(p, kFilterMounts);
        else if (p && strcmp(p, "/proc/net/unix") == 0) fd = open_filtered_proc(p, kFilterNetUnix);
        else if (is_proc_net_path(p, "route"))        fd = open_filtered_proc(p, kFilterNetRoute);
        else if (is_proc_net_path(p, "ipv6_route"))   fd = open_filtered_proc(p, kFilterNetIpv6Route);
        else if (is_proc_net_path(p, "dev"))          fd = open_filtered_proc(p, kFilterNetDev);
        else if (is_proc_net_path(p, "if_inet6"))      fd = open_filtered_proc(p, kFilterNetIfInet6);
        else                                          return o_fopen(p, mode);
        if (fd < 0) return nullptr;
        FILE *stream = fdopen(fd, mode);
        if (!stream) close(fd);
        return stream;
    }
    return o_fopen(p, mode);
}

// ---- read hook — rewrites getprop pipe output ----
// ---- read hook — rewrites getprop pipe output ----
static void rewrite_getprop_chunk(char *buf, ssize_t len) {
    if (len <= 0 || !buf) return;

    if (len == 4 && memcmp(buf, "adb\n", 4) == 0) {
        memcpy(buf, "mtp\n", 4);
        return;
    }
    if (len == 7 && memcmp(buf, "orange\n", 7) == 0) {
        memcpy(buf, "green\n\0", 7);
        return;
    }
    if (len == 10 && memcmp(buf, "userdebug\n", 10) == 0) {
        memcpy(buf, "user\n\0\0\0\0\0", 10);
        return;
    }
    if (len == 9 && memcmp(buf, "unlocked\n", 9) == 0) {
        memcpy(buf, "locked\n\0\0", 9);
        return;
    }

    struct PropRepl {
        const char *target;
        size_t target_len;
        const char *repl;
        size_t repl_len;
    };

    static const PropRepl kChunkReplacements[] = {
        {"[ro.boot.verifiedbootstate]: [orange]", 37, "[ro.boot.verifiedbootstate]: [green ]", 37},
        {"[ro.boot.flash.locked]: [0]",           27, "[ro.boot.flash.locked]: [1]",           27},
        {"[ro.boot.vbmeta.device_state]: [unlocked]", 41, "[ro.boot.vbmeta.device_state]: [locked  ]", 41},
        {"[ro.boot.selinux]: [permissive]",       31, "[ro.boot.selinux]: [enforcing ]",       31},
        {"[ro.secureboot.lockstate]: [unlocked]",  39, "[ro.secureboot.lockstate]: [locked  ]",  39},
        {"[vendor.boot.verifiedbootstate]: [orange]", 44, "[vendor.boot.verifiedbootstate]: [green ]", 44},
        {"[vendor.boot.vbmeta.device_state]: [unlocked]", 48, "[vendor.boot.vbmeta.device_state]: [locked  ]", 48},
        {"[ro.is_ever_orange]: [1]",              25, "[ro.is_ever_orange]: [0]",              25},
        {"[ro.debuggable]: [1]",                  20, "[ro.debuggable]: [0]",                  20},
        {"[ro.force.debuggable]: [1]",            26, "[ro.force.debuggable]: [0]",            26},
        {"[ro.build.type]: [userdebug]",          28, "[ro.build.type]: [user     ]",          28},
        {"[ro.product.build.type]: [userdebug]",  36, "[ro.product.build.type]: [user     ]",  36},
        {"[ro.system.build.type]: [userdebug]",   35, "[ro.system.build.type]: [user     ]",   35},
        {"[ro.system_ext.build.type]: [userdebug]", 39, "[ro.system_ext.build.type]: [user     ]", 39},
        {"[ro.vendor.build.type]: [userdebug]",   35, "[ro.vendor.build.type]: [user     ]",   35},
        {"[ro.vendor_dlkm.build.type]: [userdebug]", 40, "[ro.vendor_dlkm.build.type]: [user     ]", 40},
        {"[ro.bootimage.build.type]: [userdebug]", 38, "[ro.bootimage.build.type]: [user     ]", 38},
        {"[ro.odm.build.type]: [userdebug]",      32, "[ro.odm.build.type]: [user     ]",      32},
        {"[persist.sys.usb.config]: [adb]",       31, "[persist.sys.usb.config]: [mtp]",       31},
        {"[sys.usb.config]: [adb]",               23, "[sys.usb.config]: [mtp]",               23},
        {"[sys.usb.state]: [adb]",                22, "[sys.usb.state]: [mtp]",                22},
        {"[service.adb.root]: [1]",               23, "[service.adb.root]: [0]",               23},
        {"[sys.oem_unlock_allowed]: [1]",         29, "[sys.oem_unlock_allowed]: [0]",         29},
        {"[init.svc.adbd]: [running]",            25, "[init.svc.adbd]: [stopped]",            25},
    };

    for (const auto &entry : kChunkReplacements) {
        char *p = buf;
        while ((p = (char *)memmem(p, len - (p - buf), entry.target, entry.target_len)) != nullptr) {
            memcpy(p, entry.repl, entry.repl_len);
            p += entry.repl_len;
        }
    }

    static const char kBridgeTarget[] = "[ro.dalvik.vm.native.bridge]: [";
    char *p = buf;
    while ((p = (char *)memmem(p, len - (p - buf), kBridgeTarget, sizeof(kBridgeTarget) - 1)) != nullptr) {
        char *closing = (char *)memchr(p, ']', len - (p - buf));
        if (closing) {
            for (char *c = p; c <= closing; ++c) *c = ' ';
        }
        p += sizeof(kBridgeTarget) - 1;
    }

    static const char kFlavorTarget[] = "[ro.build.flavor]: [";
    p = buf;
    while ((p = (char *)memmem(p, len - (p - buf), kFlavorTarget, sizeof(kFlavorTarget) - 1)) != nullptr) {
        char *closing = (char *)memchr(p, ']', len - (p - buf));
        if (closing) {
            char *ud = (char *)memmem(p, closing - p, "userdebug", 9);
            if (ud) memcpy(ud, "user     ", 9);
            char *lin = (char *)memmem(p, closing - p, "lineage", 7);
            if (lin) memcpy(lin, "android", 7);
        }
        p += sizeof(kFlavorTarget) - 1;
    }
}

static ssize_t h_read(int fd, void *buf, size_t count) {
    ssize_t ret = o_read ? o_read(fd, buf, count) : ::read(fd, buf, count);
    if (ret > 0 && buf) {
        rewrite_getprop_chunk(static_cast<char *>(buf), ret);
    }
    return ret;
}

// ---- readlink hooks — also filter symlink targets ----
// Catches /proc/self/fd/N → /data/adb/magisk/... symlinks
static ssize_t h_readlink(const char *p, char *b, size_t n) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    ssize_t ret = o_readlink(p, b, n);
    if (ret > 0) {
        for (const char *s : kBlockedSubstr)
            if (memmem(b, (size_t)ret, s, strlen(s))) { errno = ENOENT; return -1; }
        if (g_cfg) for (const auto &kw : g_cfg->rom_keywords)
            if (contains_ci(b, static_cast<size_t>(ret), kw.data(), kw.size())) {
                errno = ENOENT; return -1;
            }
    }
    return ret;
}
static ssize_t h_readlinkat(int d, const char *p, char *b, size_t n) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    ssize_t ret = o_readlinkat(d, p, b, n);
    if (ret > 0) {
        for (const char *s : kBlockedSubstr)
            if (memmem(b, (size_t)ret, s, strlen(s))) { errno = ENOENT; return -1; }
        if (g_cfg) for (const auto &kw : g_cfg->rom_keywords)
            if (contains_ci(b, static_cast<size_t>(ret), kw.data(), kw.size())) {
                errno = ENOENT; return -1;
            }
    }
    return ret;
}

// ---- directory listing hiding ----
static struct dirent *h_readdir(DIR *dir) {
    struct dirent *entry;
    while ((entry = o_readdir(dir)) != nullptr) {
        if (entry->d_name[0] && (is_blocked(entry->d_name) || basename_is_su(entry->d_name) || is_vpn_iface(entry->d_name)))
            continue;
        break;
    }
    return entry;
}

static DIR *h_opendir(const char *p) {
    if (is_blocked(p)) { errno = ENOENT; return nullptr; }
    return o_opendir(p);
}

static struct dirent64 *h_readdir64(DIR *dir) {
    struct dirent64 *entry;
    while ((entry = o_readdir64 ? o_readdir64(dir) : (struct dirent64 *)::readdir64(dir)) != nullptr) {
        if (entry->d_name[0] && (is_blocked(entry->d_name) || basename_is_su(entry->d_name) || is_vpn_iface(entry->d_name)))
            continue;
        break;
    }
    return entry;
}

// ---- exec filtering ----
static bool has_blocked_exec(const char *pathname, char *const argv[]) {
    if (pathname && is_blocked(pathname)) return true;
    if (argv) {
        for (int i = 0; argv[i] != nullptr; ++i) {
            if (is_blocked(argv[i])) return true;
        }
    }
    return false;
}

static int h_execve(const char *pathname, char *const argv[], char *const envp[]) {
    if (has_blocked_exec(pathname, argv)) {
        static char false_path[] = "/system/bin/false";
        static char *const false_argv[] = {false_path, nullptr};
        return o_execve ? o_execve(false_path, false_argv, envp) : ::execve(false_path, false_argv, envp);
    }
    return o_execve ? o_execve(pathname, argv, envp) : ::execve(pathname, argv, envp);
}

static int h_execvp(const char *file, char *const argv[]) {
    if (has_blocked_exec(file, argv)) {
        static char false_path[] = "/system/bin/false";
        static char *const false_argv[] = {false_path, nullptr};
        return o_execvp ? o_execvp(false_path, false_argv) : ::execvp(false_path, false_argv);
    }
    return o_execvp ? o_execvp(file, argv) : ::execvp(file, argv);
}

static int h_execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (has_blocked_exec(file, argv)) {
        static char false_path[] = "/system/bin/false";
        static char *const false_argv[] = {false_path, nullptr};
        return o_execvpe ? o_execvpe(false_path, false_argv, envp) : ::execvpe(false_path, false_argv, envp);
    }
    return o_execvpe ? o_execvpe(file, argv, envp) : ::execvpe(file, argv, envp);
}

// ---- getenv hook — hide LD_PRELOAD / LD_LIBRARY_PATH injections ----
// Some apps call getenv("LD_PRELOAD") to detect injected libraries.
// We return nullptr for loader env vars and filter results containing root paths.
static char *h_getenv(const char *name) {
    if (!name) return o_getenv(name);
    // Block LD_PRELOAD so apps can't detect our injected library.
    // LD_LIBRARY_PATH is NOT blocked — apps legitimately read it for native lib loading.
    if (strcmp(name, "LD_PRELOAD") == 0) return nullptr;
    char *val = o_getenv(name);
    if (val && is_blocked(val)) return nullptr;
    return val;
}

// ---- hardcoded boot-state props ----
static const struct { const char *name; const char *value; } kBootProps[] = {
    {"ro.boot.verifiedbootstate",      "green"},
    {"ro.boot.flash.locked",           "1"},
    {"ro.boot.vbmeta.device_state",    "locked"},
    {"sys.oem_unlock_allowed",         "0"},
    {"ro.boot.warranty_bit",           "0"},
    {"ro.warranty_bit",                "0"},
    {"ro.boot.selinux",                "enforcing"},
    {"ro.secureboot.lockstate",        "locked"},
    {"vendor.boot.verifiedbootstate",  "green"},
    {"vendor.boot.vbmeta.device_state","locked"},
    {"ro.is_ever_orange",              "0"},
    {"ro.debuggable",                  "0"},
    {"ro.force.debuggable",            "0"},
    {"ro.secure",                      "1"},
    {"ro.adb.secure",                  "1"},
    {"ro.build.type",                  "user"},
    {"ro.build.tags",                  "release-keys"},
    {"ro.product.build.type",          "user"},
    {"ro.system.build.type",           "user"},
    {"ro.system_ext.build.type",       "user"},
    {"ro.vendor.build.type",           "user"},
    {"ro.vendor_dlkm.build.type",      "user"},
    {"ro.bootimage.build.type",        "user"},
    {"ro.boot.veritymode",             "enforcing"},
    {"ro.boot.veritymode.managed",     "yes"},
    {"ro.vendor.boot.warranty_bit",    "0"},
    {"ro.vendor.warranty_bit",         "0"},
    {"ro.boot.realmebootstate",        "green"},
    {"ro.boot.realme.lockstate",       "1"},
    // The read hook below rewrites the same value in getprop's pipe output, so
    // reflection, native libc, and the subprocess snapshot remain consistent.
    {"persist.sys.usb.config",         "mtp"},
    // Hide USB debugging state (single-source checks only — no divergence risk)
    {"init.svc.adbd",                  "stopped"},
    {"sys.usb.state",                  "mtp"},
    {"sys.usb.controller",             "none"},
    {"service.adb.tcp.port",           "0"},
};

// Override OEM- or release-specific properties only when that property exists.
// This avoids inventing Samsung and legacy partition flags on unrelated devices.
static const struct { const char *name; const char *value; } kConditionalBootProps[] = {
    {"ro.boot.secureboot",                     "1"},
    {"ro.boot.knox.state",                     "NORMAL"},
    {"ro.boot.vbmeta.invalidate_on_error",     "yes"},
    {"ro.oem_unlock_supported",                "0"},
    {"partition.system.verified",              "1"},
    {"partition.vendor.verified",              "1"},
    {"partition.product.verified",             "1"},
    {"partition.system_ext.verified",          "1"},
    {"partition.odm.verified",                 "1"},
    {"ro.build.selinux",                       "1"},
    {"ro.crypto.state",                        "encrypted"},
    {"ro.allow.mock.location",                 "0"},
    {"persist.sys.development_settings_enabled","0"},
    {"service.adb.root",                       "0"},
};

static const char *const kDebugReplaceProps[] = {
    "ro.build.flavor",
    "ro.build.display.id",
};

static const struct { const char *name; const char *spoof; } kRecoveryProps[] = {
    {"ro.bootmode",          "unknown"},
    {"ro.boot.bootmode",     "unknown"},
    {"ro.boot.mode",         "unknown"},
    {"vendor.boot.bootmode", "unknown"},
    {"vendor.boot.mode",     "unknown"},
};

// Props that should appear absent (return empty string / not found)
// These props are suspicious when present on a "clean" device
static const char *const kDeletedProps[] = {
    "ro.boot.verifiedbooterror",
    "ro.boot.verifyerrorpart",
    "init.svc.magisk_daemon",
    "init.svc.magisk_service",
    "ro.magisk.hide",
    "ro.dalvik.vm.native.bridge",
};

// Return the pif.conf "ID" value for props that should show the device build ID
// (e.g. ro.build.display.id — native callers bypass our JNI Build.DISPLAY spoof).
static const char *find_display_override(const char *name) {
    if (!g_cfg) return nullptr;
    if (strcmp(name, "ro.build.display.id") != 0) return nullptr;
    static thread_local char s_disp_buf[96];
    // Prefer DISPLAY key; fall back to ID (same value on stock Pixel user builds)
    static const char *const kDispKeys[] = {"DISPLAY", "ID"};
    for (const char *key : kDispKeys) {
        auto it = g_cfg->gms_build.find(key);
        if (it != g_cfg->gms_build.end() && !it->second.empty()) {
            snprintf(s_disp_buf, sizeof s_disp_buf, "%s", it->second.c_str());
            return s_disp_buf;
        }
    }
    return nullptr;
}

static const char *find_boot_prop(const char *name) {
    for (const auto &bp : kBootProps)
        if (strcmp(name, bp.name) == 0) return bp.value;
    // Keep all *.api_level properties consistent with DEVICE_INITIAL_SDK_INT.
    if (str_ends_with(name, "api_level") && g_cfg) {
        auto it = g_cfg->gms_build.find("DEVICE_INITIAL_SDK_INT");
        if (it != g_cfg->gms_build.end() && !it->second.empty()) {
            static thread_local char s_sdk_buf[8];
            snprintf(s_sdk_buf, sizeof s_sdk_buf, "%s", it->second.c_str());
            return s_sdk_buf;
        }
    }
    return nullptr;
}
static const char *find_conditional_boot_prop(const char *name) {
    for (const auto &bp : kConditionalBootProps)
        if (strcmp(name, bp.name) == 0) return bp.value;
    return nullptr;
}
static const char *find_recovery_prop(const char *name) {
    for (const auto &rp : kRecoveryProps)
        if (strcmp(name, rp.name) == 0) return rp.spoof;
    return nullptr;
}
static bool is_debug_replace_prop(const char *name) {
    for (const char *p : kDebugReplaceProps)
        if (strcmp(name, p) == 0) return true;
    return false;
}

static const char *const kRomDeletedProps[] = {
    // Exact property signatures in Duck Detector's current ROM catalog.
    "ro.lineage.build.version", "ro.lineage.build.date", "ro.lineage.build.date.utc",
    "ro.lineage.releasetype",   "ro.lineage.device",     "ro.lineage.version",
    "ro.lineageos.version",     "ro.cm.version",          "ro.cm.build.date.utc",
    "ro.modversion",            "ro.lineage.gapps_version",
    "ro.resurrection.version",  "ro.pa.version",          "ro.aospa.version",
    "ro.crdroid.version",       "ro.pixelexperience.version",
    "ro.evolution.version",     "ro.havoc.version",
};

// Props whose VALUE is checked against ROM keywords and suppressed if it matches.
// Used for props that carry the ROM name in their value rather than their key.
static const char *const kRomValueCheckProps[] = {
    "ro.build.flavor",
    "ro.build.display.id",
};

static bool is_rom_value_check_prop(const char *name) {
    for (const char *p : kRomValueCheckProps)
        if (strcmp(name, p) == 0) return true;
    return false;
}

// Returns true if value contains a user-configured ROM keyword.
static bool value_has_rom_keyword(const char *value) {
    if (!value || !g_cfg) return false;
    for (const auto &kw : g_cfg->rom_keywords)
        if (contains_ci(value, kw)) return true;
    return false;
}

static bool is_deleted_prop(const char *name) {
    if (!name) return false;
    if (strncmp(name, "net.vpn.", 8) == 0) return true;
    if (strcmp(name, "init.svc.openvpn") == 0 ||
        strcmp(name, "init.svc.wireguard") == 0 ||
        strcmp(name, "init.svc.strongswan") == 0 ||
        strcmp(name, "init.svc.xl2tpd") == 0) return true;
    for (const char *p : kDeletedProps)
        if (strcmp(name, p) == 0) return true;
    if (!g_cfg || g_cfg->rom_keywords.empty()) return false;
    for (const char *p : kRomDeletedProps)
        if (strcmp(name, p) == 0) return true;
    // Dynamic: any prop whose NAME contains a ROM keyword is suppressed
    for (const auto &kw : g_cfg->rom_keywords)
        if (contains_ci(name, kw)) return true;
    return false;
}

static int normalize_build_variant(char *buf, int len) {
    char *pos = strstr(buf, "userdebug");
    if (pos) {
        int tail = len - (int)(pos - buf) - 9;
        memmove(pos + 4, pos + 9, tail + 1);
        memcpy(pos, "user", 4);
        return len - 5;
    }
    if (strcmp(buf, "eng") == 0 || strstr(buf, "-eng") || strstr(buf, "_eng")) {
        memcpy(buf, "user", 5);
        return 4;
    }
    return len;
}

// ---- property hooks (classic API) ----
static int h_prop_get(const char *name, char *value) {
    if (name) {
        // Suppress "deleted" suspicious props
        if (is_deleted_prop(name)) { value[0] = '\0'; return 0; }

        // Spoof display ID from pif.conf before any other check
        const char *dp = find_display_override(name);
        if (dp) {
            size_t n = strlen(dp);
            if (n > 91) n = 91;
            memcpy(value, dp, n);
            value[n] = '\0';
            return (int)n;
        }

        const char *bp = find_boot_prop(name);
        if (bp) {
            size_t n = strlen(bp);
            if (n > 91) n = 91;
            memcpy(value, bp, n);
            value[n] = '\0';
            return (int)n;
        }
        const char *conditional = find_conditional_boot_prop(name);
        if (conditional) {
            int original_len = o_prop_get(name, value);
            if (original_len <= 0) return original_len;
            size_t n = strlen(conditional);
            if (n > 91) n = 91;
            memcpy(value, conditional, n);
            value[n] = '\0';
            return (int)n;
        }
        const char *rp = find_recovery_prop(name);
        if (rp) {
            int len = o_prop_get(name, value);
            if (len > 0 && strstr(value, "recovery")) {
                size_t n = strlen(rp);
                memcpy(value, rp, n);
                value[n] = '\0';
                return (int)n;
            }
            return len;
        }
        if (is_debug_replace_prop(name)) {
            int len = o_prop_get(name, value);
            if (len > 0) return normalize_build_variant(value, len);
            return len;
        }
        // Suppress props whose value exposes a configured ROM keyword.
        if (is_rom_value_check_prop(name)) {
            int len = o_prop_get(name, value);
            if (len > 0 && value_has_rom_keyword(value)) {
                value[0] = '\0';
                return 0;
            }
            return len;
        }
        if (g_cfg) {
            auto it = g_cfg->props.find(name);
            if (it != g_cfg->props.end()) {
                size_t n = it->second.copy(value, 91);
                value[n] = '\0';
                return (int)n;
            }
        }
    }
    return o_prop_get(name, value);
}

// ---- property hooks (modern callback API) ----
struct CbCtx {
    void (*user_cb)(void *, const char *, const char *, uint32_t);
    void *user_cookie;
};
static thread_local char tl_cb_buf[256];
static void cb_trampoline(void *cookie, const char *name, const char *value, uint32_t serial) {
    auto *ctx = static_cast<CbCtx *>(cookie);
    if (name) {
        if (is_deleted_prop(name)) { value = ""; }
        else {
            const char *dp = find_display_override(name);
            if (dp) { value = dp; }
            else {
                const char *bp = find_boot_prop(name);
                if (bp) { value = bp; }
                else {
                    const char *conditional = find_conditional_boot_prop(name);
                    if (conditional) { value = conditional; }
                    else {
                        const char *rp = find_recovery_prop(name);
                        if (rp && value && strstr(value, "recovery")) {
                            value = rp;
                        } else if (is_rom_value_check_prop(name) &&
                                   value_has_rom_keyword(value)) {
                            value = "";
                        } else if (is_debug_replace_prop(name) && value &&
                                   (strstr(value, "userdebug") ||
                                    strcmp(value, "eng") == 0 ||
                                    strstr(value, "-eng") || strstr(value, "_eng"))) {
                            size_t n = strlen(value);
                            if (n < sizeof(tl_cb_buf)) {
                                memcpy(tl_cb_buf, value, n + 1);
                                normalize_build_variant(tl_cb_buf, (int)n);
                                value = tl_cb_buf;
                            }
                        } else if (g_cfg) {
                            auto it = g_cfg->props.find(name);
                            if (it != g_cfg->props.end()) value = it->second.c_str();
                        }
                    }
                }
            }
        }
    }
    ctx->user_cb(ctx->user_cookie, name, value, serial);
}
static void h_prop_read_cb(const void *pi,
                           void (*cb)(void *, const char *, const char *, uint32_t),
                           void *cookie) {
    CbCtx ctx{cb, cookie};
    o_prop_read_cb(pi, cb_trampoline, &ctx);
}

// ---- VPN concealment hooks ----
static thread_local bool s_in_getifaddrs = false;

static int h_getifaddrs(struct ifaddrs **ifap) {
    if (!o_getifaddrs) {
        auto real_fn = reinterpret_cast<int (*)(struct ifaddrs **)>(
            dlsym(RTLD_DEFAULT, "getifaddrs"));
        if (!real_fn) {
            errno = EFAULT;
            return -1;
        }
        o_getifaddrs = real_fn;
    }

    s_in_getifaddrs = true;
    int rc = o_getifaddrs(ifap);
    s_in_getifaddrs = false;

    if (rc != 0 || !ifap || !*ifap) return rc;

    struct ifaddrs **curr = ifap;
    while (*curr) {
        struct ifaddrs *entry = *curr;
        if (entry->ifa_name && is_vpn_iface(entry->ifa_name)) {
            *curr = entry->ifa_next;
        } else {
            curr = &(entry->ifa_next);
        }
    }
    return rc;
}

static int h_ioctl(int fd, unsigned long req, ...) {
    va_list ap;
    va_start(ap, req);
    void *arg = va_arg(ap, void *);
    va_end(ap);

    if (s_in_getifaddrs) {
        return o_ioctl ? o_ioctl(fd, req, arg) : ::ioctl(fd, req, arg);
    }

    if (req == SIOCGIFCONF && arg != nullptr) {
        int ret = o_ioctl ? o_ioctl(fd, req, arg) : ::ioctl(fd, req, arg);
        if (ret == 0) {
            auto *ifc = static_cast<struct ifconf *>(arg);
            if (ifc->ifc_req && ifc->ifc_len > 0) {
                int total_entries = ifc->ifc_len / static_cast<int>(sizeof(struct ifreq));
                int kept = 0;
                for (int i = 0; i < total_entries; ++i) {
                    struct ifreq &entry = ifc->ifc_req[i];
                    char name_buf[IFNAMSIZ + 1] = {0};
                    memcpy(name_buf, entry.ifr_name, IFNAMSIZ);
                    if (is_vpn_iface(name_buf)) {
                        continue;
                    }
                    if (kept != i) {
                        ifc->ifc_req[kept] = entry;
                    }
                    kept++;
                }
                if (kept < total_entries) {
                    memset(&ifc->ifc_req[kept], 0, (total_entries - kept) * sizeof(struct ifreq));
                }
                ifc->ifc_len = kept * static_cast<int>(sizeof(struct ifreq));
            }
        }
        return ret;
    }

    if (req == SIOCGIFNAME && arg != nullptr) {
        int ret = o_ioctl ? o_ioctl(fd, req, arg) : ::ioctl(fd, req, arg);
        if (ret == 0) {
            auto *ifr = static_cast<struct ifreq *>(arg);
            char name_buf[IFNAMSIZ + 1] = {0};
            memcpy(name_buf, ifr->ifr_name, IFNAMSIZ);
            if (is_vpn_iface(name_buf)) {
                errno = ENODEV;
                return -1;
            }
        }
        return ret;
    }

    if (arg != nullptr && (req >= 0x8910 && req <= 0x8970)) {
        auto *ifr = static_cast<const struct ifreq *>(arg);
        char name_buf[IFNAMSIZ + 1] = {0};
        memcpy(name_buf, ifr->ifr_name, IFNAMSIZ);
        if (is_vpn_iface(name_buf)) {
            errno = ENODEV;
            return -1;
        }
    }

    return o_ioctl ? o_ioctl(fd, req, arg) : ::ioctl(fd, req, arg);
}

#ifndef SO_BINDTOIFINDEX
#define SO_BINDTOIFINDEX 62
#endif

static int h_setsockopt(int fd, int level, int optname, const void *optval, socklen_t optlen) {
    if (level == SOL_SOCKET && optval != nullptr) {
        if (optname == SO_BINDTODEVICE && optlen > 0) {
            char name_buf[IFNAMSIZ + 1] = {0};
            size_t copy_len = optlen < IFNAMSIZ ? optlen : IFNAMSIZ;
            memcpy(name_buf, optval, copy_len);
            if (is_vpn_iface(name_buf)) {
                errno = ENODEV;
                return -1;
            }
        } else if (optname == SO_BINDTOIFINDEX && optlen >= sizeof(int)) {
            int ifindex = *static_cast<const int *>(optval);
            if (ifindex > 0) {
                char name_buf[IFNAMSIZ] = {0};
                if (if_indextoname(static_cast<unsigned int>(ifindex), name_buf)) {
                    if (is_vpn_iface(name_buf)) {
                        errno = ENODEV;
                        return -1;
                    }
                }
            }
        }
    }
    return o_setsockopt ? o_setsockopt(fd, level, optname, optval, optlen)
                        : ::setsockopt(fd, level, optname, optval, optlen);
}

// ---- hook table ----
struct HookSpec { const char *sym; void *hook; void **orig; };

static const HookSpec kHooks[] = {
    {"faccessat",  (void *)h_faccessat,  (void **)&o_faccessat},
    {"access",     (void *)h_access,     (void **)&o_access},
    {"stat",       (void *)h_stat,       (void **)&o_stat},
    {"lstat",      (void *)h_lstat,      (void **)&o_lstat},
    {"fstatat",    (void *)h_fstatat,    (void **)&o_fstatat},
    {"stat64",     (void *)h_stat64,     (void **)&o_stat64},
    {"lstat64",    (void *)h_lstat64,    (void **)&o_lstat64},
    {"fstatat64",  (void *)h_fstatat64,  (void **)&o_fstatat64},
    {"open",       (void *)h_open,       (void **)&o_open},
    {"__open_2",   (void *)h_open_2,     (void **)&o_open_2},
    {"openat",     (void *)h_openat,     (void **)&o_openat},
    {"fopen",      (void *)h_fopen,      (void **)&o_fopen},
    {"read",       (void *)h_read,       (void **)&o_read},
    {"readlink",   (void *)h_readlink,   (void **)&o_readlink},
    {"readlinkat", (void *)h_readlinkat, (void **)&o_readlinkat},
    {"opendir",    (void *)h_opendir,    (void **)&o_opendir},
    {"readdir",    (void *)h_readdir,    (void **)&o_readdir},
    {"readdir64",  (void *)h_readdir64,  (void **)&o_readdir64},
    {"execve",     (void *)h_execve,     (void **)&o_execve},
    {"execvp",     (void *)h_execvp,     (void **)&o_execvp},
    {"execvpe",    (void *)h_execvpe,    (void **)&o_execvpe},
    {"getenv",     (void *)h_getenv,     (void **)&o_getenv},
    {"dlopen",     (void *)h_dlopen,     (void **)&o_dlopen},
    {"android_dlopen_ext", (void *)h_android_dlopen_ext,
                            (void **)&o_android_dlopen_ext},
    {"dlsym",      (void *)h_dlsym,      (void **)&o_dlsym},
    {"selinux_check_access", (void *)h_selinux_check_access, (void **)&o_selinux_check_access},
    {"__system_property_get",           (void *)h_prop_get,     (void **)&o_prop_get},
    {"__system_property_read_callback", (void *)h_prop_read_cb, (void **)&o_prop_read_cb},
    {"getifaddrs",  (void *)h_getifaddrs,  (void **)&o_getifaddrs},
    {"ioctl",       (void *)h_ioctl,       (void **)&o_ioctl},
    {"setsockopt",  (void *)h_setsockopt,  (void **)&o_setsockopt},
};

static const HookSpec kPropsHooks[] = {
    {"__system_property_get",           (void *)h_prop_get,     (void **)&o_prop_get},
    {"__system_property_read_callback", (void *)h_prop_read_cb, (void **)&o_prop_read_cb},
};

static size_t mapped_elf_size(const lsplt::MapInfo &map) {
    if (!(map.perms & PROT_READ) || map.end <= map.start ||
        map.end - map.start < sizeof(ElfW(Ehdr))) return 0;
    const auto *header = reinterpret_cast<const ElfW(Ehdr) *>(map.start);
    if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0 ||
        header->e_phentsize != sizeof(ElfW(Phdr)) || header->e_phnum == 0) return 0;
    const size_t mapped_size = map.end - map.start;
    const size_t table_size = static_cast<size_t>(header->e_phnum) * sizeof(ElfW(Phdr));
    if (header->e_phoff > mapped_size || table_size > mapped_size - header->e_phoff) return 0;

    const auto *program_headers = reinterpret_cast<const ElfW(Phdr) *>(
            map.start + header->e_phoff);
    size_t file_size = 0;
    for (size_t i = 0; i < header->e_phnum; ++i) {
        if (program_headers[i].p_type != PT_LOAD) continue;
        if (program_headers[i].p_filesz > SIZE_MAX - program_headers[i].p_offset) return 0;
        const size_t end = program_headers[i].p_offset + program_headers[i].p_filesz;
        if (end > file_size) file_size = end;
    }
    return file_size;
}

static void install_late_library_hooks() {
    if (g_profile != HookProfile::Full) return;
    static std::mutex late_hook_mutex;
    static std::set<std::tuple<dev_t, ino_t, uintptr_t, std::string>> seen;
    std::lock_guard<std::mutex> lock(late_hook_mutex);
    bool registered = false;
    for (const auto &map : lsplt::MapInfo::Scan()) {
        if (!map.is_private || map.inode == 0 || map.path.find("/data/app/") == std::string::npos ||
            map.path.find(".apk") == std::string::npos) continue;
        const size_t elf_size = mapped_elf_size(map);
        if (elf_size == 0) continue;
        for (const auto &hook : kHooks) {
            if (!seen.insert({map.dev, map.inode, map.offset, hook.sym}).second) continue;
            registered |= lsplt::RegisterHook(
                    map.dev, map.inode, map.offset, elf_size,
                    hook.sym, hook.hook, hook.orig);
        }
    }
    if (registered) lsplt::CommitHook();
}

void install_hooks(zygisk::Api *api, const Config *cfg, HookProfile profile) {
    static std::mutex hook_mutex;
    std::lock_guard<std::mutex> lock(hook_mutex);
    // Copy into static storage: survives DLCLOSE_MODULE_LIBRARY which may
    // destroy the caller's Config before the library is actually unmapped.
    static Config s_cfg;
    s_cfg = *cfg;
    g_cfg = &s_cfg;
    g_api = api;
    g_profile = profile;

    // These linker exports accept the original call-site explicitly. Resolve
    // them before registering dlopen hooks; see h_dlopen above.
    if (!o_loader_dlopen) {
        o_loader_dlopen = reinterpret_cast<decltype(o_loader_dlopen)>(
            dlsym(RTLD_DEFAULT, "__loader_dlopen"));
    }
    if (!o_loader_android_dlopen_ext) {
        o_loader_android_dlopen_ext =
            reinterpret_cast<decltype(o_loader_android_dlopen_ext)>(
                dlsym(RTLD_DEFAULT, "__loader_android_dlopen_ext"));
    }

    const HookSpec *hooks = kHooks;
    size_t nhooks = sizeof(kHooks) / sizeof(kHooks[0]);
    if (profile == HookProfile::PropertiesOnly) {
        hooks = kPropsHooks;
        nhooks = sizeof(kPropsHooks) / sizeof(kPropsHooks[0]);
    }

    FILE *maps = fopen("/proc/self/maps", "re");
    if (!maps) return;

    // APK-embedded native libraries share the base APK's device/inode. Track
    // each executable ELF mapping base as well, otherwise an earlier APK/Dex
    // mapping makes a library loaded later look "already hooked".
    // A dedicated app zygote may preload the target's native library after a
    // narrow hook profile has been installed. Its child then needs the full
    // profile on the same inherited mapping. Track symbols independently so a
    // previously installed dlsym hook does not suppress every other hook.
    static std::set<std::tuple<dev_t, ino_t, unsigned long, std::string>> seen;
    char line[512];
    while (fgets(line, sizeof line, maps)) {
        unsigned long start, end, off;
        char perms[8];
        unsigned major, minor;
        unsigned long inode;
        char path[400] = {0};
        int n = sscanf(line, "%lx-%lx %7s %lx %x:%x %lu %399[^\n]",
                       &start, &end, perms, &off, &major, &minor, &inode, path);
        if (n < 7 || inode == 0 || !strchr(perms, 'x')) continue;
        char *p = path;
        while (*p == ' ') ++p;
        if (*p != '/') continue;
        // Never patch libzygisk.so: Zygisk dlcloses itself after specializeApp,
        // its destructor calls __system_property_get through the patched PLT, and
        // our hook then accesses the already-freed UdongeModule's g_cfg → SIGSEGV.
        if (strstr(p, "libzygisk")) continue;
        // Skip ioctl hook for libbinder.so: Duck Detector TEE native probe checks that
        // libbinder.so's ioctl GOT entry resolves to the real libc ioctl. Binder ioctl
        // codes (BINDER_WRITE_READ etc.) are never VPN/network-interface-related, so
        // excluding libbinder from the ioctl hook is safe and avoids false detection.
        const bool is_libbinder = (strstr(p, "libbinder.so") != nullptr);
        dev_t dev = makedev(major, minor);
        const unsigned long image_base = start - off;
        for (size_t i = 0; i < nhooks; i++) {
            if (is_libbinder && strcmp(hooks[i].sym, "ioctl") == 0) continue;
            if (!seen.insert({dev, inode, image_base, hooks[i].sym}).second) continue;
            api->pltHookRegister(dev, inode, hooks[i].sym, hooks[i].hook, hooks[i].orig);
        }
    }
    fclose(maps);
    api->pltHookCommit();
    install_late_library_hooks();
}

} // namespace cloak
