#include "hideapps.hpp"

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
    jobjectArray prefixes = env->NewObjectArray(1, string_class, nullptr);
    jstring all = env->NewStringUTF("L");
    env->SetObjectArrayElement(prefixes, 0, all);
    env->CallVoidMethod(runtime, set_exemptions, prefixes);
    clear_exception(env);
}

} // namespace

bool install(JNIEnv *env, const std::string &caller, const std::string &rule,
             const std::string &dex, const std::vector<std::string> &rom_keywords,
             bool integrity_target) {
    if (!env || caller.empty() || dex.empty() || (rule.empty() && !integrity_target)) {
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
    jstring class_name = env->NewStringUTF(
            "com.topjohnwu.reisenless.hideapps.PackageManagerProxy");
    auto proxy_class = static_cast<jclass>(
            env->CallObjectMethod(loader, load_class, class_name));
    if (!proxy_class) {
        clear_exception(env);
        return false;
    }
    if (clear_exception(env)) return false;

    jmethodID wrap_proxy = env->GetStaticMethodID(
            proxy_class, "wrap",
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/Object;");
    jstring caller_string = env->NewStringUTF(caller.c_str());
    jstring rule_string = env->NewStringUTF(rule.c_str());
    std::string joined_keywords;
    for (const auto &keyword : rom_keywords) {
        if (!joined_keywords.empty()) joined_keywords.push_back('\n');
        joined_keywords.append(keyword);
    }
    jstring package_keyword_string = env->NewStringUTF(joined_keywords.c_str());
    jobject proxy = wrap_proxy
            ? env->CallStaticObjectMethod(proxy_class, wrap_proxy, original,
                                          caller_string, rule_string, package_keyword_string,
                                          integrity_target ? JNI_TRUE : JNI_FALSE)
            : nullptr;
    if (!proxy) {
        clear_exception(env);
        return false;
    }
    if (clear_exception(env)) return false;

    env->SetStaticObjectField(activity_thread, pm_field, proxy);
    if (clear_exception(env)) return false;

    if (!rom_keywords.empty() || integrity_target) {
        jclass service_manager = env->FindClass("android/os/ServiceManager");
        jfieldID manager_field = service_manager
                ? env->GetStaticFieldID(service_manager, "sServiceManager",
                                        "Landroid/os/IServiceManager;")
                : nullptr;
        jobject manager = manager_field
                ? env->GetStaticObjectField(service_manager, manager_field)
                : nullptr;
        if (!manager && service_manager) {
            env->ExceptionClear();
            jmethodID get_manager = env->GetStaticMethodID(
                    service_manager, "getIServiceManager", "()Landroid/os/IServiceManager;");
            if (get_manager) manager = env->CallStaticObjectMethod(service_manager, get_manager);
        }
        if (manager && manager_field && !clear_exception(env)) {
            jmethodID wrap_services = env->GetStaticMethodID(
                    proxy_class, "wrapServiceManager",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/Object;");
            jstring service_caller = env->NewStringUTF(caller.c_str());
            jstring keyword_string = env->NewStringUTF(joined_keywords.c_str());
            jobject service_proxy = wrap_services
                    ? env->CallStaticObjectMethod(proxy_class, wrap_services,
                                                  manager, service_caller, keyword_string,
                                                  integrity_target ? JNI_TRUE : JNI_FALSE)
                    : nullptr;
            if (service_proxy && !clear_exception(env)) {
                env->SetStaticObjectField(service_manager, manager_field, service_proxy);
                clear_exception(env);
            } else {
                clear_exception(env);
            }
        } else {
            clear_exception(env);
        }
    }
    return true;
}

} // namespace hideapps
