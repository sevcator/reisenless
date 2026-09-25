package com.topjohnwu.reisenless.hideapps;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.Process;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Process-local IPackageManager filter for exact configured package identities. */
public final class PackageManagerProxy implements InvocationHandler {
    private static final String PACKAGE_DESCRIPTOR = "android.content.pm.IPackageManager";
    private static final Set<String> NEVER_HIDE = new HashSet<>();
    private static final Set<String> PACKAGE_ARGUMENT_METHODS = new HashSet<>(Arrays.asList(
            "hasSigningCertificate",
            "isInstantApp",
            "getInstantAppCookie",
            "clearInstantAppCookie",
            "updateInstantAppCookie",
            "getTargetSdkVersion",
            "getSplashScreenTheme",
            "setSplashScreenTheme",
            "getAppMetadataFd",
            "getArchivedPackage",
            "isAutoRevokeWhitelisted",
            "getSharedLibraries",
            "getDeclaredSharedLibraries",
            "getMimeGroup",
            "setMimeGroup",
            "getProperty"
    ));

    static {
        NEVER_HIDE.add("android");
        NEVER_HIDE.add("android.media");
        NEVER_HIDE.add("android.uid.system");
        NEVER_HIDE.add("android.uid.shell");
        NEVER_HIDE.add("android.uid.systemui");
        NEVER_HIDE.add("com.android.permissioncontroller");
        NEVER_HIDE.add("com.android.providers.downloads");
        NEVER_HIDE.add("com.android.providers.downloads.ui");
        NEVER_HIDE.add("com.android.providers.media");
        NEVER_HIDE.add("com.android.providers.media.module");
        NEVER_HIDE.add("com.android.providers.settings");
        NEVER_HIDE.add("com.google.android.providers.media.module");
        NEVER_HIDE.add("com.google.android.webview");
    }

    private final Object delegate;
    private final String caller;
    private final boolean whitelist;
    private final boolean excludeSystem;
    private final String manager;
    private final Set<String> selected;
    private final Set<String> systemPackages;

    private PackageManagerProxy(Object delegate, String caller, String rule) {
        this.delegate = delegate;
        this.caller = caller;
        String[] fields = rule.split("\\t", -1);
        whitelist = fields.length > 2 && "W".equals(fields[2]);
        excludeSystem = fields.length > 3 && "1".equals(fields[3]);
        manager = fields.length > 4 ? fields[4] : "";
        selected = splitPackages(fields.length > 5 ? fields[5] : "");
        systemPackages = splitPackages(fields.length > 6 ? fields[6] : "");
    }

    public static Object wrap(Object delegate, String caller, String rule) {
        if (delegate == null || caller == null || isSystemProcess(caller)
                || rule == null || rule.isEmpty()) return delegate;
        Class<?>[] interfaces = delegate.getClass().getInterfaces();
        if (interfaces.length == 0) return delegate;
        return Proxy.newProxyInstance(PackageManagerProxy.class.getClassLoader(), interfaces,
                new PackageManagerProxy(delegate, caller, rule));
    }

    private static volatile Object sWrappedPackageManager = null;

    public static void installFrameworkCaches(Object packageManager) {
        installFrameworkCaches(packageManager, null);
    }

    public static void installFrameworkCaches(Object packageManager, String caller) {
        if (caller != null && isSystemProcess(caller)) {
            return;
        }
        if (Process.myUid() % 100000 < 10000) {
            return;
        }
        if (packageManager != null) {
            sWrappedPackageManager = packageManager;
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                setStaticField(activityThread, "sPackageManager", packageManager);
                Object thread = invokeStatic(activityThread, "currentActivityThread");
                if (thread != null) installContextCache(invoke(thread, "getSystemContext"), packageManager);
                installContextCache(invokeStatic(activityThread, "currentApplication"), packageManager);
            } catch (ReflectiveOperationException ignored) {
                // Framework layouts differ by release; each cache is best effort.
            }
        }

