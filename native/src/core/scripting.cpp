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
    char new_path[4096];
    ssprintf(new_path, sizeof(new_path), "%s:%s", getenv("PATH"), get_magisk_tmp());
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
        LOGW("* post-fs-data scripts blocking phase timeout\n"); \
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
    LOGI("* Running %s.d scripts\n", stage.c_str());
    char path[4096];
    char *name = path + sprintf(path, SECURE_DIR "/%s.d", stage.c_str());
    auto dir = xopen_dir(path);
    if (!dir) return;

    bool pfs = stage == "post-fs-data"sv;
    int timer_pid = -1;
    if (pfs) {

        clock_gettime(CLOCK_MONOTONIC, &pfs_timeout);
        pfs_timeout.tv_sec += POST_FS_DATA_SCRIPT_MAX_TIME;
    }
    PFS_SETUP()

    *(name++) = '/';
    int dfd = dirfd(dir.get());
    for (dirent *entry; (entry = xreaddir(dir.get()));) {
        if (entry->d_type == DT_REG) {
            if (faccessat(dfd, entry->d_name, X_OK, 0) != 0)
                continue;
            LOGI("%s.d: exec [%s]\n", stage.c_str(), entry->d_name);
            strcpy(name, entry->d_name);
            exec_t exec {
                .pre_exec = set_script_env,
                .fork = pfs ? xfork : fork_dont_care
            };
            exec_command(exec, BBEXEC_CMD, path);
            PFS_WAIT()
        }
    }

    PFS_DONE()
}

static bool operator>(const timespec &a, const timespec &b) {
    if (a.tv_sec != b.tv_sec)
        return a.tv_sec > b.tv_sec;
    return a.tv_nsec > b.tv_nsec;
}

void exec_module_scripts(Utf8CStr stage, const rust::Vec<ModuleInfo> &module_list) {
    LOGI("* Running module %s scripts\n", stage.c_str());
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
        LOGI("%.*s: exec [%s.sh]\n", (int) m.name.size(), m.name.data(), stage.c_str());
        exec_t exec {
            .pre_exec = set_script_env,
            .fork = pfs ? xfork : fork_dont_care
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
