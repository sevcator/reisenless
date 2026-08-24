#include "spoof.hpp"
#include "config.hpp"

#include <string>

namespace cloak {

static void set_str(JNIEnv *env, jclass cls, const char *field, const std::string &val) {
    if (!cls) return;
    jfieldID fid = env->GetStaticFieldID(cls, field, "Ljava/lang/String;");
    if (!fid) { env->ExceptionClear(); return; }
    jstring s = env->NewStringUTF(val.c_str());
    env->SetStaticObjectField(cls, fid, s);
    env->DeleteLocalRef(s);
}

void spoof_build(JNIEnv *env, const Config &cfg) {
    if (!env || cfg.gms_build.empty()) return;

    jclass build = env->FindClass("android/os/Build");
    if (!build) { env->ExceptionClear(); return; }
    jclass ver = env->FindClass("android/os/Build$VERSION");
    if (!ver) env->ExceptionClear();

    for (const auto &kv : cfg.gms_build) {
        const std::string &k = kv.first;
        const std::string &v = kv.second;
        if (k == "SECURITY_PATCH" || k == "INCREMENTAL") {
            set_str(env, ver, k.c_str(), v);
        } else if (k == "DEVICE_INITIAL_SDK_INT" || k == "SDK_INT" ||
                   k == "RELEASE") {




            continue;
        } else {
            set_str(env, build, k.c_str(), v);
        }
    }
    env->ExceptionClear();
    if (ver) env->DeleteLocalRef(ver);
    env->DeleteLocalRef(build);
}

void spoof_display(JNIEnv *env, const Config &cfg) {
    if (!env) return;
    auto it = cfg.gms_build.find("DISPLAY");
    if (it == cfg.gms_build.end() || it->second.empty()) it = cfg.gms_build.find("ID");
    if (it == cfg.gms_build.end() || it->second.empty()) return;
    jclass build = env->FindClass("android/os/Build");
    if (!build) { env->ExceptionClear(); return; }
    set_str(env, build, "DISPLAY", it->second);
    env->ExceptionClear();
    env->DeleteLocalRef(build);
}

void spoof_build_type(JNIEnv *env) {
    if (!env) return;
    jclass build = env->FindClass("android/os/Build");
    if (!build) { env->ExceptionClear(); return; }
    set_str(env, build, "TYPE", "user");
    set_str(env, build, "TAGS", "release-keys");
    env->ExceptionClear();
    env->DeleteLocalRef(build);
}

void spoof_rom_framework(JNIEnv *env, const Config &cfg) {
    if (!env || cfg.rom_keywords.empty()) return;
    jclass assets = env->FindClass("android/content/res/AssetManager");
    if (!assets) { env->ExceptionClear(); return; }
    set_str(env, assets, "LINEAGE_APK_PATH", "");
    env->ExceptionClear();
    env->DeleteLocalRef(assets);
}

}
