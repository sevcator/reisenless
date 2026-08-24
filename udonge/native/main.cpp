#include <sys/types.h>
#include "zygisk.hpp"
#include "config.hpp"
#include "hideapps.hpp"
#include "hooks.hpp"
#include "spoof.hpp"

#include <cerrno>
#include <cstdint>
#include <string>
#include <sys/socket.h>
#include <unistd.h>

using zygisk::Api;
using zygisk::AppSpecializeArgs;

namespace {

bool xwrite(int fd, const void *buf, size_t len) {
    auto *cursor = static_cast<const uint8_t *>(buf);
    while (len) {
        ssize_t count = write(fd, cursor, len);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        cursor += count;
        len -= static_cast<size_t>(count);
    }
    return true;
}

bool xread(int fd, void *buf, size_t len) {
    auto *cursor = static_cast<uint8_t *>(buf);
    while (len) {
        ssize_t count = read(fd, cursor, len);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        cursor += count;
        len -= static_cast<size_t>(count);
    }
    return true;
}

bool write_str(int fd, const std::string &value) {
    uint32_t size = static_cast<uint32_t>(value.size());
    return xwrite(fd, &size, sizeof(size)) && xwrite(fd, value.data(), size);
}

bool read_str(int fd, std::string &value) {
    uint32_t size = 0;
    if (!xread(fd, &size, sizeof(size)) || size > (16u << 20)) return false;
    value.resize(size);
    return size == 0 || xread(fd, value.data(), size);
}

#ifndef UDONGE_ROOT
#define UDONGE_ROOT "/data/adb/udonge"
#endif

const char *CONF_DIR = UDONGE_ROOT "/state";
std::string base_package(const std::string &process_name) {
    size_t separator = process_name.find(':');
    return process_name.substr(0, separator);
}

std::string tab_field(const std::string &line, size_t index) {
    size_t start = 0;
    for (size_t current = 0; current < index; ++current) {
        start = line.find('\t', start);
        if (start == std::string::npos) return {};
        ++start;
    }
    size_t end = line.find('\t', start);
    return line.substr(start, end == std::string::npos ? std::string::npos : end - start);
}

bool contains_package(const std::string &packages, const std::string &package) {
    size_t start = 0;
    while (start <= packages.size()) {
        size_t end = packages.find(',', start);
        size_t length = end == std::string::npos ? packages.size() - start : end - start;
        if (packages.compare(start, length, package) == 0 && package.size() == length) {
            return true;
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return false;
}

std::string find_hide_rule(const std::string &config, const std::string &package) {
    const std::string prefix = "R\t" + package + "\t";
    std::string global;
    size_t start = 0;
    while (start < config.size()) {
        size_t end = config.find('\n', start);
        std::string line = config.substr(
                start, end == std::string::npos ? std::string::npos : end - start);
        if (line.rfind(prefix, 0) == 0 && tab_field(line, 2) != "G") return line;
        if (line.rfind("G\t", 0) == 0) global = line;
        if (end == std::string::npos) break;
        start = end + 1;
    }
    if (global.empty()) return {};

    const std::string manager = tab_field(global, 1);
    const std::string hidden = tab_field(global, 2);
    const std::string exempt = tab_field(global, 3);
    if (hidden.empty() || package == manager || contains_package(exempt, package)) return {};
    return "R\t" + package + "\tB\t0\t" + manager + "\t" + hidden + "\t";
}

} // namespace

class UdongeModule : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(AppSpecializeArgs *args) override {
        cloak_ = false;
        hide_apps_ = false;
        is_gms_unstable_ = false;
        keep_loaded_ = false;
        hide_rule_.clear();
        hide_dex_.clear();

        // Child zygotes (notably WebView's sandbox zygote) must stay pristine.
        // Installing process-local Java hooks here prevents the child zygote
        // from publishing its command socket, so every WebView network process
        // fails to start with ECONNREFUSED.
        if (args->is_child_zygote && *args->is_child_zygote) return;

        std::string package_name = jstr(args->nice_name);
        if (package_name.empty()) return;
        std::string package = base_package(package_name);
        is_gms_unstable_ = package_name == "com.google.android.gms.unstable";
        if (!fetch_config(package_name)) return;

        hide_apps_ = !hide_rule_.empty() && !hide_dex_.empty();

        if (is_gms_unstable_) return;
        // Cloak/stealth candidacy comes from the live targets configuration.
        if (cfg_.shouldStealth(package)) {
            api_->setOption(zygisk::FORCE_DENYLIST_UNMOUNT);
            return;
        }
        if (cfg_.shouldCloak(package)) {
            cloak_ = true;
            keep_loaded_ = true;
            api_->setOption(zygisk::FORCE_DENYLIST_UNMOUNT);
        }
    }

    void postAppSpecialize(const AppSpecializeArgs *) override {
        if (is_gms_unstable_) {
            cloak::spoof_build(env_, cfg_);
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        if (hide_apps_) {
            hideapps::install(env_, package_, hide_rule_, hide_dex_);
        }
        if (cloak_) {
            cloak::install_hooks(api_, &cfg_);
            cloak::spoof_display(env_, cfg_);
            // Patch Build.TYPE and Build.TAGS static constants so Java-level
            // cross-checks (Build.TYPE vs fingerprint tail) see clean values.
            cloak::spoof_build_type(env_);
            cloak::spoof_rom_framework(env_, cfg_);
        }
        if (!keep_loaded_) api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

private:
    Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    cloak::Config cfg_;
    std::string package_;
    std::string hide_rule_;
    std::string hide_dex_;
    bool cloak_ = false;
    bool hide_apps_ = false;
    bool is_gms_unstable_ = false;
    bool keep_loaded_ = false;

    std::string jstr(jstring value) {
        if (!value) return {};
        const char *chars = env_->GetStringUTFChars(value, nullptr);
        std::string result = chars ? chars : "";
        if (chars) env_->ReleaseStringUTFChars(value, chars);
        return result;
    }

    bool fetch_config(const std::string &process_name) {
        package_ = base_package(process_name);
        int fd = api_->connectCompanion();
        std::string targets;
        std::string props;
        std::string pif;
        std::string rom_keywords;
        bool from_companion = false;
        if (fd >= 0) {
            uint8_t request = 1;
            bool ok = xwrite(fd, &request, 1)
                && write_str(fd, process_name)
                && read_str(fd, targets)
                && read_str(fd, props)
                && read_str(fd, pif)
                && read_str(fd, hide_rule_)
                && read_str(fd, hide_dex_)
                && read_str(fd, rom_keywords);
            close(fd);
            if (!ok) {
                targets.clear();
                props.clear();
                pif.clear();
                hide_rule_.clear();
                hide_dex_.clear();
                rom_keywords.clear();
            } else {
                from_companion = true;
            }
        }
        if (!from_companion) {
            targets = cloak::read_file(std::string(CONF_DIR) + "/targets.conf");
            props = cloak::read_file(std::string(CONF_DIR) + "/props.conf");
            pif = cloak::read_file(std::string(CONF_DIR) + "/pif.conf");
            rom_keywords = cloak::read_file(std::string(CONF_DIR) + "/rom_keywords.conf");
        }
        cfg_ = cloak::parse_config(targets, props, pif, rom_keywords);
        if (is_gms_unstable_) return !cfg_.gms_build.empty();
        if (!cfg_.shouldCloak(package_) && !cfg_.shouldStealth(package_)) {
            cfg_.packages.insert(package_);
        }
        return true;
    }
};

static void companion_handler(int client) {
    uint8_t request = 0;
    if (!xread(client, &request, 1)) return;
    std::string process_name;
    if (!read_str(client, process_name)) return;
    const std::string package = base_package(process_name);
    std::string targets = cloak::read_file(std::string(CONF_DIR) + "/targets.conf");
    const cloak::Config target_config = cloak::parse_config(targets, {}, {}, {});
    const bool gms_unstable = process_name == "com.google.android.gms.unstable";
    const bool needs_props = gms_unstable || target_config.shouldCloak(package);
    std::string props;
    std::string pif;
    if (needs_props) {
        props = cloak::read_file(std::string(CONF_DIR) + "/props.conf");
        pif = cloak::read_file(std::string(CONF_DIR) + "/pif.conf");
    }
    std::string hide_config = cloak::read_file(std::string(CONF_DIR) + "/hideapps.conf");
    std::string hide_rule;
    if (!gms_unstable) hide_rule = find_hide_rule(hide_config, package);
    std::string hide_dex;
    if (!hide_rule.empty()) {
        hide_dex = cloak::read_file(UDONGE_ROOT "/runtime/hideapps.dex");
    }
    std::string rom_keywords = cloak::read_file(std::string(CONF_DIR) + "/rom_keywords.conf");
    write_str(client, targets);
    write_str(client, props);
    write_str(client, pif);
    write_str(client, hide_rule);
    write_str(client, hide_dex);
    write_str(client, rom_keywords);
}

REGISTER_ZYGISK_MODULE(UdongeModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
