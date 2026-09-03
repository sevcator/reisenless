#include <sys/mman.h>
#include <sys/mount.h>
#include <sys/resource.h>
#include <dlfcn.h>
#include <unwind.h>
#include <span>

#include <lsplt.hpp>

#include <base.hpp>

#include "zygisk.hpp"
#include "module.hpp"
#include "jni_hooks.hpp"

using namespace std;










































































constexpr const char *kZygoteInit = "com.android.internal.os.ZygoteInit";
constexpr const char *kZygote = "com/android/internal/os/Zygote";
constexpr const char *kForkApp = "nativeForkAndSpecialize";
constexpr const char *kSpecializeApp = "nativeSpecializeAppProcess";
constexpr const char *kForkServer = "nativeForkSystemServer";

using JNIMethods = std::span<JNINativeMethod>;
using JNIMethodsDyn = std::pair<unique_ptr<JNINativeMethod[]>, size_t>;

struct HookContext : JniHookDefinitions {

    vector<tuple<dev_t, ino_t, const char *, void **>> plt_backup;
    const NativeBridgeRuntimeCallbacks *runtime_callbacks = nullptr;
    void *self_handle = nullptr;
    bool should_unmap = false;
    // Guards against hooking the zygote JNI methods more than once. On lazy-native-bridge
    // devices both the post_native_bridge_load path and the strdup("ZygoteInit") trigger can
    // fire; a second pass corrupts the JNI registration (nulls fnPtrs) and unregisters
    // nativeForkSystemServer -> UnsatisfiedLinkError -> zygote dies.
    bool jni_hooked = false;

    void hook_plt();
    void hook_unloader();
    void restore_plt_hook();
    void hook_zygote_jni();
    void restore_zygote_hook(JNIEnv *env);
    void hook_jni_methods(JNIEnv *env, const char *clz, JNIMethods methods) const;
    void post_native_bridge_load(void *handle);

private:
    void register_hook(dev_t dev, ino_t inode, const char *symbol, void *new_func, void **old_func);
    int hook_jni_methods(JNIEnv *env, jclass clazz, JNIMethods methods) const;
    JNIMethodsDyn get_jni_methods(JNIEnv *env, jclass clazz) const;
};











ZygiskContext *g_ctx;
static HookContext *g_hook;

static JniHookDefinitions *get_defs() {
    return g_hook;
}



