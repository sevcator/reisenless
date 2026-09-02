package com.topjohnwu.reisenless.hideapps;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.os.Process;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Process-local IPackageManager wrapper installed by Udonge's built-in Zygisk
 * runtime. This class deliberately uses framework-only APIs so the DEX
 * can be loaded directly into an app process without an Android dependency.
 */
public final class PackageManagerProxy implements InvocationHandler {
    private static final Set<String> NEVER_HIDE = new HashSet<>();
    private static final Set<String> ROM_PACKAGE_SIGNATURES = new HashSet<>();
    private static final Set<String> ROM_SERVICE_SIGNATURES = new HashSet<>();

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

        String[] romPackages = {
                "org.lineageos.jelly", "org.lineageos.aperture",
                "org.lineageos.recorder", "org.lineageos.etar",
                "org.lineageos.twelve", "org.lineageos.glimpse",
                "org.lineageos.updater", "org.lineageos.lineageparts",
                "org.lineageos.profiles", "org.lineageos.backgrounds",
                "org.lineageos.camelot", "org.lineageos.lineagesettings",
                "com.crdroid.settings", "com.crdroid.updater",
                "com.crdroid.ltpo.oplus", "co.aospa.sense",
                "co.aospa.dolby.oplus", "org.protonaosp.columbus",
                "org.protonaosp.deviceconfig", "org.omnirom.omnijaws",
                "org.omnirom.omnistyle", "io.chaldeaprjkt.gamespace"
        };
        for (String packageName : romPackages) ROM_PACKAGE_SIGNATURES.add(packageName);

