#include "../hooks.cpp"

#include <cstdio>
#include <malloc.h>
#include <string>

static int test_interfaces(ifaddrs **out) {
    struct Node { ifaddrs entry; char name[16]; };
    auto *hidden = static_cast<Node *>(calloc(1, sizeof(Node)));
    auto *visible = static_cast<Node *>(calloc(1, sizeof(Node)));
    if (!hidden || !visible) return -1;
    strcpy(hidden->name, "tun0");
    strcpy(visible->name, "wlan0");
    hidden->entry.ifa_name = hidden->name;
    visible->entry.ifa_name = visible->name;
    hidden->entry.ifa_next = &visible->entry;
    *out = &hidden->entry;
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    cloak::o_read = ::read;
    cloak::o_syscall = ::syscall;
    int failures = 0;
    const auto check = [&](bool passed, const char *name) {
        printf("%s: %s\n", name, passed ? "PASS" : "FAIL");
        failures += !passed;
    };
    for (const std::string input : {"adb\n", "orange\n", "userdebug\n",
                                   "[ro.debuggable]: [1]\n"}) {
        int fds[2];
        if (pipe(fds) != 0) return 2;
        write(fds[1], input.data(), input.size());
        close(fds[1]);
        char output[128] = {};
        const ssize_t count = cloak::h_read(fds[0], output, sizeof(output));
        close(fds[0]);
        check(count == static_cast<ssize_t>(input.size()) &&
              std::string(output, count > 0 ? count : 0) == input,
              "ordinary pipe bytes are preserved");
    }
    std::string path = std::string(argv[1]) + "/ordinary-file";
    int fd = open(path.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0600);
    if (fd < 0) return 2;
    const char input[] = "orange\n";
    write(fd, input, sizeof(input) - 1);
    lseek(fd, 0, SEEK_SET);
    char output[sizeof(input)] = {};
    const auto count = cloak::h_read(fd, output, sizeof(output));
    check(count == sizeof(input) - 1 && memcmp(output, input, count) == 0,
          "ordinary file bytes are preserved");
    close(fd);
    const auto truncate = [&](const char *target, long length) {
        return cloak::h_syscall(__NR_truncate,
                const_cast<char *>(target), reinterpret_cast<void *>(length),
                nullptr, nullptr, nullptr, nullptr);
    };
    check(truncate(path.c_str(), 3) == 0, "ordinary file truncation succeeds");
    struct stat info{};
    check(stat(path.c_str(), &info) == 0 && info.st_size == 3,
          "ordinary file truncation updates size");
    errno = 0;
    check(truncate("/data/adb/blocked-test", 0) == -1 && errno == ENOENT,
          "existing blocked path policy is preserved");
    unlink(path.c_str());
    cloak::o_getifaddrs = test_interfaces;
    const auto allocated_before = mallinfo().uordblks;
    for (int i = 0; i < 1000; ++i) {
        ifaddrs *interfaces = nullptr;
        if (cloak::h_getifaddrs(&interfaces) != 0) return 2;
        if (!interfaces || strcmp(interfaces->ifa_name, "wlan0") != 0 ||
                interfaces->ifa_next) return 2;
        freeifaddrs(interfaces);
    }
    check(mallinfo().uordblks <= allocated_before + 4096,
          "filtered interface allocations are released");
    return failures ? 1 : 0;
}
