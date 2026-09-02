#pragma once

#include <jni.h>
#include <string>
#include <vector>

namespace hideapps {

bool install(JNIEnv *env, const std::string &caller, const std::string &rule,
             const std::string &dex, const std::vector<std::string> &rom_keywords,
             bool integrity_target);

} // namespace hideapps