        String[] romServices = {
                "lineageglobalactions", "lineagehardware", "lineagehealth",
                "lineagelivedisplay", "lineagetrust", "profile",
                "vendor.lineage.health.IChargingControl/default",
                "vendor.lineage.health.IFastCharge/default",
                "vendor.lineage.livedisplay.IPictureAdjustment/default",
                "vendor.lineage.touch.ITouchscreenGesture/default",
                "vendor.lineage.livedisplay.IDisplayModes/default"
        };
        for (String serviceName : romServices) {
            ROM_SERVICE_SIGNATURES.add(serviceName.toLowerCase(Locale.ROOT));
        }
    }

    private final Object delegate;
    private final String caller;
    private final String manager;
    private final boolean whitelist;
    private final boolean excludeSystem;
    private final boolean integrityTarget;
    private final List<String> romKeywords;
    private final Set<String> selected;
    private final Set<String> systemPackages;
    private static volatile boolean soterSuppressed;

    private PackageManagerProxy(Object delegate, String caller, String rule, String keywords,
                                boolean integrityTarget) {
        this.delegate = delegate;
        this.caller = caller;
        this.integrityTarget = integrityTarget;
        this.romKeywords = splitKeywords(keywords);

        String[] fields = rule.split("\\t", -1);
        this.whitelist = fields.length > 2 && "W".equals(fields[2]);
        this.excludeSystem = fields.length > 3 && "1".equals(fields[3]);
        this.manager = fields.length > 4 ? fields[4] : "";
        this.selected = splitPackages(fields.length > 5 ? fields[5] : "");
        this.systemPackages = splitPackages(fields.length > 6 ? fields[6] : "");
    }

    public static Object wrap(Object delegate, String caller, String rule, String keywords,
                              boolean integrityTarget) {
        if (delegate == null || caller == null || isSystemProcess(caller)
                || rule == null || (rule.isEmpty() && !integrityTarget)) {
            return delegate;
        }
        Class<?>[] interfaces = delegate.getClass().getInterfaces();
        if (interfaces.length == 0) return delegate;
        return Proxy.newProxyInstance(
                PackageManagerProxy.class.getClassLoader(),
                interfaces,
                new PackageManagerProxy(delegate, caller, rule, keywords, integrityTarget));
    }

    public static Object wrapServiceManager(Object delegate, String caller, String keywords,
                                            boolean integrityTarget) {
        if (delegate == null || caller == null || isSystemProcess(caller)
                || ((keywords == null || keywords.isEmpty()) && !integrityTarget)) return delegate;
        Class<?>[] interfaces = delegate.getClass().getInterfaces();
        if (interfaces.length == 0) return delegate;
        return Proxy.newProxyInstance(
                PackageManagerProxy.class.getClassLoader(),
                interfaces,
                new ServiceManagerFilter(delegate, keywords, integrityTarget));
    }

    private static boolean isSystemProcess(String caller) {
        return Process.myUid() % 100000 < 10000
                || "android".equals(caller)
                || caller.startsWith("android.")
                || caller.startsWith("com.android.")
                || caller.startsWith("com.google.android.")
                || caller.startsWith("vendor.");
    }

    private static final class ServiceManagerFilter implements InvocationHandler {
        private final Object delegate;
        private final List<String> keywords = new ArrayList<>();

        ServiceManagerFilter(Object delegate, String rawKeywords, boolean integrityTarget) {
            this.delegate = delegate;
            if (rawKeywords != null) {
                for (String item : rawKeywords.split("\\n")) {
                    item = item.trim().toLowerCase(Locale.ROOT);
                    if (item.length() >= 3) keywords.add(item);
                }
            }
            if (integrityTarget) keywords.add("soter");
        }

        private boolean shouldHide(String service) {
            if (service == null) return false;
            String lower = service.toLowerCase(Locale.ROOT);
            if (!keywords.isEmpty() && ROM_SERVICE_SIGNATURES.contains(lower)) return true;
            for (String keyword : keywords) if (lower.contains(keyword)) return true;
            return false;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (args != null && args.length > 0 && args[0] instanceof String
                    && shouldHide((String) args[0])) {
                return hiddenValue(method.getReturnType(), name);
            }
            final Object result;
            try {
                result = method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (result instanceof String[]) {
                String[] input = (String[]) result;
                List<String> output = new ArrayList<>(input.length);
                for (String service : input) if (!shouldHide(service)) output.add(service);
                return output.toArray(new String[0]);
            }
            return result;
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (integrityTarget) suppressSoter();
        if (hasHiddenExplicitPackage(method.getName(), args)) {
            return hiddenValue(method.getReturnType(), method.getName());
        }

        final Object result;
        try {
            result = method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
        return filter(result, method.getName());
    }

    private static void suppressSoter() {
        if (soterSuppressed) return;
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) return;
            Class<?> delegate = Class.forName(
                    "com.tencent.soter.core.model.SoterDelegate", true, loader);
            delegate.getMethod("onTriggerOOM").invoke(null);
            soterSuppressed = true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // The app ClassLoader may not be installed on the first framework
            // query. A later package-manager call retries after startup.
        }
    }

    private Object filter(Object value, String method) {
        if (value == null) return null;

        boolean stringsArePackages = method.contains("Package") || "getNameForUid".equals(method);
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
            try {
                map.entrySet().removeIf(entry -> (stringsArePackages
                        && shouldHide(String.valueOf(entry.getKey())))
                        || shouldHide(packageNameOf(entry.getValue(), false)));
            } catch (UnsupportedOperationException ignored) {
                // Framework returned an immutable map.
            }
            return value;
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

        // Android package queries commonly return ParceledListSlice. Hidden API
        // exemptions are installed natively before this handler is created.
        if (value.getClass().getName().endsWith("ParceledListSlice")) {
            try {
                Method getList = value.getClass().getMethod("getList");
                Object list = getList.invoke(value);
                if (list instanceof List<?>) {
                    List<?> filtered = filterList((List<?>) list, stringsArePackages);
                    try {
                        return value.getClass().getConstructor(List.class).newInstance(filtered);
                    } catch (ReflectiveOperationException ignored) {
                        try {
                            ((List<?>) list).removeIf(
                                    item -> shouldHide(packageNameOf(item, stringsArePackages)));
                        } catch (UnsupportedOperationException ignoredAgain) {
                            // Unknown immutable framework implementation.
                        }
                    }
                }
            } catch (ReflectiveOperationException ignored) {
                // Unknown framework revision: leave the original result intact.
            }
        }
        return value;
    }

    private List<?> filterList(List<?> input, boolean stringsArePackages) {
        List<Object> output = new ArrayList<>(input.size());
        for (Object item : input) {
            String packageName = packageNameOf(item, stringsArePackages);
            if (!shouldHide(packageName)) output.add(item);
        }
        return output;
    }

    private boolean shouldHide(String target) {
        if (target == null || target.isEmpty() || target.equals(caller)) {
            return false;
        }
        if (integrityTarget && "com.tencent.soter.soterserver".equals(target)) return true;
        if (integrityTarget && !romKeywords.isEmpty()) {
            if (ROM_PACKAGE_SIGNATURES.contains(target)) return true;
            String lower = target.toLowerCase(Locale.ROOT);
            for (String keyword : romKeywords) {
                if (lower.contains(keyword)) return true;
            }
        }
        if (NEVER_HIDE.contains(target)) return false;
        if (whitelist && excludeSystem && systemPackages.contains(target)) return false;
        return whitelist ? !selected.contains(target) : selected.contains(target);
    }

    private boolean hasHiddenExplicitPackage(String method, Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (arg instanceof ComponentName
                    && shouldHide(((ComponentName) arg).getPackageName())) return true;
        }

        if ("checkPermission".equals(method)) {
            return shouldHide(stringAt(args, 1));
        }
        if ("checkSignatures".equals(method)) {
            return shouldHide(stringAt(args, 0)) || shouldHide(stringAt(args, 1));
        }
        if (method.contains("Package") || method.contains("Application")
                || method.contains("Installer") || method.startsWith("isPackage")) {
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
        if (type == int.class) return method.contains("Uid") ? -1 : 0;
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
        try {
            Object packageName = value.getClass().getField("packageName").get(value);
            return packageName instanceof String ? (String) packageName : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Set<String> splitPackages(String value) {
        Set<String> packages = new HashSet<>();
        if (value.isEmpty()) return packages;
        for (String item : value.split(",")) if (!item.isEmpty()) packages.add(item);
        return packages;
    }

    private static List<String> splitKeywords(String value) {
        List<String> keywords = new ArrayList<>();
        if (value == null) return keywords;
        for (String item : value.split("\\n")) {
            item = item.trim().toLowerCase(Locale.ROOT);
            if (item.length() >= 3) keywords.add(item);
        }
        return keywords;
    }
}
