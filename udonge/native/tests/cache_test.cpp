#include "file_cache.hpp"
#include <cassert>
#include <fcntl.h>
#include <fstream>
#include <unistd.h>

int main(int argc, char **argv) {
    assert(argc == 2);
    const std::string path = std::string(argv[1]) + "/cache-input";
    int reads = 0;
    cloak::FileCache cache([&](const std::string &file) {
        ++reads;
        std::ifstream in(file, std::ios::binary);
        return std::string(std::istreambuf_iterator<char>(in), {});
    });
    auto write = [](const std::string &file, const char *value) { std::ofstream(file) << value; };
    write(path, "one");
    assert(cache.get(path) == "one");
    assert(cache.get(path) == "one" && reads == 1);
    write(path + ".new", "two");
    rename((path + ".new").c_str(), path.c_str());
    assert(cache.get(path) == "two" && reads == 2);
    struct stat previous{};
    assert(stat(path.c_str(), &previous) == 0);
    write(path, "six");
    struct timespec times[] = {previous.st_atim, previous.st_mtim};
    if (++times[1].tv_nsec == 1000000000) { ++times[1].tv_sec; times[1].tv_nsec = 0; }
    assert(utimensat(AT_FDCWD, path.c_str(), times, 0) == 0);
    assert(cache.get(path) == "six" && reads == 3);
    unlink(path.c_str());
    assert(cache.get(path).empty());
    write(path, "new");
    assert(cache.get(path) == "new");
    unlink(path.c_str());
    puts("file cache: unchanged, atomic replacement, same-size write, deletion, recreation passed");
}
