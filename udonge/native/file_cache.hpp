#pragma once

#include <functional>
#include <string>
#include <unordered_map>
#include <sys/stat.h>

namespace cloak {

class FileCache {
    struct Stamp {
        struct stat value{};
        bool exists = false;
        explicit Stamp(const std::string &path) : exists(stat(path.c_str(), &value) == 0) {}
        bool operator==(const Stamp &other) const {
            if (exists != other.exists) return false;
            if (!exists) return true;
            const auto &a = value;
            const auto &b = other.value;
            return a.st_dev == b.st_dev && a.st_ino == b.st_ino && a.st_size == b.st_size
                && a.st_mode == b.st_mode && a.st_uid == b.st_uid && a.st_gid == b.st_gid
                && a.st_mtim.tv_sec == b.st_mtim.tv_sec && a.st_mtim.tv_nsec == b.st_mtim.tv_nsec
                && a.st_ctim.tv_sec == b.st_ctim.tv_sec && a.st_ctim.tv_nsec == b.st_ctim.tv_nsec;
        }
    };
    struct Entry {
        Stamp stamp;
        std::string value;
        bool valid = false;
        explicit Entry(const std::string &path) : stamp(path) {}
    };
    std::unordered_map<std::string, Entry> files_;
    std::function<std::string(const std::string &)> read_;

public:
    explicit FileCache(std::function<std::string(const std::string &)> read) : read_(std::move(read)) {}
    const std::string &get(const std::string &path) {
        auto [it, inserted] = files_.try_emplace(path, path);
        auto &entry = it->second;
        Stamp before(path);
        if (entry.valid && entry.stamp == before) return entry.value;

        for (int attempt = 0; attempt != 2; ++attempt) {
            entry.value = before.exists ? read_(path) : std::string();
            Stamp after(path);
            entry.valid = before == after;
            entry.stamp = after;
            if (entry.valid) break;
            before = after;
        }
        return entry.value;
    }
};

}
