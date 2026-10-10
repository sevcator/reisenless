#include "spoof.hpp"

#include <algorithm>
#include <cctype>
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

void spoof_build(JNIEnv *env, const Config &cfg, const std::string &pkg) {
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

    if (pkg == "com.android.vending" && ver) {
        auto it = cfg.gms_build.find("spoofVendingSdk");
        if (it != cfg.gms_build.end() && !it->second.empty()) {
            int target_sdk = std::atoi(it->second.c_str());
            if (target_sdk > 0) {
                jfieldID fid = env->GetStaticFieldID(ver, "SDK_INT", "I");
                if (fid) {
                    env->SetStaticIntField(ver, fid, target_sdk == 1 ? 32 : target_sdk);
                } else {
                    env->ExceptionClear();
                }
            }
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

void spoof_custom_rom(JNIEnv *env) {
    if (!env) return;
    jclass asset_mgr = env->FindClass("android/content/res/AssetManager");
    if (asset_mgr) {
        jfieldID fid = env->GetStaticFieldID(asset_mgr, "LINEAGE_APK_PATH", "Ljava/lang/String;");
        if (fid) {
            env->SetStaticObjectField(asset_mgr, fid, nullptr);
        } else {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(asset_mgr);
    }
    env->ExceptionClear();

    jclass lin_build = env->FindClass("lineageos/os/Build");
    if (lin_build) {
        static const char *const kLineageFields[] = {
            "LINEAGE_VERSION", "LINEAGE_DISPLAY_VERSION", "UNKNOWN"
        };
        for (const char *fname : kLineageFields) {
            jfieldID fid = env->GetStaticFieldID(lin_build, fname, "Ljava/lang/String;");
            if (fid) {
                env->SetStaticObjectField(lin_build, fid, nullptr);
            } else {
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(lin_build);
    }
    env->ExceptionClear();
}

}
