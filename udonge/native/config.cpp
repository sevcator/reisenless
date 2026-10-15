#include "config.hpp"

#include <cstdio>
#include <string>

namespace cloak {

static std::string trim(const std::string &s) {
    size_t a = s.find_first_not_of(" \t\r\n");
    if (a == std::string::npos) return "";
    size_t b = s.find_last_not_of(" \t\r\n");
    return s.substr(a, b - a + 1);
}


std::string read_file(const std::string &path) {
    FILE *f = fopen(path.c_str(), "re");
    if (!f) return "";
    std::string out;
    char buf[4096];
    size_t n;
    while ((n = fread(buf, 1, sizeof buf, f)) > 0) out.append(buf, n);
    fclose(f);
    return out;
}


template <class F>
static void for_each_line(const std::string &text, F fn) {
    size_t i = 0;
    while (i < text.size()) {
        size_t nl = text.find('\n', i);
        std::string line = text.substr(i, nl == std::string::npos ? std::string::npos : nl - i);
        i = (nl == std::string::npos) ? text.size() : nl + 1;
        line = trim(line);
        if (line.empty() || line[0] == '#') continue;
        fn(line);
    }
}

Config parse_config(const std::string &targets_text, const std::string &props_text,
                    const std::string &pif_text, const std::string &rom_keywords_text) {
    Config cfg;

    for_each_line(targets_text, [&](const std::string &line) {
        std::string pkg = line;
        bool is_stealth = false;
        bool is_exempt = false;
        if (pkg.rfind("stealth:", 0) == 0) {
            pkg = pkg.substr(8);
            is_stealth = true;
        } else if (pkg.rfind("exempt:", 0) == 0) {
            pkg = pkg.substr(7);
            is_exempt = true;
        }
        while (!pkg.empty() && (pkg.back() == '!' || pkg.back() == '?')) {
            pkg.pop_back();
        }
        pkg = trim(pkg);
        if (pkg.empty()) return;
        if (is_exempt) {
            cfg.exempt_packages.insert(pkg);
        } else if (is_stealth) {
            cfg.stealth_packages.insert(pkg);
        } else {
            cfg.packages.insert(pkg);
        }
    });

    for_each_line(pif_text, [&](const std::string &line) {
        size_t eq = line.find('=');
        if (eq == std::string::npos) return;
        std::string k = trim(line.substr(0, eq));
        std::string v = trim(line.substr(eq + 1));
        if (!k.empty()) {
            cfg.gms_build[k] = v;
            if (k == "FINGERPRINT") {
                cfg.props["ro.build.fingerprint"] = v;
                cfg.props["ro.system.build.fingerprint"] = v;
                cfg.props["ro.vendor.build.fingerprint"] = v;
                cfg.props["ro.product.build.fingerprint"] = v;
                cfg.props["ro.system_ext.build.fingerprint"] = v;
                cfg.props["ro.odm.build.fingerprint"] = v;
                cfg.props["ro.bootimage.build.fingerprint"] = v;
            } else if (k == "BRAND") {
                cfg.props["ro.product.brand"] = v;
                cfg.props["ro.product.system.brand"] = v;
                cfg.props["ro.product.vendor.brand"] = v;
                cfg.props["ro.product.product.brand"] = v;
                cfg.props["ro.product.system_ext.brand"] = v;
                cfg.props["ro.product.odm.brand"] = v;
            } else if (k == "DEVICE") {
                cfg.props["ro.product.device"] = v;
                cfg.props["ro.product.system.device"] = v;
                cfg.props["ro.product.vendor.device"] = v;
                cfg.props["ro.product.product.device"] = v;
                cfg.props["ro.product.system_ext.device"] = v;
                cfg.props["ro.product.odm.device"] = v;
                cfg.props["ro.build.product"] = v;
                cfg.props["ro.product.board"] = v;
            } else if (k == "BOARD") {
                cfg.props["ro.product.board"] = v;
            } else if (k == "MANUFACTURER") {
                cfg.props["ro.product.manufacturer"] = v;
                cfg.props["ro.product.system.manufacturer"] = v;
                cfg.props["ro.product.vendor.manufacturer"] = v;
                cfg.props["ro.product.product.manufacturer"] = v;
                cfg.props["ro.product.system_ext.manufacturer"] = v;
                cfg.props["ro.product.odm.manufacturer"] = v;
            } else if (k == "MODEL") {
                cfg.props["ro.product.model"] = v;
                cfg.props["ro.product.system.model"] = v;
                cfg.props["ro.product.vendor.model"] = v;
                cfg.props["ro.product.product.model"] = v;
                cfg.props["ro.product.system_ext.model"] = v;
                cfg.props["ro.product.odm.model"] = v;
            } else if (k == "PRODUCT") {
                cfg.props["ro.product.name"] = v;
                cfg.props["ro.product.system.name"] = v;
                cfg.props["ro.product.vendor.name"] = v;
                cfg.props["ro.product.product.name"] = v;
                cfg.props["ro.product.system_ext.name"] = v;
                cfg.props["ro.product.odm.name"] = v;
            } else if (k == "ID") {
                cfg.props["ro.build.id"] = v;
                cfg.props["ro.build.display.id"] = v;
                cfg.props["ro.system.build.id"] = v;
                cfg.props["ro.vendor.build.id"] = v;
                cfg.props["ro.product.build.id"] = v;
                cfg.props["ro.system_ext.build.id"] = v;
                cfg.props["ro.odm.build.id"] = v;
            } else if (k == "SECURITY_PATCH") {
                cfg.props["ro.build.version.security_patch"] = v;
                cfg.props["ro.vendor.build.security_patch"] = v;
                cfg.props["ro.system.build.version.security_patch"] = v;
                cfg.props["ro.product.build.version.security_patch"] = v;
                cfg.props["ro.system_ext.build.version.security_patch"] = v;
                cfg.props["ro.bootimage.build.version.security_patch"] = v;
            } else if (k == "DEVICE_INITIAL_SDK_INT") {
                cfg.props["ro.product.first_api_level"] = v;
                cfg.props["ro.boot.first_api_level"] = v;
                cfg.props["ro.board.first_api_level"] = v;
                cfg.props["ro.board.api_level"] = v;
            } else if (k == "RELEASE") {
                cfg.props["ro.build.version.release"] = v;
                cfg.props["ro.system.build.version.release"] = v;
                cfg.props["ro.vendor.build.version.release"] = v;
                cfg.props["ro.product.build.version.release"] = v;
                cfg.props["ro.system_ext.build.version.release"] = v;
                cfg.props["ro.odm.build.version.release"] = v;
                cfg.props["ro.build.version.release_or_codename"] = v;
            } else if (k == "SDK_INT") {
                cfg.props["ro.build.version.sdk"] = v;
                cfg.props["ro.system.build.version.sdk"] = v;
                cfg.props["ro.vendor.build.version.sdk"] = v;
                cfg.props["ro.product.build.version.sdk"] = v;
                cfg.props["ro.system_ext.build.version.sdk"] = v;
                cfg.props["ro.odm.build.version.sdk"] = v;
            } else if (k == "INCREMENTAL") {
                cfg.props["ro.build.version.incremental"] = v;
            } else if (k == "TYPE") {
                cfg.props["ro.build.type"] = v;
                cfg.props["ro.system.build.type"] = v;
                cfg.props["ro.vendor.build.type"] = v;
                cfg.props["ro.product.build.type"] = v;
                cfg.props["ro.system_ext.build.type"] = v;
                cfg.props["ro.odm.build.type"] = v;
            } else if (k == "TAGS") {
                cfg.props["ro.build.tags"] = v;
                cfg.props["ro.system.build.tags"] = v;
                cfg.props["ro.vendor.build.tags"] = v;
                cfg.props["ro.product.build.tags"] = v;
                cfg.props["ro.system_ext.build.tags"] = v;
                cfg.props["ro.odm.build.tags"] = v;
            }
        }
    });

    for_each_line(props_text, [&](const std::string &line) {
        size_t eq = line.find('=');
        if (eq == std::string::npos) return;
        std::string k = trim(line.substr(0, eq));
        std::string v = trim(line.substr(eq + 1));
        if (!k.empty()) cfg.props[k] = v;
    });


    for_each_line(rom_keywords_text, [&](const std::string &line) {
        if (line.size() >= 3) cfg.rom_keywords.push_back(line);
    });

    return cfg;
}

}
