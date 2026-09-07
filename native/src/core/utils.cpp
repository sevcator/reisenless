#include <csignal>
#include <libgen.h>
#include <sys/mount.h>
#include <sys/sysmacros.h>
#include <linux/input.h>
#include <map>

#include <consts.hpp>
#include <base.hpp>
#include <core.hpp>

using namespace std;

bool read_string(int fd, std::string &str) {
    str.clear();
    int len = read_int(fd);
    str.resize(len);
    return xxread(fd, str.data(), len) == len;
}

string read_string(int fd) {
    string str;
    read_string(fd, str);
    return str;
}

void write_string(int fd, string_view str) {
    if (fd < 0) return;
    write_int(fd, str.size());
    xwrite(fd, str.data(), str.size());
}

const char *get_magisk_tmp() {
    static const char *path = nullptr;
    if (path == nullptr) {
        if (access("/debug_ramdisk/" INTLROOT, F_OK) == 0) {
            path = "/debug_ramdisk";
        } else if (access("/sbin/" INTLROOT, F_OK) == 0) {
            path = "/sbin";
        } else {
            path = "";
        }
    }
    return path;
}

static string runtime_alias(string_view role, string_view fallback) {
    char boot_id[64]{};
    owned_fd fd = open("/proc/sys/kernel/random/boot_id", O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return string(fallback);
    ssize_t len = read(fd, boot_id, sizeof(boot_id) - 1);
    if (len <= 0)
        return string(fallback);
    while (len > 0 && (boot_id[len - 1] == '\n' || boot_id[len - 1] == '\r'))
        --len;

    uint64_t hash = 1469598103934665603ULL;
    auto mix = [&hash](string_view value) {
        for (unsigned char ch : value) {
            hash ^= ch;
            hash *= 1099511628211ULL;
        }
    };
    mix(BUILD_RUNTIME_SEED);
    mix(string_view(boot_id, len));
    mix(role);

    string result = ".";
    constexpr string_view alphabet = "abcdefghijklmnopqrstuvwxyz";
    for (int i = 0; i < 12; ++i) {
        hash ^= hash << 13;
        hash ^= hash >> 7;
        hash ^= hash << 17;
        result += alphabet[hash % alphabet.size()];
    }
    return result;
}

const char *get_runtime_socket() {
    static string value = string(DEVICEDIR) + "/" + runtime_alias("s", BUILD_SOCKET_NAME);
    return value.c_str();
}

const char *get_runtime_daemon_name() {
    static string value = runtime_alias("d", DAEMON_PROC_NAME);
    return value.c_str();
}

const char *get_runtime_su_name() {
    static string value = runtime_alias("u", "su");
    return value.c_str();
}

const char *get_runtime_zygisk_name() {
#if defined(__LP64__)
    static string value = runtime_alias("z64", ZYGISKD64);
#else
    static string value = runtime_alias("z32", ZYGISKD32);
#endif
    return value.c_str();
}

void unlock_blocks() {
    int fd, dev, OFF = 0;

    auto dir = xopen_dir("/dev/block");
    if (!dir)
        return;
    dev = dirfd(dir.get());

    for (dirent *entry; (entry = readdir(dir.get()));) {
        if (entry->d_type == DT_BLK) {
            if ((fd = openat(dev, entry->d_name, O_RDONLY | O_CLOEXEC)) < 0)
                continue;
            if (ioctl(fd, BLKROSET, &OFF) < 0)
                PLOGE("unlock %s", entry->d_name);
            close(fd);
        }
    }
}

#define test_bit(bit, array) (array[bit / 8] & (1 << (bit % 8)))

bool check_key_combo() {
    uint8_t bitmask[(KEY_MAX + 1) / 8];
    vector<owned_fd> events;
    constexpr char name[] = "/dev/.ev";


    for (int minor = 64; minor < 96; ++minor) {
        if (xmknod(name, S_IFCHR | 0444, makedev(13, minor)))
            continue;
        int fd = open(name, O_RDONLY | O_CLOEXEC);
        unlink(name);
        if (fd < 0)
            continue;
        memset(bitmask, 0, sizeof(bitmask));
        ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(bitmask)), bitmask);
        if (test_bit(KEY_VOLUMEDOWN, bitmask))
            events.emplace_back(fd);
        else
            close(fd);
    }
    if (events.empty())
        return false;


    for (int i = 0; i < 300; ++i) {
        bool pressed = false;
        for (int fd : events) {
            memset(bitmask, 0, sizeof(bitmask));
            ioctl(fd, EVIOCGKEY(sizeof(bitmask)), bitmask);
            if (test_bit(KEY_VOLUMEDOWN, bitmask)) {
                pressed = true;
                break;
            }
        }
        if (!pressed)
            return false;

        usleep(10000);
    }
    LOGD("KEY_VOLUMEDOWN detected: enter safe mode\n");
    return true;
}
