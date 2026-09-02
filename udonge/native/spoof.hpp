#pragma once
#include <jni.h>
#include "config.hpp"

namespace cloak {

// Overwrite android.os.Build / Build.VERSION fields in the current process with
// the certified-device values from cfg.gms_build (via JNI SetStaticObjectField).
void spoof_build(JNIEnv *env, const Config &cfg);

// Overwrite only Build.DISPLAY using the DISPLAY (or ID) value from cfg.gms_build.
// Targeted variant for cloaked non-GMS apps — avoids changing MODEL/BRAND/etc.
// which could break payment apps that validate device identity.
void spoof_display(JNIEnv *env, const Config &cfg);

// Overwrite Build.TYPE → "user" and Build.TAGS → "release-keys" unconditionally.
// Called for all cloak targets to fix the Build constant cross-check that Duck
// Detector performs against the fingerprint tail. The property hook covers the
// native/reflection path; this covers the static Java constant.
void spoof_build_type(JNIEnv *env);

// Clear ROM-added framework constants exposed through Java reflection.
void spoof_rom_framework(JNIEnv *env, const Config &cfg);

} // namespace cloak
