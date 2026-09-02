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
#include <set>
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
    // KernelSU, APatch
    "kernelsu", "KernelSU",
    "apatch",   "APatch",
    // Root kernel device nodes
    "/dev/ksud",
    "/dev/apatch",
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

// Return true if the path contains any user-configured ROM keyword.
// Called from is_blocked(), which is already guarded by a !path check.
static bool is_rom_path(const char *path) {
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
    };
    for (const char *blocked : exact_paths) {
        const size_t length = strlen(blocked);
        if (strncmp(path, blocked, length) == 0 &&
            (path[length] == '\0' || path[length] == '/')) return true;
    }
    return false;
}

static bool is_rom_policy_source(const char *path) {
    if (!path || !g_cfg || g_cfg->rom_keywords.empty()) return false;
    static const char *const sources[] = {
        "/vendor/etc/selinux/vendor_sepolicy.cil",
        "/system_ext/etc/selinux/system_ext_sepolicy.cil",
        "/vendor/etc/selinux/vendor_file_contexts",
    };
    for (const char *source : sources)
        if (strcmp(path, source) == 0) return true;
    return false;
}

static bool is_rom_symbol_source(const char *path) {
    if (!path || !g_cfg || g_cfg->rom_keywords.empty()) return false;
    static const char suffix[] = "/libstagefright.so";
    const size_t path_len = strlen(path);
    const size_t suffix_len = sizeof(suffix) - 1;
    return path_len >= suffix_len &&
           strcmp(path + path_len - suffix_len, suffix) == 0;
}

static bool is_blocked(const char *path) {
    if (!path) return false;
    for (const char *s : kBlockedSubstr)
        if (strstr(path, s)) return true;
    if (basename_is_su(path)) return true;
    return is_rom_path(path);
}

// ---- originals ----
static int     (*o_faccessat)(int, const char *, int, int);
static int     (*o_access)(const char *, int);
static int     (*o_stat)(const char *, struct stat *);
static int     (*o_lstat)(const char *, struct stat *);
static int     (*o_fstatat)(int, const char *, struct stat *, int);
static int     (*o_open)(const char *, int, ...);
static int     (*o_open_2)(const char *, int);
static int     (*o_openat)(int, const char *, int, ...);
static FILE   *(*o_fopen)(const char *, const char *);
static ssize_t (*o_readlink)(const char *, char *, size_t);
static ssize_t (*o_readlinkat)(int, const char *, char *, size_t);
static ssize_t (*o_read)(int, void *, size_t);
static int     (*o_prop_get)(const char *, char *);
static void    (*o_prop_read_cb)(const void *, void (*)(void *, const char *, const char *, uint32_t), void *);
static DIR    *(*o_opendir)(const char *);
static struct dirent *(*o_readdir)(DIR *);
static char   *(*o_getenv)(const char *);
static void   *(*o_dlsym)(void *, const char *);
static void   *(*o_dlopen)(const char *, int);
static void   *(*o_android_dlopen_ext)(const char *, int, const void *);
static void   *(*o_loader_dlopen)(const char *, int, const void *);
static void   *(*o_loader_android_dlopen_ext)(const char *, int, const void *, const void *);
static jstring (*o_runtime_native_load)(JNIEnv *, jclass, jstring, jobject, jclass);
static jboolean (*o_selinux_check_access)(
        JNIEnv *, jclass, jstring, jstring, jstring, jstring);
static int (*o_selinux_native_check_access)(
        const char *, const char *, const char *, const char *, void *);

static void install_late_library_hooks();

static bool is_detector_policy_edge(const char *source, const char *target,
                                    const char *target_class, const char *permission) {
    if (!source || !target || !target_class || !permission) return false;
    struct PolicyEdge {
        const char *source;
        const char *target;
        const char *target_class;
        const char *permission;
    };
    static const PolicyEdge detector_edges[] = {
        {"u:r:system_server:s0", "u:r:system_server:s0", "process", "execmem"},
        {"u:r:fsck_untrusted:s0", "u:r:fsck_untrusted:s0", "capability", "sys_admin"},
        {"u:r:shell:s0", "u:r:su:s0", "process", "transition"},
        {"u:r:adbd:s0", "u:r:adbroot:s0", "binder", "call"},
    };
    for (const auto &edge : detector_edges) {
        if (strcmp(source, edge.source) == 0 && strcmp(target, edge.target) == 0 &&
            strcmp(target_class, edge.target_class) == 0 &&
            strcmp(permission, edge.permission) == 0) return true;
    }
    return false;
}

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

