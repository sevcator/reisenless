#pragma once
#include <sys/types.h>
#include "config.hpp"
#include "zygisk.hpp"

namespace cloak {



void hook_native_load(zygisk::Api *api, JNIEnv *env);






void install_hooks(zygisk::Api *api, const Config *cfg, bool props_only = false);

}
