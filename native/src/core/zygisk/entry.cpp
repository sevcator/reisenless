#include <sys/mount.h>
#include <android/dlext.h>
#include <dlfcn.h>
#include <poll.h>

#include <base.hpp>
#include <core.hpp>

#include "zygisk.hpp"

#include <lsplt.hpp>
#include <consts.hpp>
#include <cstdarg>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/mman.h>

#ifndef O_TMPFILE
#define __O_TMPFILE 020000000
#define O_TMPFILE (__O_TMPFILE | O_DIRECTORY)
#endif

using namespace std;

using comp_entry = void(*)(int);
extern "C" void exec_companion_entry(int, comp_entry);

static std::string remap_path(const char *path) {
    if (!path) return {};
    constexpr std::string_view kAdbPrefix = "/data/adb/";
    if (std::string_view(path).starts_with(kAdbPrefix)) {
        return std::string(SECURE_DIR) + "/" + (path + kAdbPrefix.length());
    }
    return path;
}

static FILE *(*old_fopen)(const char *, const char *) = nullptr;
static FILE *hook_fopen(const char *path, const char *mode) {
    if (!path) return old_fopen ? old_fopen(path, mode) : fopen(path, mode);
    auto remapped = remap_path(path);
    return old_fopen ? old_fopen(remapped.c_str(), mode) : fopen(remapped.c_str(), mode);
}

static int (*old_open)(const char *, int, ...) = nullptr;
static int hook_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || ((flags & O_TMPFILE) == O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    if (!path) return old_open ? old_open(path, flags, mode) : open(path, flags, mode);
    auto remapped = remap_path(path);
    return old_open ? old_open(remapped.c_str(), flags, mode) : open(remapped.c_str(), flags, mode);
}

static int (*old_openat)(int, const char *, int, ...) = nullptr;
static int hook_openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || ((flags & O_TMPFILE) == O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    if (!path) return old_openat ? old_openat(dirfd, path, flags, mode) : openat(dirfd, path, flags, mode);
    if (path[0] == '/') {
        auto remapped = remap_path(path);
        return old_openat ? old_openat(dirfd, remapped.c_str(), flags, mode) : openat(dirfd, remapped.c_str(), flags, mode);
    }
    return old_openat ? old_openat(dirfd, path, flags, mode) : openat(dirfd, path, flags, mode);
}

static int (*old_open_2)(const char *, int) = nullptr;
static int hook_open_2(const char *path, int flags) {
    if (!path) return old_open_2 ? old_open_2(path, flags) : __open_2(path, flags);
    auto remapped = remap_path(path);
    return old_open_2 ? old_open_2(remapped.c_str(), flags) : __open_2(remapped.c_str(), flags);
}

static int (*old_access)(const char *, int) = nullptr;
static int hook_access(const char *path, int mode) {
    if (!path) return old_access ? old_access(path, mode) : access(path, mode);
    auto remapped = remap_path(path);
    return old_access ? old_access(remapped.c_str(), mode) : access(remapped.c_str(), mode);
}

static int (*old_stat)(const char *, struct stat *) = nullptr;
static int hook_stat(const char *path, struct stat *buf) {
    if (!path) return old_stat ? old_stat(path, buf) : stat(path, buf);
    auto remapped = remap_path(path);
    return old_stat ? old_stat(remapped.c_str(), buf) : stat(remapped.c_str(), buf);
}

static int (*old_fstatat)(int, const char *, struct stat *, int) = nullptr;
static int hook_fstatat(int dirfd, const char *path, struct stat *buf, int flags) {
    if (!path) return old_fstatat ? old_fstatat(dirfd, path, buf, flags) : fstatat(dirfd, path, buf, flags);
    if (path[0] == '/') {
        auto remapped = remap_path(path);
        return old_fstatat ? old_fstatat(dirfd, remapped.c_str(), buf, flags) : fstatat(dirfd, remapped.c_str(), buf, flags);
    }
    return old_fstatat ? old_fstatat(dirfd, path, buf, flags) : fstatat(dirfd, path, buf, flags);
}

static void hook_companion_plt() {
    for (const auto &map : lsplt::MapInfo::Scan()) {
        if (map.offset != 0 || !map.is_private || !(map.perms & PROT_READ)) continue;
        if (map.path.empty() || map.path[0] == '[') continue;
        lsplt::RegisterHook(map.dev, map.inode, "fopen", (void *) hook_fopen, (void **) &old_fopen);
        lsplt::RegisterHook(map.dev, map.inode, "open", (void *) hook_open, (void **) &old_open);
        lsplt::RegisterHook(map.dev, map.inode, "openat", (void *) hook_openat, (void **) &old_openat);
        lsplt::RegisterHook(map.dev, map.inode, "__open_2", (void *) hook_open_2, (void **) &old_open_2);
        lsplt::RegisterHook(map.dev, map.inode, "access", (void *) hook_access, (void **) &old_access);
        lsplt::RegisterHook(map.dev, map.inode, "stat", (void *) hook_stat, (void **) &old_stat);
        lsplt::RegisterHook(map.dev, map.inode, "fstatat", (void *) hook_fstatat, (void **) &old_fstatat);
    }
    lsplt::CommitHook();
}

static void zygiskd(int socket) {
    if (getuid() != 0 || fcntl(socket, F_GETFD) < 0)
        exit(-1);

    set_nice_name(get_runtime_zygisk_name());


    vector<comp_entry> modules;
    {
        auto module_fds = recv_fds(socket);
        for (int fd : module_fds) {
            comp_entry entry = nullptr;
            struct stat s{};
            if (fstat(fd, &s) == 0 && S_ISREG(s.st_mode)) {
                android_dlextinfo info {
                    .flags = ANDROID_DLEXT_USE_LIBRARY_FD,
                    .library_fd = fd,
                };
                if (void *h = android_dlopen_ext("/memfd:anon", RTLD_LAZY, &info)) {
                    *(void **) &entry = dlsym(h, "zygisk_companion_entry");
                } else {
                    LOGW("Failed to dlopen zygisk module: %s\n", dlerror());
                }
            }
            modules.push_back(entry);
            close(fd);
        }
    }


    write_int(socket, 0);
    hook_companion_plt();


    pollfd pfd = { socket, POLLIN, 0 };
    for (;;) {
        poll(&pfd, 1, -1);
        if (pfd.revents && !(pfd.revents & POLLIN)) {

            exit(0);
        }
        int client = recv_fd(socket);
        if (client < 0) {

            exit(0);
        }
        int module_id = read_int(client);
        if (module_id >= 0 && module_id < modules.size() && modules[module_id]) {
            exec_companion_entry(client, modules[module_id]);
        } else {
            close(client);
        }
    }
}



int zygisk_main(int argc, char *argv[]) {
    android_logging();
    if (argc == 3 && argv[1] == "companion"sv) {
        zygiskd(parse_int(argv[2]));
    }
    return 0;
}


extern "C" [[maybe_unused]] NativeBridgeCallbacks NativeBridgeItf {
    .version = 2,
    .padding = {},
    .isCompatibleWith = [](auto) {
        zygisk_logging();
        hook_entry();
        ZLOGD("load success\n");
        return false;
    },
};
