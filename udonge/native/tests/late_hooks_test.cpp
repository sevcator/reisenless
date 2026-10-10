#include <lsplt.hpp>
#include <string>
#include <vector>
#include <android/dlext.h>

static std::string library_path;
static std::vector<lsplt::MapInfo> test_scan() {
    std::vector<lsplt::MapInfo> maps;
    for (auto map : lsplt::MapInfo::Scan()) {
        if (map.path != library_path) continue;

        map.path = "/data/app/test-package/lib/arm64/liblate.so";
        maps.push_back(std::move(map));
    }
    return maps;
}

#include "late-hooks-source.cpp"

int main(int argc, char **argv) {
    if (argc < 2 || argc > 3) return 2;
    const bool check_next = argc == 3;
    const std::string root = argv[1];
    library_path = root + "/liblate.so";
    constexpr size_t reservation_size = 1 << 20;
    void *reservation = mmap(nullptr, reservation_size, PROT_NONE,
                             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (reservation == MAP_FAILED) return 2;
    android_dlextinfo load_info{};
    load_info.flags = ANDROID_DLEXT_RESERVED_ADDRESS;
    load_info.reserved_addr = reservation;
    load_info.reserved_size = reservation_size;
    void *library = android_dlopen_ext(library_path.c_str(),
            RTLD_NOW | (check_next ? RTLD_GLOBAL : 0), &load_info);
    if (!library) { puts(dlerror()); return 2; }
    auto probe = reinterpret_cast<int (*)(const char *)>(dlsym(library, "library_access"));
    if (!probe) return 2;
    int (*next_symbol)() = nullptr;
    if (check_next) {
        if (!dlopen((root + "/libnext.so").c_str(), RTLD_NOW | RTLD_GLOBAL)) return 2;
        next_symbol = reinterpret_cast<int (*)()>(dlsym(library, "library_next_symbol"));
        if (!next_symbol || next_symbol() != 2) return 2;
    }
    std::string blocked = root + "/magisk-fixture";
    std::string ordinary = root + "/ordinary-fixture";
    for (const auto &path : {blocked, ordinary}) {
        int fd = open(path.c_str(), O_CREAT | O_WRONLY, 0600);
        if (fd < 0) return 2;
        close(fd);
        if (probe(path.c_str()) != 0) return 2;
    }
    cloak::Config config;
    cloak::g_cfg = &config;
    cloak::install_late_library_hooks();
    errno = 0;
    bool passed = probe(blocked.c_str()) == -1 && errno == ENOENT
            && probe(ordinary.c_str()) == 0;
    printf("late extracted library receives existing PLT hook: %s\n", passed ? "PASS" : "FAIL");
    if (check_next) {
        const bool next_passed = next_symbol() == 2;
        printf("RTLD_NEXT preserves the library caller: %s\n", next_passed ? "PASS" : "FAIL");

        unlink(blocked.c_str());
        unlink(ordinary.c_str());
        return passed && next_passed ? 0 : 1;
    }
    unlink(blocked.c_str());
    unlink(ordinary.c_str());
    fflush(stdout);
    auto close_library = ::dlclose;
    for (const auto &hook : cloak::kHooks) {
        if (strcmp(hook.sym, "dlclose") == 0)
            close_library = reinterpret_cast<decltype(close_library)>(hook.hook);
    }
    cloak::o_android_dlopen_ext = reinterpret_cast<decltype(cloak::o_android_dlopen_ext)>(
            ::android_dlopen_ext);
    const void *first_probe = reinterpret_cast<void *>(probe);
    int reused = 0;
    for (int i = 0; i < 5; ++i) {
        if (close_library(library) != 0) return 2;
        if (mmap(reservation, reservation_size, PROT_NONE,
                 MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0) != reservation) return 2;
        library = cloak::h_android_dlopen_ext(library_path.c_str(), RTLD_NOW, &load_info);
        if (!library) return 2;
        probe = reinterpret_cast<int (*)(const char *)>(dlsym(library, "library_access"));
        if (!probe) return 2;
        reused += first_probe == reinterpret_cast<void *>(probe);
        int fd = open(blocked.c_str(), O_CREAT | O_WRONLY, 0600);
        if (fd < 0) return 2;
        close(fd);
        errno = 0;
        passed = probe(blocked.c_str()) == -1 && errno == ENOENT && passed;
        unlink(blocked.c_str());
    }
    printf("late library unload/reload preserves hooks (%d reused bases): %s\n",
           reused, passed ? "PASS" : "FAIL");
    fflush(stdout);
    if (close_library(library) != 0) return 2;
    munmap(reservation, reservation_size);
    if (reused != 5) return 2;
    return passed ? 0 : 1;
}
