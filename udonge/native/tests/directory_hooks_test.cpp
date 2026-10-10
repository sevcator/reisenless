#include <sys/types.h>
#include <unistd.h>
#include <cstring>
#include <cstdio>
#include <string>
#include <algorithm>

static int network_directory_fd = -1;
static ssize_t test_readlink(const char *path, char *buffer, size_t size) {
    char expected[64];
    snprintf(expected, sizeof(expected), "/proc/self/fd/%d", network_directory_fd);
    if (network_directory_fd >= 0 && strcmp(path, expected) == 0) {
        const char target[] = "/sys/class/net";
        const size_t length = std::min(size, sizeof(target) - 1);
        memcpy(buffer, target, length);
        return length;
    }
    return ::readlink(path, buffer, size);
}

#include "directory-hooks-source.cpp"

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    std::string root = std::string(argv[1]) + "/directory-fixture";
    if (mkdir(root.c_str(), 0700) != 0) return 2;
    const std::set<std::string> ordinary = {"tunnel.txt", "tap.txt", "wg.txt", "ppp-data", "wlan0"};
    for (const auto &name : ordinary) {
        int fd = open((root + "/" + name).c_str(), O_CREAT | O_WRONLY, 0600);
        if (fd < 0) return 2;
        close(fd);
    }
    int blocked = open((root + "/magisk-fixture").c_str(), O_CREAT | O_WRONLY, 0600);
    if (blocked < 0) return 2;
    close(blocked);
    cloak::o_readdir = ::readdir;
    cloak::o_readdir64 = ::readdir64;
    bool passed = true;
    for (bool use64 : {false, true}) {
        for (bool network : {false, true}) {
            DIR *directory = opendir(root.c_str());
            if (!directory) return 2;
            network_directory_fd = network ? dirfd(directory) : -1;
            std::set<std::string> found;
            for (;;) {
                const char *name;
                if (use64) {
                    auto *entry = cloak::h_readdir64(directory);
                    if (!entry) break;
                    name = entry->d_name;
                } else {
                    auto *entry = cloak::h_readdir(directory);
                    if (!entry) break;
                    name = entry->d_name;
                }
                if (strcmp(name, ".") != 0 && strcmp(name, "..") != 0) found.insert(name);
            }
            closedir(directory);
            const bool result = network ? found == std::set<std::string>{"wlan0"} : found == ordinary;
            printf("readdir%s %s preserves directory policy: %s\n", use64 ? "64" : "",
                   network ? "network directory" : "ordinary files", result ? "PASS" : "FAIL");
            passed = result && passed;
        }
    }
    network_directory_fd = -1;
    for (const auto &name : ordinary) unlink((root + "/" + name).c_str());
    unlink((root + "/magisk-fixture").c_str());
    rmdir(root.c_str());
    return passed ? 0 : 1;
}