#define DCL_HOOK_FUNC(ret, func, ...) \
ret (*old_##func)(__VA_ARGS__);       \
ret new_##func(__VA_ARGS__)

DCL_HOOK_FUNC(static char *, strdup, const char * str) {
    // The runtime hands the "com.android.internal.os.ZygoteInit" class name to strdup at the correct
    // point (after the Zygote natives are (re)registered, before ZygoteInit#main forks), which is when
    // hook_zygote_jni() must arm. Match as a substring (rather than exact) so a wrapped/prefixed name
    // still triggers — harmless on standard devices, and it is what fires reliably on Meta Quest.
    if (str && strstr(str, kZygoteInit)) {
        g_hook->hook_zygote_jni();
    }
    return old_strdup(str);
}


DCL_HOOK_FUNC(int, fork) {
    return (g_ctx && g_ctx->pid >= 0) ? g_ctx->pid : old_fork();
}


DCL_HOOK_FUNC(static int, unshare, int flags) {
    int res = old_unshare(flags);
    if (g_ctx && (flags & CLONE_NEWNS) != 0 && res == 0) {
        if (g_ctx->flags & DO_REVERT_UNMOUNT) {
            revert_unmount();
        }

        errno = 0;
    }
    return res;
}


DCL_HOOK_FUNC(static int, selinux_android_setcontext,
              uid_t uid, bool isSystemServer, const char *seinfo, const char *pkgname) {
    return old_selinux_android_setcontext(uid, isSystemServer, seinfo, pkgname);
}


DCL_HOOK_FUNC(static void, android_log_close) {
    old_android_log_close();
}


DCL_HOOK_FUNC(static int, dlclose, void *handle) {
    if (!g_hook->self_handle) {
        ZLOGV("dlclose zygisk_loader\n");
        g_hook->post_native_bridge_load(handle);
    }
    return 0;
}




DCL_HOOK_FUNC(static int, pthread_attr_destroy, void *target) {
    int res = old_pthread_attr_destroy((pthread_attr_t *)target);


    if (gettid() != getpid())
        return res;

    ZLOGV("pthread_attr_destroy\n");
    if (g_hook->should_unmap) {
        g_hook->restore_plt_hook();
        if (g_hook->should_unmap) {
            ZLOGV("dlclosing self\n");
            void *self_handle = g_hook->self_handle;
            delete g_hook;




            [[clang::musttail]] return dlclose(self_handle);
        }
    }

    delete g_hook;
    return res;
}

#undef DCL_HOOK_FUNC



static size_t get_fd_max() {
    rlimit r{32768, 32768};
    getrlimit(RLIMIT_NOFILE, &r);
    return r.rlim_max;
}

ZygiskContext::ZygiskContext(JNIEnv *env, void *args) :
    env(env), args{args}, process(nullptr), pid(-1), flags(0), info_flags(0),
    allowed_fds(get_fd_max()), hook_info_lock(PTHREAD_MUTEX_INITIALIZER) { g_ctx = this; }

ZygiskContext::~ZygiskContext() {



    g_ctx = nullptr;

    if (!is_child())
        return;

    android_logging();


    for (auto &m : modules) {
        m.clearApi();
    }


    g_hook->should_unmap = true;
    g_hook->restore_zygote_hook(env);
    g_hook->hook_unloader();
}



inline void *unwind_get_region_start(_Unwind_Context *ctx) {
    auto fp = _Unwind_GetRegionStart(ctx);
#if defined(__arm__)


    auto pc = _Unwind_GetGR(ctx, 15);
    if (pc & 1) {

        fp |= 1;
    }
#endif
    return reinterpret_cast<void *>(fp);
}









static const NativeBridgeRuntimeCallbacks* find_runtime_callbacks(struct _Unwind_Context *ctx) {

    auto [start, end] = []()-> tuple<uintptr_t, uintptr_t> {
        for (const auto &map : lsplt::MapInfo::Scan()) {
            if (map.path.ends_with("/libart.so") && map.perms == (PROT_WRITE | PROT_READ)) {
                ZLOGV("libart.so: start=%p, end=%p\n",
                      reinterpret_cast<void *>(map.start), reinterpret_cast<void *>(map.end));
                return {map.start, map.end};
            }
        }
        return {0, 0};
    }();
#if defined(__aarch64__)

    for (int i = 19; i <= 28; ++i) {
        auto val = static_cast<uintptr_t>(_Unwind_GetGR(ctx, i));
        ZLOGV("r%d = %p\n", i, reinterpret_cast<void *>(val));
        if (val >= start && val < end)
            return reinterpret_cast<const NativeBridgeRuntimeCallbacks*>(val);
    }
#elif defined(__arm__)

    for (int i = 4; i <= 10; ++i) {
        auto val = static_cast<uintptr_t>(_Unwind_GetGR(ctx, i));
        ZLOGV("r%d = %p\n", i, reinterpret_cast<void *>(val));
        if (val >= start && val < end)
            return reinterpret_cast<const NativeBridgeRuntimeCallbacks*>(val);
    }
#elif defined(__i386__)

    auto ebp = static_cast<uintptr_t>(_Unwind_GetGR(ctx, 5));



    auto val = *reinterpret_cast<uintptr_t *>(ebp + 3 * sizeof(void *));
    ZLOGV("ebp + 3 * ptr_size = %p\n", reinterpret_cast<void *>(val));
    if (val >= start && val < end)
        return reinterpret_cast<const NativeBridgeRuntimeCallbacks*>(val);
#elif defined(__x86_64__)

    for (int i : {3, 15, 14, 13, 12}) {
        auto val = static_cast<uintptr_t>(_Unwind_GetGR(ctx, i));
        ZLOGV("r%d = %p\n", i, reinterpret_cast<void *>(val));
        if (val >= start && val < end)
            return reinterpret_cast<const NativeBridgeRuntimeCallbacks*>(val);
    }
#elif defined(__riscv)

    for (int i : {8, 9, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27}) {
        auto val = static_cast<uintptr_t>(_Unwind_GetGR(ctx, i));
        ZLOGV("x%d = %p\n", i, reinterpret_cast<void *>(val));
        if (val >= start && val < end)
            return reinterpret_cast<const NativeBridgeRuntimeCallbacks*>(val);
    }
#else
#error "Unsupported architecture"
#endif
    return nullptr;
}

void HookContext::post_native_bridge_load(void *handle) {
    self_handle = handle;
    using method_sig = const bool (*)(const char *, const NativeBridgeRuntimeCallbacks *);
    struct trace_arg {
        method_sig load_native_bridge;
        const NativeBridgeRuntimeCallbacks *callbacks;
    };
    trace_arg arg{};


    _Unwind_Backtrace(+[](_Unwind_Context *ctx, void *arg) -> _Unwind_Reason_Code {
        void *fp = unwind_get_region_start(ctx);
        Dl_info info{};
        dladdr(fp, &info);
        ZLOGV("backtrace: %p %s\n", fp, info.dli_fname ?: "???");
        if (info.dli_fname && std::string_view(info.dli_fname).ends_with("/libnativebridge.so")) {
            auto payload = reinterpret_cast<trace_arg *>(arg);
            payload->load_native_bridge = reinterpret_cast<method_sig>(fp);
            payload->callbacks = find_runtime_callbacks(ctx);
            ZLOGV("NativeBridgeRuntimeCallbacks: %p\n", payload->callbacks);
            return _URC_END_OF_STACK;
        }
        return _URC_NO_REASON;
    }, &arg);

    if (!arg.load_native_bridge || !arg.callbacks)
        return;


    auto nb = get_prop(NBPROP);
    auto len = sizeof(ZYGISKLDR) - 1;
    if (nb.size() > len) {
        arg.load_native_bridge(nb.c_str() + len, arg.callbacks);
    }
    runtime_callbacks = arg.callbacks;
    // NOTE: do NOT hook the zygote JNI methods here. The native bridge loads before the runtime
    // finishes registering (and later re-registers) the Zygote natives, so a hook installed now is
    // overwritten by the runtime and never takes effect. The strdup("com.android.internal.os.ZygoteInit")
    // PLT hook fires at the correct time (after registration, before ZygoteInit#main forks), and it
    // does fire on Meta Quest too, so let it arm hook_zygote_jni().
}



void HookContext::register_hook(
        dev_t dev, ino_t inode, const char *symbol, void *new_func, void **old_func) {
    if (!lsplt::RegisterHook(dev, inode, symbol, new_func, old_func)) {
        ZLOGE("Failed to register plt_hook \"%s\"\n", symbol);
        return;
    }
    plt_backup.emplace_back(dev, inode, symbol, old_func);
}

#define PLT_HOOK_REGISTER_SYM(DEV, INODE, SYM, NAME) \
    register_hook(DEV, INODE, SYM, \
    reinterpret_cast<void *>(new_##NAME), reinterpret_cast<void **>(&old_##NAME))

#define PLT_HOOK_REGISTER(DEV, INODE, NAME) \
    PLT_HOOK_REGISTER_SYM(DEV, INODE, #NAME, NAME)

void HookContext::hook_plt() {
    ino_t android_runtime_inode = 0;
    dev_t android_runtime_dev = 0;
    ino_t native_bridge_inode = 0;
    dev_t native_bridge_dev = 0;

    for (auto &map : lsplt::MapInfo::Scan()) {
        if (map.path.ends_with("/libandroid_runtime.so")) {
            android_runtime_inode = map.inode;
            android_runtime_dev = map.dev;
        } else if (map.path.ends_with("/libnativebridge.so")) {
            native_bridge_inode = map.inode;
            native_bridge_dev = map.dev;
        }
    }

    PLT_HOOK_REGISTER(native_bridge_dev, native_bridge_inode, dlclose);
    PLT_HOOK_REGISTER(android_runtime_dev, android_runtime_inode, fork);
    PLT_HOOK_REGISTER(android_runtime_dev, android_runtime_inode, unshare);
    PLT_HOOK_REGISTER(android_runtime_dev, android_runtime_inode, selinux_android_setcontext);
    PLT_HOOK_REGISTER(android_runtime_dev, android_runtime_inode, strdup);
    PLT_HOOK_REGISTER_SYM(android_runtime_dev, android_runtime_inode, "__android_log_close", android_log_close);

    if (!lsplt::CommitHook())
        ZLOGE("plt_hook failed\n");


    std::erase_if(plt_backup, [](auto &t) { return *std::get<3>(t) == nullptr; });
}

void HookContext::hook_unloader() {
    ino_t art_inode = 0;
    dev_t art_dev = 0;

    for (auto &map : lsplt::MapInfo::Scan()) {
        if (map.path.ends_with("/libart.so")) {
            art_inode = map.inode;
            art_dev = map.dev;
            break;
        }
    }

    PLT_HOOK_REGISTER(art_dev, art_inode, pthread_attr_destroy);
    if (!lsplt::CommitHook())
        ZLOGE("plt_hook failed\n");
}

void HookContext::restore_plt_hook() {

    for (const auto &[dev, inode, sym, old_func] : plt_backup) {
        if (!lsplt::RegisterHook(dev, inode, sym, *old_func, nullptr)) {
            ZLOGE("Failed to register plt_hook [%s]\n", sym);
            should_unmap = false;
        }
    }
    if (!lsplt::CommitHook()) {
        ZLOGE("Failed to restore plt_hook\n");
        should_unmap = false;
    }
}



JNIMethodsDyn HookContext::get_jni_methods(JNIEnv *env, jclass clazz) const {
    size_t total = runtime_callbacks->getNativeMethodCount(env, clazz);
    auto methods = std::make_unique_for_overwrite<JNINativeMethod[]>(total);
    runtime_callbacks->getNativeMethods(env, clazz, methods.get(), total);
    return std::make_pair(std::move(methods), total);
}

static void register_jni_methods(JNIEnv *env, jclass clazz, JNIMethods methods) {
    for (auto &method : methods) {

        if (!method.fnPtr) continue;


        if (env->RegisterNatives(clazz, &method, 1) == JNI_ERR || env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            method.fnPtr = nullptr;
        }
    }
}

int HookContext::hook_jni_methods(JNIEnv *env, jclass clazz, JNIMethods methods) const {

    auto o = get_jni_methods(env, clazz);
    const auto old_methods = span(o.first.get(), o.second);






    register_jni_methods(env, clazz, methods);


    auto n = get_jni_methods(env, clazz);
    const auto new_methods = span(n.first.get(), n.second);


    int hook_count = 0;
    for (auto &method : methods) {
        if (!method.fnPtr) continue;
        for (const auto &new_method : new_methods) {
            if (new_method.fnPtr == method.fnPtr) {
                for (const auto &old_method : old_methods) {
                    if (strcmp(old_method.name, new_method.name) == 0 &&
                        strcmp(old_method.signature, new_method.signature) == 0) {
                        ZLOGV("replace %s %s %p -> %p\n",
                            method.name, method.signature, old_method.fnPtr, method.fnPtr);
                        method.fnPtr = old_method.fnPtr;
                        ++hook_count;

                        goto next_method;
                    }
                }
            }
        }
        next_method:
    }
    return hook_count;
}


void HookContext::hook_jni_methods(JNIEnv *env, const char *clz, JNIMethods methods) const {
    jclass clazz;
    if (!runtime_callbacks || !env || !clz || !((clazz = env->FindClass(clz)))) {
        ranges::for_each(methods, [](auto &m) { m.fnPtr = nullptr; });
        return;
    }
    hook_jni_methods(env, clazz, methods);
}

void HookContext::hook_zygote_jni() {
    // Idempotent: only replace the zygote JNI methods once per process.
    if (jni_hooked) {
        return;
    }
    using method_sig = jint(*)(JavaVM **, jsize, jsize *);
    auto get_created_vms = reinterpret_cast<method_sig>(
            dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs"));
    if (!get_created_vms) {
        for (auto &map: lsplt::MapInfo::Scan()) {
            if (!map.path.ends_with("/libnativehelper.so")) continue;
            void *h = dlopen(map.path.data(), RTLD_LAZY);
            if (!h) {
                ZLOGW("Cannot dlopen libnativehelper.so: %s\n", dlerror());
                break;
            }
            get_created_vms = reinterpret_cast<method_sig>(dlsym(h, "JNI_GetCreatedJavaVMs"));
            dlclose(h);
            break;
        }
        if (!get_created_vms) {
            ZLOGW("JNI_GetCreatedJavaVMs not found\n");
            return;
        }
    }

    JavaVM *vm = nullptr;
    jsize num = 0;
    jint res = get_created_vms(&vm, 1, &num);
    if (res != JNI_OK || vm == nullptr) {
        ZLOGW("JavaVM not found\n");
        return;
    }
    JNIEnv *env = nullptr;
    res = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (res != JNI_OK || env == nullptr) {
        ZLOGW("JNIEnv not found\n");
        return;
    }

    // Contain every JNI local reference we create (FindClass, ExceptionOccurred, ...) in an
    // explicit frame. Depending on the exact caller/timing this may run outside a managed JNI
    // transition, and leaking locals trips ART's "non-empty local reference table" check -> abort.
    bool local_frame = env->PushLocalFrame(64) == JNI_OK;

    JNINativeMethod missing_method{};
    bool replaced_fork_app = false;
    bool replaced_specialize_app = false;
    bool replaced_fork_server = false;

    jclass clazz = env->FindClass(kZygote);
    auto [ptr, count] = get_jni_methods(env, clazz);
    for (const auto methods = span(ptr.get(), count); const auto &method : methods) {
        if (strcmp(method.name, kForkApp) == 0) {
            if (hook_jni_methods(env, clazz, fork_app_methods) == 0) {
                missing_method = method;
                break;
            }
            replaced_fork_app = true;
        } else if (strcmp(method.name, kSpecializeApp) == 0) {
            if (hook_jni_methods(env, clazz, specialize_app_methods) == 0) {
                missing_method = method;
                break;
            }
            replaced_specialize_app = true;
        } else if (strcmp(method.name, kForkServer) == 0) {
            if (hook_jni_methods(env, clazz, fork_server_methods) == 0) {
                missing_method = method;
                break;
            }
            replaced_fork_server = true;
        }
    }

    if (missing_method.name != nullptr) {
        ZLOGE("Cannot hook method: %s %s\n", missing_method.name, missing_method.signature);

        if (replaced_fork_app) register_jni_methods(env, clazz, fork_app_methods);
        if (replaced_specialize_app) register_jni_methods(env, clazz, specialize_app_methods);
        if (replaced_fork_server) register_jni_methods(env, clazz, fork_server_methods);

        ranges::for_each(fork_app_methods, [](auto &m) { m.fnPtr = nullptr; });
        ranges::for_each(specialize_app_methods, [](auto &m) { m.fnPtr = nullptr; });
        ranges::for_each(fork_server_methods, [](auto &m) { m.fnPtr = nullptr; });
    }
    // Only mark as hooked when the full set was replaced cleanly, so that a premature/failed
    // call does not permanently block a later well-timed trigger from installing the hooks.
    if (missing_method.name == nullptr && replaced_fork_app && replaced_specialize_app &&
        replaced_fork_server) {
        jni_hooked = true;
    }
    if (local_frame) env->PopLocalFrame(nullptr);
}

void HookContext::restore_zygote_hook(JNIEnv *env) {
    jclass clazz = env->FindClass(kZygote);
    register_jni_methods(env, clazz, fork_app_methods);
    register_jni_methods(env, clazz, specialize_app_methods);
    register_jni_methods(env, clazz, fork_server_methods);
}



void hook_entry() {
    default_new(g_hook);
    g_hook->hook_plt();
}

void hookJniNativeMethods(JNIEnv *env, const char *clz, JNINativeMethod *methods, int numMethods) {
    g_hook->hook_jni_methods(env, clz, { methods, static_cast<size_t>(numMethods) });
}
