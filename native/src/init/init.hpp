#pragma once

#include "../../out/generated/flags.h"

#define DEFAULT_DT_DIR "/proc/device-tree/firmware/android"
#define REDIR_PATH BUILD_REDIR_PATH

#define PRELOAD_LIB    BUILD_PRELOAD_LIB
#define PRELOAD_POLICY BUILD_PRELOAD_POLICY
#define PRELOAD_ACK    BUILD_PRELOAD_ACK

#ifdef __cplusplus

#include <base.hpp>
#include <sepolicy.hpp>

using kv_pairs = std::vector<std::pair<std::string, std::string>>;

#include "init-rs.hpp"

int magisk_proxy_main(int, char *argv[]);
Utf8CStr backup_init();



static inline Utf8CStr split_plat_cil() {
    return SPLIT_PLAT_CIL;
};

static inline Utf8CStr preload_lib() {
    return PRELOAD_LIB;
}

static inline Utf8CStr preload_policy() {
    return PRELOAD_POLICY;
}

static inline Utf8CStr preload_ack() {
    return PRELOAD_ACK;
}


#endif
