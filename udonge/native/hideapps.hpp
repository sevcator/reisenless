#pragma once

#include <jni.h>
#include <string>

namespace hideapps {

bool install(JNIEnv *env, const std::string &caller, const std::string &rule,
             const std::string &dex);

} // namespace hideapps