static jboolean h_duck_mount_preload_false(JNIEnv *, jobject) {
    return JNI_FALSE;
}

static jobjectArray h_duck_mount_preload_findings(JNIEnv *env, jobject) {
    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return nullptr;
    jobjectArray result = env->NewObjectArray(0, string_class, nullptr);
    env->DeleteLocalRef(string_class);
    return result;
}

static void suppress_duck_mount_preload(JNIEnv *env, jobject loader, jstring filename) {
    if (!env || !loader || !filename || !g_cfg || g_cfg->rom_keywords.empty()) return;
    const char *path = env->GetStringUTFChars(filename, nullptr);
    const bool is_duck_library = path && strstr(path, "libduckdetector.so");
    if (path) env->ReleaseStringUTFChars(filename, path);
    if (!is_duck_library) return;

    jclass loader_class = env->GetObjectClass(loader);
    jmethodID load_class = loader_class ? env->GetMethodID(
        loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;") : nullptr;
    jstring bridge_name = env->NewStringUTF(
        "com.eltavine.duckdetector.core.startup.preload.EarlyMountPreloadBridge");
    jobject bridge_object = load_class && bridge_name
        ? env->CallObjectMethod(loader, load_class, bridge_name) : nullptr;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        bridge_object = nullptr;
    }
    if (bridge_object) {
        JNINativeMethod methods[] = {
            {const_cast<char *>("nativeWasDetected"),
             const_cast<char *>("()Z"),
             reinterpret_cast<void *>(h_duck_mount_preload_false)},
            {const_cast<char *>("nativeWasPeerGroupGapDetected"),
             const_cast<char *>("()Z"),
             reinterpret_cast<void *>(h_duck_mount_preload_false)},
            {const_cast<char *>("nativeGetFindings"),
             const_cast<char *>("()[Ljava/lang/String;"),
             reinterpret_cast<void *>(h_duck_mount_preload_findings)},
        };
        env->RegisterNatives(static_cast<jclass>(bridge_object), methods,
                             sizeof(methods) / sizeof(methods[0]));
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (bridge_object) env->DeleteLocalRef(bridge_object);
    if (bridge_name) env->DeleteLocalRef(bridge_name);
    if (loader_class) env->DeleteLocalRef(loader_class);
}

static jstring h_runtime_native_load(JNIEnv *env, jclass type, jstring filename,
                                     jobject loader, jclass caller) {
    jstring error = o_runtime_native_load(env, type, filename, loader, caller);
    refresh_late_library_hooks();
    if (!error) suppress_duck_mount_preload(env, loader, filename);
    return error;
}

void hook_native_load(zygisk::Api *api, JNIEnv *env) {
    JNINativeMethod method{
        const_cast<char *>("nativeLoad"),
        const_cast<char *>(
            "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;"),
        reinterpret_cast<void *>(h_runtime_native_load),
    };
    api->hookJniNativeMethods(env, "java/lang/Runtime", &method, 1);
    o_runtime_native_load =
        reinterpret_cast<decltype(o_runtime_native_load)>(method.fnPtr);
}

static jboolean h_selinux_check_access(JNIEnv *env, jclass type, jstring source,
                                       jstring target, jstring target_class,
                                       jstring permission) {
    const char *source_chars = source ? env->GetStringUTFChars(source, nullptr) : nullptr;
    const char *target_chars = target ? env->GetStringUTFChars(target, nullptr) : nullptr;
    const char *class_chars = target_class
            ? env->GetStringUTFChars(target_class, nullptr) : nullptr;
    const char *permission_chars = permission
            ? env->GetStringUTFChars(permission, nullptr) : nullptr;

    const bool blocked = is_detector_policy_edge(
            source_chars, target_chars, class_chars, permission_chars);

    if (source_chars) env->ReleaseStringUTFChars(source, source_chars);
    if (target_chars) env->ReleaseStringUTFChars(target, target_chars);
    if (class_chars) env->ReleaseStringUTFChars(target_class, class_chars);
    if (permission_chars) env->ReleaseStringUTFChars(permission, permission_chars);
    if (blocked) return JNI_FALSE;
    return o_selinux_check_access
            ? o_selinux_check_access(env, type, source, target, target_class, permission)
            : JNI_FALSE;
}

void hook_selinux_access(zygisk::Api *api, JNIEnv *env) {
    JNINativeMethod method{
        const_cast<char *>("checkSELinuxAccess"),
        const_cast<char *>(
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z"),
        reinterpret_cast<void *>(h_selinux_check_access),
    };
    api->hookJniNativeMethods(env, "android/os/SELinux", &method, 1);
    o_selinux_check_access =
        reinterpret_cast<decltype(o_selinux_check_access)>(method.fnPtr);
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

static void normalize_shell_tmp_stat(const char *path, struct stat *st) {
    if (path && st && strcmp(path, "/data/local/tmp") == 0 && st->st_ino > 10000) {
        st->st_ino = 4096;
    }
}

static int h_stat(const char *p, struct stat *s) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    int result = o_stat(p, s);
    if (result == 0) normalize_shell_tmp_stat(p, s);
    return result;
}
static int h_lstat(const char *p, struct stat *s) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    int result = o_lstat(p, s);
    if (result == 0) normalize_shell_tmp_stat(p, s);
    return result;
}
static int h_fstatat(int d, const char *p, struct stat *s, int f) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    int result = o_fstatat(d, p, s, f);
    if (result == 0 && (d == AT_FDCWD || (p && p[0] == '/'))) {
        normalize_shell_tmp_stat(p, s);
    }
    return result;
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
        if (keep) out.insert(out.end(), p, p + len);
        p += len;
    }
    return out;
}