        try {
            Class<?> assetManager = Class.forName("android.content.res.AssetManager");
            Field field = assetManager.getDeclaredField("LINEAGE_APK_PATH");
            field.setAccessible(true);
            try {
                field.set(null, null);
            } catch (Throwable e) {
                try {
                    Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                    Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                    theUnsafe.setAccessible(true);
                    Object unsafe = theUnsafe.get(null);
                    Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
                    Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
                    Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
                    Object base = staticFieldBase.invoke(unsafe, field);
                    long offset = ((Number) staticFieldOffset.invoke(unsafe, field)).longValue();
                    putObject.invoke(unsafe, base, offset, null);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Field cacheField = serviceManager.getDeclaredField("sCache");
            cacheField.setAccessible(true);
            Object value = cacheField.get(null);
            if (value instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, IBinder> cache = (Map<String, IBinder>) value;
                cache.entrySet().removeIf(entry -> isLineageService(entry.getKey()));
                if (packageManager != null) {
                    IBinder binder = cache.get("package");
                    if (binder != null && !Proxy.isProxyClass(binder.getClass())) {
                        cache.put("package", wrapBinder(binder, packageManager));
                    }
                }
            }

            Method getIServiceManager = serviceManager.getDeclaredMethod("getIServiceManager");
            getIServiceManager.setAccessible(true);
            Object sm = getIServiceManager.invoke(null);
            if (sm != null && !Proxy.isProxyClass(sm.getClass())) {
                Class<?> iServiceManagerClass = Class.forName("android.os.IServiceManager");
                Object smProxy = Proxy.newProxyInstance(
                        PackageManagerProxy.class.getClassLoader(),
                        new Class<?>[]{iServiceManagerClass},
                        new ServiceManagerProxy(sm));
                Field sSmField = serviceManager.getDeclaredField("sServiceManager");
                sSmField.setAccessible(true);
                sSmField.set(null, smProxy);
            }
        } catch (Throwable ignored) {
            // ActivityThread remains protected if ServiceManager internals change.
        }
    }

    private static boolean isLineageService(Object key) {
        if (!(key instanceof String)) return false;
        String name = (String) key;
        return name.contains("lineage") || "profile".equals(name);
    }

    private static final class ServiceManagerProxy implements InvocationHandler {
        private final Object delegate;

        ServiceManagerProxy(Object delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (("getService".equals(name) || "checkService".equals(name)) && args != null && args.length > 0) {
                if (isLineageService(args[0])) {
                    return null;
                }
                if ("package".equals(args[0]) && sWrappedPackageManager != null) {
                    try {
                        Object binder = method.invoke(delegate, args);
                        if (binder instanceof IBinder) {
                            return wrapBinder((IBinder) binder, sWrappedPackageManager);
                        }
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
            if ("getService2".equals(name) && args != null && args.length > 0) {
                if (isLineageService(args[0])) {
                    try {
                        return method.invoke(delegate, new Object[]{"_reisenless_none"});
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
            if ("listServices".equals(name)) {
                try {
                    Object result = method.invoke(delegate, args);
                    if (result instanceof String[]) {
                        String[] services = (String[]) result;
                        List<String> filtered = new ArrayList<>(services.length);
                        for (String s : services) {
                            if (!isLineageService(s)) filtered.add(s);
                        }
                        return filtered.toArray(new String[0]);
                    }
                    return result;
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
            if ("isDeclared".equals(name) && args != null && args.length > 0) {
                if (isLineageService(args[0])) {
                    return false;
                }
            }
            if ("getDeclaredInstances".equals(name) && args != null && args.length > 0) {
                if (isLineageService(args[0])) {
                    return new String[0];
                }
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static IBinder wrapBinder(IBinder delegate, Object packageManager) {
        return (IBinder) Proxy.newProxyInstance(PackageManagerProxy.class.getClassLoader(),
                new Class<?>[]{IBinder.class}, new PackageBinderProxy(delegate, packageManager));
    }

    private static final class PackageBinderProxy implements InvocationHandler {
        private final IBinder delegate;
        private final Object packageManager;

        PackageBinderProxy(IBinder delegate, Object packageManager) {
            this.delegate = delegate;
            this.packageManager = packageManager;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("queryLocalInterface".equals(method.getName()) && args != null
                    && args.length == 1 && PACKAGE_DESCRIPTOR.equals(args[0])) {
                return packageManager;
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static void installContextCache(Object context, Object packageManager)
            throws ReflectiveOperationException {
        if (!(context instanceof Context)) return;
        Object local = ((Context) context).getPackageManager();
        if (local == null) return;
        Field field = local.getClass().getDeclaredField("mPM");
        field.setAccessible(true);
        field.set(local, packageManager);
    }

    private static void setStaticField(Class<?> type, String name, Object value)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static Object invokeStatic(Class<?> type, String name)
            throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(null);
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        if (target == null) return null;
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static boolean isSystemProcess(String caller) {
        if (Process.myUid() % 100000 < 10000) return true;
        if (caller == null || caller.isEmpty()) return false;
        return "android".equals(caller)
                || "system".equals(caller)
                || caller.contains("systemui")
                || caller.startsWith("com.android.server.");
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("asBinder".equals(name)) {
            try {
                Object binder = method.invoke(delegate, args);
                if (binder instanceof IBinder) {
                    return wrapBinder((IBinder) binder, proxy);
                }
                return binder;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
        if (hasHiddenExplicitPackage(name, args)) return hiddenValue(method.getReturnType(), name);
        final Object result;
        try {
            result = method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
        filterOutParameters(name, args);
        return filter(result, name);
    }

    private void filterOutParameters(String method, Object[] args) {
        if (!"querySyncProviders".equals(method) || args == null) return;
        for (Object arg : args) {
            if (!(arg instanceof List<?>)) continue;
            try {
                ((List<?>) arg).removeIf(item -> shouldHide(packageNameOf(item, true)));
            } catch (UnsupportedOperationException ignored) {
                // Unknown immutable framework implementation.
            }
        }
    }

    private Object filter(Object value, String method) {
        if (value == null) return null;
        boolean stringsArePackages = method.contains("Package")
                || method.contains("Installer") || method.contains("InstallSource")
                || "getNameForUid".equals(method);
        String packageName = packageNameOf(value, stringsArePackages);
        if (packageName != null) return shouldHide(packageName) ? null : value;

        if (value instanceof String[]) {
            if (!stringsArePackages) return value;
            String[] input = (String[]) value;
            List<String> output = new ArrayList<>(input.length);
            for (String item : input) if (!shouldHide(item)) output.add(item);
            return output.toArray(new String[0]);
        }
        if (value instanceof List<?>) return filterList((List<?>) value, stringsArePackages);
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<Object, Object> output = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ((stringsArePackages && shouldHide(String.valueOf(entry.getKey())))
                        || shouldHide(packageNameOf(entry.getValue(), false))) continue;
                output.put(entry.getKey(), entry.getValue());
            }
            return output;
        }
        if (value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()) {
            int length = Array.getLength(value);
            List<Object> output = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                Object item = Array.get(value, i);
                if (!shouldHide(packageNameOf(item, stringsArePackages))) output.add(item);
            }
            Object array = Array.newInstance(value.getClass().getComponentType(), output.size());
            for (int i = 0; i < output.size(); i++) Array.set(array, i, output.get(i));
            return array;
        }
        if (value.getClass().getName().endsWith("ParceledListSlice")) {
            try {
                Method getList = value.getClass().getMethod("getList");
                Object list = getList.invoke(value);
                if (list instanceof List<?>) {
                    List<?> filtered = filterList((List<?>) list, stringsArePackages);
                    try {
                        return value.getClass().getConstructor(List.class).newInstance(filtered);
                    } catch (ReflectiveOperationException ignored) {
                        ((List<?>) list).removeIf(
                                item -> shouldHide(packageNameOf(item, stringsArePackages)));
                    }
                }
            } catch (ReflectiveOperationException | UnsupportedOperationException ignored) {
                // Unknown framework revision: leave the original result intact.
            }
        }
        return value;
    }

    private List<?> filterList(List<?> input, boolean stringsArePackages) {
        List<Object> output = new ArrayList<>(input.size());
        for (Object item : input) {
            if (!shouldHide(packageNameOf(item, stringsArePackages))) output.add(item);
        }
        return output;
    }

    private static final byte[] ENC_PKG_A = new byte[]{33, 45, 47, 108, 54, 45, 50, 40, 45, 42, 44, 53, 55, 108, 47, 35, 37, 43, 49, 41};
    private static final byte[] ENC_PKG_B = new byte[]{43, 45, 108, 37, 43, 54, 42, 55, 32, 108, 52, 52, 32, 112, 114, 116, 114, 108, 47, 35, 37, 43, 49, 41};
    private static final String PKG_A = decodeToken(ENC_PKG_A);
    private static final String PKG_B = decodeToken(ENC_PKG_B);

    private static String decodeToken(byte[] enc) {
        char[] chars = new char[enc.length];
        for (int i = 0; i < enc.length; i++) {
            chars[i] = (char) (enc[i] ^ 0x42);
        }
        return new String(chars);
    }

    private static boolean isRomOrRootPackage(String target) {
        if (target == null || target.isEmpty()) return false;
        if (target.startsWith("org.lineageos.") || target.startsWith("lineageos.")) return true;
        if (target.startsWith("org.protonaosp.")) return true;
        if (target.startsWith("co.aospa.")) return true;
        if (target.startsWith("com.crdroid.")) return true;
        if (target.startsWith("org.omnirom.")) return true;
        if (target.startsWith("io.chaldeaprjkt.")) return true;
        if (target.startsWith("org.lsposed.")) return true;
        if (target.startsWith("org.meowcat.edxposed.")) return true;
        if ("io.va.exposed".equals(target)) return true;
        if (target.startsWith(PKG_A) || target.startsWith(PKG_B)) return true;
        if (target.startsWith("io.github.a13e300.") || target.startsWith("com.rifsxd.ksunext")) return true;
        if (target.startsWith("com.resukisu.") || target.startsWith("com.sukisu.")) return true;
        if (target.startsWith("com.tsng.hidemyapplist") || target.startsWith("com.tsng.pzyhrx.hma")) return true;
        if (target.startsWith("com.topmiaohan.hidebllist")) return true;
        return false;
    }

    private boolean shouldHide(String target) {
        if (target == null || target.isEmpty() || target.equals(caller)) return false;
        if (NEVER_HIDE.contains(target)) return false;
        if (target.equals(manager)) return true;
        if (isRomOrRootPackage(target)) return true;
        if (whitelist && excludeSystem && systemPackages.contains(target)) return false;
        return whitelist ? !selected.contains(target) : selected.contains(target);
    }

    private boolean hasHiddenExplicitPackage(String method, Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (arg instanceof ComponentName
                    && shouldHide(((ComponentName) arg).getPackageName())) return true;
        }
        if ("checkPermission".equals(method)) return shouldHide(stringAt(args, 1));
        if ("checkSignatures".equals(method)) {
            return shouldHide(stringAt(args, 0)) || shouldHide(stringAt(args, 1));
        }
        if (method.contains("Package") || method.contains("Application")
                || method.contains("Installer") || method.contains("InstallSource")
                || method.contains("Component") || method.startsWith("isPackage")
                || PACKAGE_ARGUMENT_METHODS.contains(method)) {
            for (Object arg : args) {
                if (arg instanceof String && shouldHide((String) arg)) return true;
            }
        }
        return false;
    }

    private static String stringAt(Object[] args, int index) {
        return index < args.length && args[index] instanceof String ? (String) args[index] : null;
    }

    private static Object hiddenValue(Class<?> type, String method) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) {
            if ("checkSignatures".equals(method)) return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            if ("checkPermission".equals(method)) return PackageManager.PERMISSION_DENIED;
            return method.contains("Uid") ? -1 : 0;
        }
        if (type == long.class) return -1L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    private static String packageNameOf(Object value, boolean allowString) {
        if (value == null) return null;
        if (allowString && value instanceof String) return (String) value;
        if (value instanceof ApplicationInfo) return ((ApplicationInfo) value).packageName;
        if (value instanceof PackageInfo) return ((PackageInfo) value).packageName;
        if (value instanceof ComponentInfo) return ((ComponentInfo) value).packageName;
        if (value instanceof ResolveInfo) {
            ResolveInfo info = (ResolveInfo) value;
            if (info.activityInfo != null) return info.activityInfo.packageName;
            if (info.serviceInfo != null) return info.serviceInfo.packageName;
            if (info.providerInfo != null) return info.providerInfo.packageName;
        }
        for (String fieldName : new String[]{"packageName", "initiatingPackageName",
                "installingPackageName", "originatingPackageName"}) {
            try {
                Object packageName = value.getClass().getField(fieldName).get(value);
                if (packageName instanceof String) return (String) packageName;
            } catch (ReflectiveOperationException ignored) {
                // Try the next framework-version field.
            }
        }
        return null;
    }

    private static Set<String> splitPackages(String value) {
        Set<String> packages = new HashSet<>();
        if (value.isEmpty()) return packages;
        for (String item : value.split(",")) if (!item.isEmpty()) packages.add(item);
        return packages;
    }
}
