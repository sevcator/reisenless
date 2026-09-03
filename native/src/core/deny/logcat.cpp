#include <algorithm>
#include <unistd.h>
#include <android/log.h>
#include <sys/syscall.h>
#include <string>
#include <map>

#include <core.hpp>

#include "deny.hpp"

using namespace std;

struct logger_entry {
    uint16_t len;
    uint16_t hdr_size;
    int32_t pid;
    uint32_t tid;
    uint32_t sec;
    uint32_t nsec;
    uint32_t lid;
    uint32_t uid;
};

#define LOGGER_ENTRY_MAX_LEN (5 * 1024)
struct log_msg {
    union [[gnu::aligned(4)]] {
        unsigned char buf[LOGGER_ENTRY_MAX_LEN + 1];
        struct logger_entry entry;
    };
};

struct AndroidLogEntry {
    time_t tv_sec;
    long tv_nsec;
    android_LogPriority priority;
    int32_t uid;
    int32_t pid;
    int32_t tid;
    const char *tag;
    size_t tagLen;
    size_t messageLen;
    const char *message;
};

struct [[gnu::packed]] android_event_header_t {
    int32_t tag;
};

struct [[gnu::packed]] android_event_int_t {
    int8_t type;
    int32_t data;
};

struct [[gnu::packed]] android_event_string_t {
    int8_t type;
    int32_t length;
    char data[];
};

struct [[gnu::packed]] android_event_list_t {
    int8_t type;
    int8_t element_count;
} ;


struct [[gnu::packed]] android_event_am_proc_start {
    android_event_header_t tag;
    android_event_list_t list;
    android_event_int_t user;
    android_event_int_t pid;
    android_event_int_t uid;
    android_event_string_t process_name;


};



extern "C" {

[[gnu::weak]] struct logger_list *android_logger_list_alloc(int mode, unsigned int tail, pid_t pid);
[[gnu::weak]] void android_logger_list_free(struct logger_list *list);
[[gnu::weak]] int android_logger_list_read(struct logger_list *list, struct log_msg *log_msg);
[[gnu::weak]] struct logger *android_logger_open(struct logger_list *list, log_id_t id);
[[gnu::weak]] int android_log_processLogBuffer(struct logger_entry *buf, AndroidLogEntry *entry);

}


static map<int, struct stat> zygote_map;
bool logcat_exit;

static int read_ns(const int pid, struct stat *st) {
    char path[32];
    sprintf(path, "/proc/%d/ns/mnt", pid);
    return stat(path, st);
}

static int parse_ppid(int pid) {
    char path[32];
    int ppid;
    sprintf(path, "/proc/%d/stat", pid);
    auto stat = open_file(path, "re");
    if (!stat) return -1;

    fscanf(stat.get(), "%*d %*s %*c %d", &ppid);
    return ppid;
}

static void check_zygote() {
    zygote_map.clear();
    int proc = open("/proc", O_RDONLY | O_CLOEXEC);
    auto proc_dir = xopen_dir(proc);
    if (!proc_dir) return;
    struct stat st{};
    for (dirent *entry; (entry = readdir(proc_dir.get()));) {
        int pid = parse_int(entry->d_name);
        if (pid <= 0) continue;
        if (fstatat(proc, entry->d_name, &st, 0)) continue;
        if (st.st_uid != 0) continue;
        if (proc_context_match(pid, "u:r:zygote:s0") && parse_ppid(pid) == 1) {
            if (read_ns(pid, &st) == 0) {
                zygote_map[pid] = st;
            }
        }
    }
}

