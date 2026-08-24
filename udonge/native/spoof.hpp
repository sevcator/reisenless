#pragma once
#include <jni.h>
#include "config.hpp"

namespace cloak {



void spoof_build(JNIEnv *env, const Config &cfg);




void spoof_display(JNIEnv *env, const Config &cfg);





void spoof_build_type(JNIEnv *env);


void spoof_rom_framework(JNIEnv *env, const Config &cfg);

}
