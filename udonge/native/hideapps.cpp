#include "hideapps.hpp"

#ifndef HIDEAPPS_CLASS_NAME
#define HIDEAPPS_CLASS_NAME "com.topjohnwu.reisenless.hideapps"
#endif

namespace hideapps {
namespace {

bool clear_exception(JNIEnv *env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionClear();
    return true;
}

void exempt_hidden_apis(JNIEnv *env) {
    jclass vm_class = env->FindClass("dalvik/system/VMRuntime");
    if (!vm_class) {
        clear_exception(env);
        return;
    }
    jmethodID get_runtime = env->GetStaticMethodID(
            vm_class, "getRuntime", "()Ldalvik/system/VMRuntime;");
    jmethodID set_exemptions = env->GetMethodID(
            vm_class, "setHiddenApiExemptions", "([Ljava/lang/String;)V");
    if (!get_runtime || !set_exemptions) {
        clear_exception(env);
        return;
    }
    if (clear_exception(env)) return;

    jobject runtime = env->CallStaticObjectMethod(vm_class, get_runtime);
    jclass string_class = env->FindClass("java/lang/String");
    static constexpr const char *kPrefixes[] = {
        "Landroid/app/ActivityThread;",
        "Landroid/app/ApplicationPackageManager;",
        "Landroid/content/pm/ParceledListSlice;",
        "Landroid/os/ServiceManager;",
    };
    jobjectArray prefixes = env->NewObjectArray(
            sizeof(kPrefixes) / sizeof(kPrefixes[0]), string_class, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(sizeof(kPrefixes) / sizeof(kPrefixes[0])); ++i) {
        jstring prefix = env->NewStringUTF(kPrefixes[i]);
        env->SetObjectArrayElement(prefixes, i, prefix);
        env->DeleteLocalRef(prefix);
    }
    env->CallVoidMethod(runtime, set_exemptions, prefixes);
    clear_exception(env);
}

} // namespace

bool install(JNIEnv *env, const std::string &caller, const std::string &rule,
             const std::string &dex) {
    if (!env || caller.empty() || dex.empty() || rule.empty()) {
        return false;
    }
    exempt_hidden_apis(env);

    jclass activity_thread = env->FindClass("android/app/ActivityThread");
    jfieldID pm_field = activity_thread
            ? env->GetStaticFieldID(activity_thread, "sPackageManager",
                                    "Landroid/content/pm/IPackageManager;")
            : nullptr;
    jobject original = pm_field ? env->GetStaticObjectField(activity_thread, pm_field) : nullptr;
    if (!original && activity_thread) {
        env->ExceptionClear();
        jmethodID get_package_manager = env->GetStaticMethodID(
                activity_thread, "getPackageManager",
                "()Landroid/content/pm/IPackageManager;");
        if (get_package_manager) {
            original = env->CallStaticObjectMethod(activity_thread, get_package_manager);
        }
    }
    if (!original) {
        clear_exception(env);
        return false;
    }
    if (clear_exception(env)) return false;

    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(dex.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(dex.size()),
                            reinterpret_cast<const jbyte *>(dex.data()));

    jclass byte_buffer = env->FindClass("java/nio/ByteBuffer");
    jmethodID wrap = byte_buffer
            ? env->GetStaticMethodID(byte_buffer, "wrap", "([B)Ljava/nio/ByteBuffer;")
            : nullptr;
    jobject buffer = wrap ? env->CallStaticObjectMethod(byte_buffer, wrap, bytes) : nullptr;

    jclass class_loader = env->FindClass("java/lang/ClassLoader");
    jmethodID get_system = class_loader
            ? env->GetStaticMethodID(class_loader, "getSystemClassLoader",
                                     "()Ljava/lang/ClassLoader;")
            : nullptr;
    jobject parent = get_system ? env->CallStaticObjectMethod(class_loader, get_system) : nullptr;

    jclass memory_loader = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    jmethodID loader_ctor = memory_loader
            ? env->GetMethodID(memory_loader, "<init>",
                               "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V")
            : nullptr;
    jobject loader = loader_ctor
            ? env->NewObject(memory_loader, loader_ctor, buffer, parent)
            : nullptr;
    if (!loader) {
        clear_exception(env);
        return false;
    }
    if (clear_exception(env)) return false;

    jmethodID load_class = env->GetMethodID(
            class_loader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring class_name = env->NewStringUTF(HIDEAPPS_CLASS_NAME ".PackageManagerProxy");
    auto proxy_class = static_cast<jclass>(
            env->CallObjectMethod(loader, load_class, class_name));
    if (!proxy_class) {
        clear_exception(env);
        return false;
    }
    if (clear_exception(env)) return false;

    jmethodID wrap_proxy = env->GetStaticMethodID(
            proxy_class, "wrap",
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;");
    jstring caller_string = env->NewStringUTF(caller.c_str());
    jstring rule_string = env->NewStringUTF(rule.c_str());
    jobject proxy = wrap_proxy
            ? env->CallStaticObjectMethod(proxy_class, wrap_proxy, original,
                                          caller_string, rule_string)
            : nullptr;
    if (!proxy) {
        clear_exception(env);
        return false;
    }
    if (clear_exception(env)) return false;

    jmethodID install_caches = env->GetStaticMethodID(
            proxy_class, "installFrameworkCaches", "(Ljava/lang/Object;)V");
    if (!install_caches) {
        clear_exception(env);
        return false;
    }
    env->CallStaticVoidMethod(proxy_class, install_caches, proxy);
    if (clear_exception(env)) return false;
    return true;
}

} // namespace hideapps