static std::string normalize_jit_inode(const char *line, size_t length,
                                       std::string &first_inode) {
    std::string result(line, length);
    if (!contains_ci(line, length, "jit-cache", 9)) return result;

    size_t cursor = 0;
    for (int field = 0; field < 4; ++field) {
        while (cursor < result.size() && result[cursor] != ' ' && result[cursor] != '\t') {
            ++cursor;
        }
        while (cursor < result.size() && (result[cursor] == ' ' || result[cursor] == '\t')) {
            ++cursor;
        }
    }
    const size_t inode_start = cursor;
    while (cursor < result.size() && result[cursor] >= '0' && result[cursor] <= '9') ++cursor;
    if (cursor == inode_start) return result;

    const std::string inode = result.substr(inode_start, cursor - inode_start);
    if (first_inode.empty()) {
        first_inode = inode;
    } else if (inode != first_inode) {
        result.replace(inode_start, cursor - inode_start, first_inode);
    }
    return result;
}

static std::vector<char> filter_maps(const std::vector<char> &raw) {
    const auto clean = filter_blocked_lines(raw, false);
    std::vector<char> out;
    out.reserve(clean.size());
    std::string first_jit_inode;
    const char *p = clean.data();
    const char *end = p + clean.size();
    while (p < end) {
        const char *nl = static_cast<const char *>(memchr(p, '\n', end - p));
        const size_t length = nl ? static_cast<size_t>(nl - p + 1)
                                 : static_cast<size_t>(end - p);
        const std::string line = normalize_jit_inode(p, length, first_jit_inode);
        out.insert(out.end(), line.begin(), line.end());
        p += length;
    }
    return out;
}

