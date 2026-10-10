package com.topjohnwu.reisenless.hideapps;

import java.util.List;
import java.util.ArrayList;
import android.content.pm.ProviderInfo;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class ReflectionCacheTest {
    public static class PackageDelegate {
        public Object getProperty(String property, String packageName, String className) {
            return "property-result";
        }
        public List<String> getMimeGroup(String packageName, String group) {
            return List.of("image/png");
        }
        public void querySyncProviders(List<String> names, List<ProviderInfo> providers) {
            names.addAll(List.of("allowed.authority", "hidden.authority"));
            providers.add(provider("com.example.allowed"));
            providers.add(provider("com.example.hidden"));
        }
    }

    private static ProviderInfo provider(String packageName) {
        try {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field field = unsafeType.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            ProviderInfo info = (ProviderInfo) unsafeType.getMethod("allocateInstance", Class.class)
                    .invoke(unsafe, ProviderInfo.class);
            info.packageName = packageName;
            return info;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void packagePolicy() throws Throwable {
        PackageDelegate delegate = new PackageDelegate();
        Constructor<PackageManagerProxy> constructor = PackageManagerProxy.class
                .getDeclaredConstructor(Object.class, String.class, String.class);
        constructor.setAccessible(true);
        PackageManagerProxy proxy = constructor.newInstance(delegate, "com.example.caller",
                "R\tcom.example.caller\tW\t0\t\tcom.example.allowed\t");
        Method property = PackageDelegate.class.getMethod("getProperty",
                String.class, String.class, String.class);
        Object result = proxy.invoke(delegate, property,
                new Object[]{"some.property", "com.example.allowed", "some.Class"});
        if (!"property-result".equals(result))
            throw new AssertionError("non-package property/class arguments blocked");
        if (proxy.invoke(delegate, property,
                new Object[]{"some.property", "com.example.hidden", "some.Class"}) != null)
            throw new AssertionError("hidden package property accepted");
        Method mime = PackageDelegate.class.getMethod("getMimeGroup", String.class, String.class);
        if (!List.of("image/png").equals(proxy.invoke(delegate, mime,
                new Object[]{"com.example.allowed", "group-name"})))
            throw new AssertionError("non-package MIME group argument blocked");
        Method sync = PackageDelegate.class.getMethod("querySyncProviders", List.class, List.class);
        List<String> names = new ArrayList<>();
        List<ProviderInfo> providers = new ArrayList<>();
        proxy.invoke(delegate, sync, new Object[]{names, providers});
        if (!names.equals(List.of("allowed.authority")) || providers.size() != 1 ||
                !"com.example.allowed".equals(providers.get(0).packageName))
            throw new AssertionError("provider authorities and records lost pairing");
        PackageManagerProxy blacklist = constructor.newInstance(delegate, "com.example.caller",
                "R\tcom.example.caller\tB\t0\t\tcom.example.hidden\t");
        names.clear();
        providers.clear();
        blacklist.invoke(delegate, sync, new Object[]{names, providers});
        if (!names.equals(List.of("allowed.authority")) || providers.size() != 1 ||
                !"com.example.allowed".equals(providers.get(0).packageName))
            throw new AssertionError("blacklist provider authorities lost pairing");
        System.out.println("package policy: non-package arguments and paired providers passed");
    }
    public static class Example {
        private Object mPM;
        public Example(List<?> list) { mPM = list; }
        public List<?> getList() { return (List<?>) mPM; }
    }
    public static void main(String[] args) throws Throwable {
        Class<?> type = Example.class;
        if (PackageManagerProxy.ReflectionCache.field(type, "mPM") !=
                PackageManagerProxy.ReflectionCache.field(type, "mPM")) throw new AssertionError("field lookup repeated");
        if (PackageManagerProxy.ReflectionCache.method(type, "getList", false) !=
                PackageManagerProxy.ReflectionCache.method(type, "getList", false)) throw new AssertionError("method lookup repeated");
        Object slice = PackageManagerProxy.ReflectionCache.listConstructor(type).newInstance(new ArrayList<>());
        if (!(PackageManagerProxy.ReflectionCache.method(type, "getList", false).invoke(slice) instanceof List))
            throw new AssertionError("container shape changed");
        for (int i = 0; i < 2; ++i) {
            try { PackageManagerProxy.ReflectionCache.field(type, "missing"); throw new AssertionError("missing field accepted"); }
            catch (NoSuchFieldException expected) { }
        }
        System.out.println("framework reflection: reused metadata, list reconstruction and missing-field fallback passed");
        packagePolicy();
    }
}
