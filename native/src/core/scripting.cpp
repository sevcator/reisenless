#include <string>
#include <vector>
#include <sys/wait.h>

#include <consts.hpp>
#include <base.hpp>
#include <core.hpp>

using namespace std;

#define BBEXEC_CMD "busybox", "sh"

static const char *bbpath() {
    static string path;
    path = get_magisk_tmp();
    path += "/" BBPATH "/" BUILD_BUSYBOX_NAME;
    if (access(path.data(), X_OK) != 0) {
        path = DATABIN "/" BUILD_BUSYBOX_NAME;
    }
    return path.data();
}

static void set_script_env() {
    setenv("ASH_STANDALONE", "1", 1);
    setenv("NVBASE", SECURE_DIR, 1);
    setenv("MAGISKBIN", DATABIN, 1);
    setenv("MAGISKTMP", get_magisk_tmp(), 1);
    const char *old_path = getenv("PATH");
    if (!old_path || !*old_path) {
        old_path = "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin";
    }
    char new_path[4096];
    ssprintf(new_path, sizeof(new_path), "%s/%s:%s:%s", get_magisk_tmp(), BBPATH, get_magisk_tmp(), old_path);
    setenv("PATH", new_path, 1);
    if (MagiskD::Get().zygisk_enabled())
        setenv("ZYG_ENABLED", "1", 1);
};

void exec_script(Utf8CStr script) {
    exec_t exec {
        .pre_exec = set_script_env,
        .fork = fork_no_orphan,
        .path = bbpath(),
    };
    exec_command_sync(exec, BBEXEC_CMD, script.c_str());
}

void exec_script_async(Utf8CStr script) {
    exec_t exec {
        .pre_exec = set_script_env,
        .fork = fork_dont_care,
        .path = bbpath(),
    };
    exec_command(exec, BBEXEC_CMD, script.c_str());
}

static timespec pfs_timeout;

#define PFS_SETUP() \
if (pfs) { \
    if (int pid = xfork()) { \
        if (pid < 0) \
            return; \
                                                                 \
        waitpid(pid, nullptr, 0); \
        return; \
    } \
    timer_pid = xfork(); \
    if (timer_pid == 0) { \
                                           \
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &pfs_timeout, nullptr); \
        exit(0); \
    } \
}

#define PFS_WAIT() \
if (pfs) { \
                                             \
    if (timer_pid < 0) \
        continue; \
    if (int pid = waitpid(-1, nullptr, 0); pid == timer_pid) { \
        timer_pid = -1; \
    } \
}

#define PFS_DONE() \
if (pfs) { \
    if (timer_pid > 0) \
        kill(timer_pid, SIGKILL); \
    exit(0); \
}

void exec_common_scripts(Utf8CStr stage) {
    char path[4096];
    char *name = path + sprintf(path, SECURE_DIR "/%s.d", stage.c_str());
    auto dir = xopen_dir(path);
    if (!dir) return;

    *(name++) = '/';
    int dfd = dirfd(dir.get());
    vector<string> scripts;
    for (dirent *entry; (entry = xreaddir(dir.get()));) {
        if (entry->d_type != DT_REG || faccessat(dfd, entry->d_name, X_OK, 0) != 0)
            continue;
        strcpy(name, entry->d_name);
        scripts.emplace_back(path);
    }
    if (scripts.empty()) return;

    bool pfs = stage == "post-fs-data"sv;
    int timer_pid = -1;
    if (pfs) {

        clock_gettime(CLOCK_MONOTONIC, &pfs_timeout);
        pfs_timeout.tv_sec += POST_FS_DATA_SCRIPT_MAX_TIME;
    }
    PFS_SETUP()

    for (const auto &script : scripts) {
        exec_t exec {
            .pre_exec = set_script_env,
            .fork = pfs ? xfork : fork_dont_care,
            .path = bbpath(),
        };
        exec_command(exec, BBEXEC_CMD, script.data());
        PFS_WAIT()
    }

    PFS_DONE()
}

static bool operator>(const timespec &a, const timespec &b) {
    if (a.tv_sec != b.tv_sec)
        return a.tv_sec > b.tv_sec;
    return a.tv_nsec > b.tv_nsec;
}

void exec_module_scripts(Utf8CStr stage, const rust::Vec<ModuleInfo> &module_list) {
    if (module_list.empty())
        return;

    bool pfs = stage == "post-fs-data";
    if (pfs) {
        timespec now{};
        clock_gettime(CLOCK_MONOTONIC, &now);

        if (now > pfs_timeout)
            pfs = false;
    }
    int timer_pid = -1;
    PFS_SETUP()

    char path[4096];
    for (auto &m : module_list) {
        if (!m.name.empty() && m.name.data()[0] == '@')
            continue;
        sprintf(path, MODULEROOT "/%.*s/%s.sh", (int) m.name.size(), m.name.data(), stage.c_str());
        if (access(path, F_OK) == -1)
            continue;
        exec_t exec {
            .pre_exec = set_script_env,
            .fork = pfs ? xfork : fork_dont_care,
            .path = bbpath(),
        };
        exec_command(exec, BBEXEC_CMD, path);
        PFS_WAIT()
    }

    PFS_DONE()
}

constexpr char clear_script[] = R"EOF(
PKG=%s
USER=%d
pm clear --user $USER $PKG
)EOF";

void clear_pkg(const char *pkg, int user_id) {
    char cmds[sizeof(clear_script) + 288];
    ssprintf(cmds, sizeof(cmds), clear_script, pkg, user_id);
    exec_command_async("/system/bin/sh", "-c", cmds);
}

[[noreturn]] __printflike(2, 3)
static void abort_script(FILE *fp, const char *fmt, ...) {
    va_list valist;
    va_start(valist, fmt);
    vfprintf(fp, fmt, valist);
    fprintf(fp, "\n\n");
    va_end(valist);
    exit(1);
}

constexpr char install_module_script[] =
        ". " DATABIN "/util_functions.sh\n"
        "install_module\n"
        "exit 0\n";

void install_module(Utf8CStr file) {
    if (getuid() != 0)
        abort_script(stderr, "Run this command with root");
    if (access(DATABIN, F_OK) ||
        access(bbpath(), X_OK) ||
        access(DATABIN "/util_functions.sh", F_OK))
        abort_script(stderr, "Incomplete Magisk install");
    if (access(file.c_str(), F_OK))
        abort_script(stderr, "'%s' does not exist", file.c_str());

    char *zip = realpath(file.c_str(), nullptr);
    setenv("OUTFD", "1", 1);
    setenv("ZIPFILE", zip, 1);
    setenv("ASH_STANDALONE", "1", 1);
    setenv("NVBASE", SECURE_DIR, 1);
    setenv("MAGISKBIN", DATABIN, 1);
    setenv("MAGISKTMP", get_magisk_tmp(), 1);
    free(zip);

    int fd = xopen("/dev/null", O_RDONLY);
    xdup2(fd, STDERR_FILENO);
    close(fd);

    const char *bb = bbpath();
    const char *argv[] = { bb, "sh", "-c", install_module_script, nullptr };
    execve(bb, (char **) argv, environ);
    abort_script(stdout, "Failed to execute BusyBox shell");
}
