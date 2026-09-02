#pragma once
#include <sys/types.h>   // dev_t / ino_t used by zygisk.hpp
#include "config.hpp"
#include "zygisk.hpp"

namespace cloak {

enum class HookProfile {
    Full,
    PropertiesOnly,
    SelinuxOnly,
};

// Hook Runtime.nativeLoad before specialization so every app JNI library gets
// its PLT hooks immediately after loading and before Java can call into it.
void hook_native_load(zygisk::Api *api, JNIEnv *env);

// Keep detector-facing policy queries inside Udonge. This narrow hook is safe
// to retain in a targeted app zygote without installing process-wide PLT hooks.
void hook_selinux_access(zygisk::Api *api, JNIEnv *env);

// Install the selected PLT-hook profile for the current process. The SELinux
// profile exists for detector preload carriers that must otherwise stay
// pristine; full target processes receive all Udonge concealment hooks.
void install_hooks(zygisk::Api *api, const Config *cfg,
                   HookProfile profile = HookProfile::Full);

} // namespace cloak