static std::vector<char> filter_smaps(const std::vector<char> &raw) {
    std::vector<char> out;
    out.reserve(raw.size());
    std::string first_jit_inode;
    bool keep_block = true;
    bool webview_executable = false;
    const char *p = raw.data();
    const char *end = p + raw.size();
    while (p < end) {
        const char *nl = static_cast<const char *>(memchr(p, '\n', end - p));
        const size_t length = nl ? static_cast<size_t>(nl - p + 1)
                                 : static_cast<size_t>(end - p);
        std::string line = normalize_jit_inode(p, length, first_jit_inode);

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

enum ProcFilter { kFilterMaps, kFilterSmaps, kFilterStatus, kFilterMounts };

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
    int real_fd = o_open(path, O_RDONLY | O_CLOEXEC);
    if (real_fd < 0) return real_fd;
    auto raw = read_all_fd(real_fd);
    ::close(real_fd);

    std::vector<char> filtered;
    switch (filter) {
        case kFilterMaps:   filtered = filter_maps(raw); break;
        case kFilterSmaps:  filtered = filter_smaps(raw); break;
        case kFilterStatus: filtered = filter_status(raw); break;
        case kFilterMounts: filtered = filter_blocked_lines(raw, true);  break;
    }

    int anon = make_anon_fd(filtered);
    if (anon < 0) return o_open(path, O_RDONLY | O_CLOEXEC);
    return anon;
}

static int filter_rom_policy_fd(int real_fd) {
    if (real_fd < 0) return real_fd;
    auto raw = read_all_fd(real_fd);
    auto filtered = filter_blocked_lines(raw, false);
    int anon = make_anon_fd(filtered);
    if (anon >= 0) {
        ::close(real_fd);
        return anon;
    }
    lseek(real_fd, 0, SEEK_SET);
    return real_fd;
}

static int filter_rom_symbol_fd(int real_fd) {
    if (real_fd < 0) return real_fd;
    auto filtered = read_all_fd(real_fd);
    static const char symbol[] = "_ZN7android15ANetworkSession10threadLoopEv";
    const size_t symbol_len = sizeof(symbol) - 1;
    for (size_t offset = 0; offset + symbol_len <= filtered.size(); ++offset) {
        if (memcmp(filtered.data() + offset, symbol, symbol_len) == 0) {
            filtered[offset] = '!';
        }
    }
    int anon = make_anon_fd(filtered);
    if (anon >= 0) {
        ::close(real_fd);
        return anon;
    }
    lseek(real_fd, 0, SEEK_SET);
    return real_fd;
}

// ---- open / openat hooks ----
static int h_open(const char *p, int fl, ...) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    int mode = 0;
    if (fl & O_CREAT) { va_list ap; va_start(ap, fl); mode = va_arg(ap, int); va_end(ap); }
    if ((fl & O_ACCMODE) == O_RDONLY) {
        if (is_self_proc_file(p, "maps"))   return open_filtered_proc(p, kFilterMaps);
        if (is_self_proc_file(p, "smaps"))  return open_filtered_proc(p, kFilterSmaps);
        if (is_self_proc_file(p, "status")) return open_filtered_proc(p, kFilterStatus);
        if (is_mount_path(p))               return open_filtered_proc(p, kFilterMounts);
        if (is_rom_policy_source(p))         return filter_rom_policy_fd(
            o_open(p, O_RDONLY | O_CLOEXEC));
        if (is_rom_symbol_source(p))         return filter_rom_symbol_fd(
            o_open(p, O_RDONLY | O_CLOEXEC));
    }
    return o_open(p, fl, mode);
}

static int h_open_2(const char *p, int fl) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    if ((fl & O_ACCMODE) == O_RDONLY) {
        if (is_self_proc_file(p, "maps"))   return open_filtered_proc(p, kFilterMaps);
        if (is_self_proc_file(p, "smaps"))  return open_filtered_proc(p, kFilterSmaps);
        if (is_self_proc_file(p, "status")) return open_filtered_proc(p, kFilterStatus);
        if (is_mount_path(p))               return open_filtered_proc(p, kFilterMounts);
        if (is_rom_policy_source(p))         return filter_rom_policy_fd(
            o_open(p, O_RDONLY | O_CLOEXEC));
        if (is_rom_symbol_source(p))         return filter_rom_symbol_fd(
            o_open(p, O_RDONLY | O_CLOEXEC));
    }
    return o_open_2(p, fl);
}

static int h_openat(int d, const char *p, int fl, ...) {
    if (is_blocked(p)) { errno = ENOENT; return -1; }
    int mode = 0;
    if (fl & O_CREAT) { va_list ap; va_start(ap, fl); mode = va_arg(ap, int); va_end(ap); }
    if ((fl & O_ACCMODE) == O_RDONLY) {
        if (is_self_proc_file(p, "maps"))   return open_filtered_proc(p, kFilterMaps);
        if (is_self_proc_file(p, "smaps"))  return open_filtered_proc(p, kFilterSmaps);
        if (is_self_proc_file(p, "status")) return open_filtered_proc(p, kFilterStatus);
        if (is_mount_path(p))               return open_filtered_proc(p, kFilterMounts);
        if (is_rom_policy_source(p))         return filter_rom_policy_fd(
            o_openat(d, p, O_RDONLY | O_CLOEXEC));
        if (is_rom_symbol_source(p))         return filter_rom_symbol_fd(
            o_openat(d, p, O_RDONLY | O_CLOEXEC));
    }
    return o_openat(d, p, fl, mode);
}

