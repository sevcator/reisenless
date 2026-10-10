#pragma once
#include <jni.h>
#include "config.hpp"

namespace cloak {

void spoof_build(JNIEnv *env, const Config &cfg, const std::string &pkg = "");

void spoof_display(JNIEnv *env, const Config &cfg);

void spoof_build_type(JNIEnv *env);

void spoof_custom_rom(JNIEnv *env);

}
