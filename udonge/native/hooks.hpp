#pragma once
#include <sys/types.h>   // dev_t / ino_t used by zygisk.hpp
#include "config.hpp"
#include "zygisk.hpp"

namespace cloak {

// Hook Runtime.nativeLoad before specialization so every app JNI library gets
// its PLT hooks immediately after loading and before Java can call into it.
void hook_native_load(zygisk::Api *api, JNIEnv *env);

// Install libc PLT hooks for the current process using the Zygisk API.
// `cfg` must outlive the process (store it statically).
// If `props_only` is true, only property hooks are installed (safe for early-
// JIT processes like android.vending where full file hooks trigger Zygisk's
// pthread_create cleanup path → libzygisk.so destructor crash).
void install_hooks(zygisk::Api *api, const Config *cfg, bool props_only = false);

} // namespace cloak
