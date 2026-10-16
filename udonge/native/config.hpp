#pragma once
#include <string>
#include <unordered_set>
#include <unordered_map>
#include <vector>

namespace cloak {


struct Config {
    std::string current_package;
    std::string manager_package;
    std::unordered_set<std::string> packages;
    std::unordered_set<std::string> stealth_packages;
    std::unordered_set<std::string> exempt_packages;
    std::unordered_set<std::string> pi_packages;
    std::unordered_map<std::string, std::string> props;
    std::unordered_map<std::string, std::string> pif_props;
    std::unordered_map<std::string, std::string> gms_build;
    std::vector<std::string> rom_keywords;


    bool shouldCloak(const std::string &pkg) const {
        if (pkg.empty()) return false;
        if (!manager_package.empty() && pkg == manager_package) return false;
        if (exempt_packages.count(pkg) != 0) return false;
        return packages.count("*") != 0 || packages.count(pkg) != 0;
    }


    bool shouldStealth(const std::string &pkg) const {
        return stealth_packages.count(pkg) != 0;
    }

    bool isPiTarget(const std::string &pkg) const {
        if (pkg.empty()) return false;
        if (pkg == "com.android.vending" || pkg == "com.google.android.gms" || pkg == "com.google.android.gsf") return true;
        return pi_packages.count(pkg) != 0;
    }

};



Config parse_config(const std::string &targets_text, const std::string &props_text,
                    const std::string &pif_text, const std::string &rom_keywords_text);



std::string read_file(const std::string &path);

}