static void process_main_buffer(struct log_msg *msg) {
    AndroidLogEntry entry{};
    if (android_log_processLogBuffer(&msg->entry, &entry) < 0) return;
    entry.tagLen--;
    auto tag = string_view(entry.tag, entry.tagLen);
    auto revert = [](int pid) {
        kill(pid, SIGSTOP);
        if (fork_dont_care() == 0) {
            revert_unmount(pid);
            kill(pid, SIGCONT);
            _exit(0);
        }
    };

    // Unlike app zygote, webview zygote UID is fixed. This means we don't have to
    // handle edge cases where apps print logs themselves and lead us into a honeycomb
    if (tag == "WebViewZygoteInit") {
        int pid = msg->entry.pid;
        if (entry.uid != WEBVIEW_ZYGOTE_UID || entry.message[0] != 'S') {
            return;
        }

        if (is_deny_target(WEBVIEW_ZYGOTE_UID, WEBVIEW_ZYGOTE_MAGIC)) {
            revert(pid);
        }
        return;
    }

    static bool ready = false;
    if (tag == "AppZygote") {
        if (entry.uid != 1000) return;
        if (entry.message[0] == 'S') {
            ready = true;
        } else {
            ready = false;
        }
        return;
    }

    if (!ready || tag != "AppZygoteInit") return;
    if (!proc_context_match(msg->entry.pid, "u:r:app_zygote:s0")) return;
    ready = false;

    char cmdline[1024];
    sprintf(cmdline, "/proc/%d/cmdline", msg->entry.pid);
    if (auto f = open_file(cmdline, "re")) {
        fgets(cmdline, sizeof(cmdline), f.get());
    } else {
        return;
    }

    if (!is_deny_target(entry.uid, cmdline)) {
        int pid = msg->entry.pid;
        revert(pid);
    }
}

static void process_events_buffer(struct log_msg *msg) {
    if (msg->entry.uid != 1000) return;
    auto event_data = &msg->buf[msg->entry.hdr_size];
    auto event_header = reinterpret_cast<const android_event_header_t *>(event_data);
    if (event_header->tag == 30014) {
        auto am_proc_start = reinterpret_cast<const android_event_am_proc_start *>(event_data);
        auto proc = string_view(am_proc_start->process_name.data,
                                am_proc_start->process_name.length);
        if (!is_deny_target(am_proc_start->uid.data, proc)) {
            int pid = am_proc_start->pid.data;
            if (fork_dont_care() == 0) {
                int ppid = parse_ppid(pid);
                auto it = zygote_map.find(ppid);
                if (it == zygote_map.end()) {
                    _exit(0);
                }

                char path[16];
                ssprintf(path, sizeof(path), "/proc/%d", pid);
                struct stat st{};
                int fd = syscall(__NR_pidfd_open, pid, 0);
                if (fd > 0 && setns(fd, CLONE_NEWNS) == 0) {
                    pid = getpid();
                } else {
                    close(fd);
                    fd = -1;
                }
                while (read_ns(pid, &st) == 0 && it->second.st_ino == st.st_ino) {
                    if (stat(path, &st) == 0 && st.st_uid == 0) {
                        usleep(10 * 1000);
                    } else {
                        _exit(0);
                    }
                    if (fd > 0) setns(fd, CLONE_NEWNS);
                }
                close(fd);

                revert_unmount(pid);
                _exit(0);
            }
        }
        return;
    }
    if (event_header->tag == 3040) {
        check_zygote();
    }
}

[[noreturn]] void run() {
    unsigned retry_delay = 1;
    while (true) {
        const unique_ptr<logger_list, decltype(&android_logger_list_free)> logger_list{
            android_logger_list_alloc(0, 1, 0), &android_logger_list_free};

        for (log_id id: {LOG_ID_MAIN, LOG_ID_EVENTS}) {
            auto *logger = android_logger_open(logger_list.get(), id);
            if (logger == nullptr) continue;
        }

        struct log_msg msg{};
        while (true) {
            if (!denylist_enforced) {
                break;
            }

            if (android_logger_list_read(logger_list.get(), &msg) <= 0) {
                break;
            }

            switch (msg.entry.lid) {
                case LOG_ID_EVENTS:
                    process_events_buffer(&msg);
                    break;
                case LOG_ID_MAIN:
                    process_main_buffer(&msg);
                default:
                    break;
            }
        }

        if (!denylist_enforced) {
            break;
        }




        sleep(retry_delay);
        retry_delay = std::min(retry_delay * 2, 5U);
    }

    pthread_exit(nullptr);
}

void *logcat(void *) {
    check_zygote();
    run();
}
