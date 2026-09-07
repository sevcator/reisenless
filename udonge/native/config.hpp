#pragma once
#include <string>
#include <unordered_set>
#include <unordered_map>

namespace cloak {


struct Config {
    std::unordered_set<std::string> packages;
    std::unordered_set<std::string> stealth_packages;
    std::unordered_map<std::string, std::string> props;
    std::unordered_map<std::string, std::string> gms_build;


    bool shouldCloak(const std::string &pkg) const {
        return packages.count(pkg) != 0;
    }


    bool shouldStealth(const std::string &pkg) const {
        return stealth_packages.count(pkg) != 0;
    }

};



Config parse_config(const std::string &targets_text, const std::string &props_text,
                    const std::string &pif_text);


std::string read_file(const std::string &path);

}