static FILE *h_fopen(const char *p, const char *mode) {
    if (is_blocked(p)) { errno = ENOENT; return nullptr; }
    if (mode && mode[0] == 'r') {
        int fd = -1;
        if (is_self_proc_file(p, "maps"))        fd = open_filtered_proc(p, kFilterMaps);
        else if (is_self_proc_file(p, "smaps"))  fd = open_filtered_proc(p, kFilterSmaps);
        else if (is_self_proc_file(p, "status")) fd = open_filtered_proc(p, kFilterStatus);
        else if (is_mount_path(p))               fd = open_filtered_proc(p, kFilterMounts);
        else if (is_rom_policy_source(p))        fd = filter_rom_policy_fd(
            o_open(p, O_RDONLY | O_CLOEXEC));
        else if (is_rom_symbol_source(p))        fd = filter_rom_symbol_fd(
            o_open(p, O_RDONLY | O_CLOEXEC));
        else                                     return o_fopen(p, mode);
        if (fd < 0) return nullptr;
        FILE *stream = fdopen(fd, mode);
        if (!stream) close(fd);
        return stream;
    }
    return o_fopen(p, mode);
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
        if (entry->d_name[0] && (is_blocked(entry->d_name) || basename_is_su(entry->d_name)))
            continue;
        break;
    }
    return entry;
}

static DIR *h_opendir(const char *p) {
    if (is_blocked(p)) { errno = ENOENT; return nullptr; }
    return o_opendir(p);
}

// Duck Detector snapshots every property by spawning getprop, so property API
// hooks alone would disagree with the child-process output. Rewrite only the
// exact USB configuration line in pipe reads, preserving its byte length. This
// keeps ADB enabled on the real device while all detector-visible sources agree.
static void rewrite_usb_config_line(char *buffer, size_t length) {
    static const char prefix[] = "[persist.sys.usb.config]: [";
    constexpr size_t prefix_length = sizeof(prefix) - 1;
    if (!buffer || length < prefix_length + 4) return;

    for (size_t offset = 0; offset + prefix_length < length; ++offset) {
        if (memcmp(buffer + offset, prefix, prefix_length) != 0) continue;
        char *value = buffer + offset + prefix_length;
        char *limit = buffer + length;
        char *closing = static_cast<char *>(memchr(value, ']', limit - value));
        if (!closing) return;
        const size_t value_length = static_cast<size_t>(closing - value);
        if (!contains_ci(value, value_length, "adb", 3)) return;
        memset(value, ' ', value_length);
        if (value_length >= 3) memcpy(value, "mtp", 3);
        return;
    }
}

static ssize_t h_read(int fd, void *buffer, size_t count) {
    ssize_t result = o_read(fd, buffer, count);
    if (result > 0) rewrite_usb_config_line(
            static_cast<char *>(buffer), static_cast<size_t>(result));
    return result;
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

// Duck Detector checks one Lineage-added private stagefright symbol. Keep the
// filter exact so ordinary native symbol resolution is untouched.
static void *h_dlsym(void *handle, const char *symbol) {
    if (symbol && strcmp(symbol, "selinux_check_access") == 0) {
        void *resolved = o_dlsym(handle, symbol);
        if (!resolved) return nullptr;
        o_selinux_native_check_access =
                reinterpret_cast<decltype(o_selinux_native_check_access)>(resolved);
        return reinterpret_cast<void *>(+[](
                const char *source, const char *target, const char *target_class,
                const char *permission, void *audit_data) -> int {
            if (is_detector_policy_edge(source, target, target_class, permission)) {
                errno = EACCES;
                return -1;
            }
            return o_selinux_native_check_access
                    ? o_selinux_native_check_access(
                              source, target, target_class, permission, audit_data)
                    : -1;
        });
    }
    if (g_cfg && !g_cfg->rom_keywords.empty() && symbol &&
        strcmp(symbol, "_ZN7android15ANetworkSession10threadLoopEv") == 0) {
        return nullptr;
    }
    return o_dlsym(handle, symbol);
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
};

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

static bool str_ends_with(const char *s, const char *suffix) {
    size_t sl = strlen(s), el = strlen(suffix);
    return sl >= el && strcmp(s + sl - el, suffix) == 0;
}

static const char *find_boot_prop(const char *name) {
    for (const auto &bp : kBootProps)
        if (strcmp(name, bp.name) == 0) return bp.value;
    // Suffix-based spoofing (KOWX712 approach): spoof all *.api_level props
    // with DEVICE_INITIAL_SDK_INT so DuckDetector/root checks see consistent values.
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
// Props whose VALUE is checked against ROM keywords and suppressed if it matches.
// Used for props that carry the ROM name in their value rather than their key.
static const char *const kRomValueCheckProps[] = {
    "ro.build.flavor",
    "ro.build.display.id",
};

static bool is_deleted_prop(const char *name) {
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

// Returns true if value contains a user-configured ROM keyword.
static bool value_has_rom_keyword(const char *value) {
    if (!value || !g_cfg) return false;
    for (const auto &kw : g_cfg->rom_keywords)
        if (contains_ci(value, kw)) return true;
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

static bool is_rom_value_check_prop(const char *name) {
    for (const char *p : kRomValueCheckProps)
        if (strcmp(name, p) == 0) return true;
    return false;
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
        // Suppress props whose value exposes a configured ROM keyword.
        if (is_rom_value_check_prop(name)) {
            int len = o_prop_get(name, value);
            if (len > 0 && value_has_rom_keyword(value)) {
                value[0] = '\0';
                return 0;
            }
            return len;
        }
        if (is_debug_replace_prop(name)) {
            int len = o_prop_get(name, value);
            if (len > 0) return normalize_build_variant(value, len);
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

// ---- hook table ----
struct HookSpec { const char *sym; void *hook; void **orig; };

static const HookSpec kHooks[] = {
    {"faccessat",  (void *)h_faccessat,  (void **)&o_faccessat},
    {"access",     (void *)h_access,     (void **)&o_access},
    {"stat",       (void *)h_stat,       (void **)&o_stat},
    {"lstat",      (void *)h_lstat,      (void **)&o_lstat},
    {"fstatat",    (void *)h_fstatat,    (void **)&o_fstatat},
    {"open",       (void *)h_open,       (void **)&o_open},
    {"__open_2",   (void *)h_open_2,     (void **)&o_open_2},
    {"openat",     (void *)h_openat,     (void **)&o_openat},
    {"fopen",      (void *)h_fopen,      (void **)&o_fopen},
    {"readlink",   (void *)h_readlink,   (void **)&o_readlink},
    {"readlinkat", (void *)h_readlinkat, (void **)&o_readlinkat},
    {"read",       (void *)h_read,       (void **)&o_read},
    {"opendir",    (void *)h_opendir,    (void **)&o_opendir},
    {"readdir",    (void *)h_readdir,    (void **)&o_readdir},
    {"getenv",     (void *)h_getenv,     (void **)&o_getenv},
    {"dlsym",      (void *)h_dlsym,      (void **)&o_dlsym},
    {"dlopen",     (void *)h_dlopen,     (void **)&o_dlopen},
    {"android_dlopen_ext", (void *)h_android_dlopen_ext,
                            (void **)&o_android_dlopen_ext},
    {"__system_property_get",           (void *)h_prop_get,     (void **)&o_prop_get},
    {"__system_property_read_callback", (void *)h_prop_read_cb, (void **)&o_prop_read_cb},
};

static const HookSpec kPropsHooks[] = {
    {"__system_property_get",           (void *)h_prop_get,     (void **)&o_prop_get},
    {"__system_property_read_callback", (void *)h_prop_read_cb, (void **)&o_prop_read_cb},
};

static const HookSpec kSelinuxHooks[] = {
    {"dlsym", (void *)h_dlsym, (void **)&o_dlsym},
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
    } else if (profile == HookProfile::SelinuxOnly) {
        hooks = kSelinuxHooks;
        nhooks = sizeof(kSelinuxHooks) / sizeof(kSelinuxHooks[0]);
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
        dev_t dev = makedev(major, minor);
        const unsigned long image_base = start - off;
        for (size_t i = 0; i < nhooks; i++) {
            if (!seen.insert({dev, inode, image_base, hooks[i].sym}).second) continue;
            api->pltHookRegister(dev, inode, hooks[i].sym, hooks[i].hook, hooks[i].orig);
        }
    }
    fclose(maps);
    api->pltHookCommit();
    install_late_library_hooks();
}

} // namespace cloak
