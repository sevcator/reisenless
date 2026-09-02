package com.topjohnwu.magisk;

import static com.topjohnwu.magisk.BuildConfig.APPLICATION_ID;

import android.app.AppComponentFactory;
import android.app.Application;
import android.app.job.JobService;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import com.topjohnwu.magisk.utils.APKInstall;
import com.topjohnwu.magisk.utils.DynamicClassLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class DynLoad {

    private static final String MIGRATION_SOURCE = "reisenless.migration.source";
    static Object componentFactory;
    static ClassLoader activeClassLoader = DynLoad.class.getClassLoader();

    static StubApk.Data createApkData() {
        var data = new StubApk.Data();
        data.setVersion(BuildConfig.STUB_VERSION);
        data.setClassToComponent(new HashMap<>());
        data.setRootService(StubRootService.class);
        return data;
    }

    private static PackageInfo parseArchive(PackageManager pm, File apk, int flags) {



        for (int attempt = 0; attempt < 20; ++attempt) {

            var info = pm.getPackageArchiveInfo(apk.getPath(), flags);
            if (info != null) return info;
            SystemClock.sleep(100);
        }
        throw new IllegalStateException("unable to parse manager archive");
    }

    static void attachContext(Object o, Context context) {
        if (!(o instanceof ContextWrapper))
            return;
        try {
            Method m = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            m.setAccessible(true);
            m.invoke(o, context);
        } catch (Exception ignored) {                  }
    }

    private static boolean makeManagerApkReadOnly(File apk) {
        // The regular app process and the isolated UID-0 RootService both load
        // this archive. File.setReadOnly() preserves a newly created file's
        // 0600 read mask as 0400, which makes the second process fail before it
        // can initialize. Keep the containing directory private and publish
        // only the APK itself as immutable 0444.
        return apk.setReadable(true, false) && apk.setWritable(false, false);
    }

    private static InputStream openManagerApk(Context context)
            throws IOException, PackageManager.NameNotFoundException {
        var pm = context.getPackageManager();
        var self = pm.getApplicationInfo(
                context.getPackageName(), PackageManager.GET_META_DATA);
        String sourcePackage = self.metaData == null
                ? null : self.metaData.getString(MIGRATION_SOURCE);
        if (sourcePackage == null || sourcePackage.equals(context.getPackageName())) {
            sourcePackage = APPLICATION_ID;
        }
        // Current hidden stubs have a dedicated provider. Older hidden stubs
        // publish only `.provider`; the dynamically loaded full manager serves
        // the same guarded endpoint there as a compatibility bridge.
        for (String suffix : new String[] { ".migration", ".provider" }) {
            try {
                var uri = Uri.parse("content://" + sourcePackage + suffix + "/apk");
                var input = context.getContentResolver().openInputStream(uri);
                if (input != null) return input;
            } catch (IOException | SecurityException | IllegalArgumentException ignored) {
            }
        }
        // Only the original public package is itself a full APK. A hidden
        // source package is another stub, so copying its base.apk would poison
        // the target's dynamic payload.
        if (!APPLICATION_ID.equals(sourcePackage)) {
            throw new IOException("migration provider unavailable");
        }
        var info = pm.getApplicationInfo(sourcePackage, 0);
        return new FileInputStream(info.sourceDir);
    }


    static DynamicClassLoader loadApk(Context context) {
        File apk = StubApk.current(context);
        File update = StubApk.update(context);

        if (update.exists()) {

            update.renameTo(apk);
        }


        if (BuildConfig.DEBUG) {
            try {
                File external = new File(context.getExternalFilesDir(null), "magisk.apk");
                if (external.exists()) {
                    apk.delete();
                    try {
                        var in = new FileInputStream(external);
                        var out = new FileOutputStream(apk);
                        makeManagerApkReadOnly(apk);
                        try (in; out) {
                            APKInstall.transfer(in, out);
                        }
                    } catch (IOException e) {
                        apk.delete();
                    } finally {
                        external.delete();
                    }
                }
            } catch (SecurityException e) {

            }
        }

        if (apk.exists()) {
            makeManagerApkReadOnly(apk);
            return new DynamicClassLoader(apk);
        }


        if (!context.getPackageName().equals(APPLICATION_ID)) {
            File bootstrap = new File(apk.getParentFile(), "bootstrap.apk.tmp");
            try {
                bootstrap.delete();
                var src = openManagerApk(context);
                var out = new FileOutputStream(bootstrap);
                try (src; out) {
                    APKInstall.transfer(src, out);
                }
                if (!makeManagerApkReadOnly(bootstrap)) {
                    bootstrap.delete();
                    if (apk.exists()) {
                        makeManagerApkReadOnly(apk);
                        return new DynamicClassLoader(apk);
                    }
                    return null;
                }
                // A rooted migration source may have published current.apk
                // while the provider copy was in progress. Never overwrite or
                // delete that authoritative file.
                if (apk.exists()) {
                    bootstrap.delete();
                    makeManagerApkReadOnly(apk);
                    return new DynamicClassLoader(apk);
                }
                if (!bootstrap.renameTo(apk)) {
                    bootstrap.delete();
                    if (apk.exists()) {
                        makeManagerApkReadOnly(apk);
                        return new DynamicClassLoader(apk);
                    }
                    return null;
                }
                return new DynamicClassLoader(apk);
            } catch (PackageManager.NameNotFoundException ignored) {
            } catch (IOException e) {
                bootstrap.delete();
                if (apk.exists()) {
                    makeManagerApkReadOnly(apk);
                    return new DynamicClassLoader(apk);
                }
            }
        }

        return null;
    }


    static void loadAndInitializeApp(Application context) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            replaceClassLoader(context);


        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                | PackageManager.GET_PROVIDERS | PackageManager.GET_RECEIVERS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DISABLED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        var pm = context.getPackageManager();

        final PackageInfo stubInfo;
        try {

            stubInfo = pm.getPackageInfo(context.getPackageName(), flags);
        } catch (PackageManager.NameNotFoundException e) {

            throw new RuntimeException(e);
        }

        File apk = StubApk.current(context);

        final var cl = loadApk(context);
        if (cl != null) try {
            var apkInfo = parseArchive(pm, apk, flags);
            var mapping = generateMapping(stubInfo, apkInfo);

            var data = createApkData();
            var map = data.getClassToComponent();

            for (var e : mapping.entrySet()) {
                map.put(e.getValue(), e.getKey());
            }

            var appInfo = apkInfo.applicationInfo;

            var app = cl.loadClass(appInfo.className)
                    .getConstructor(Object.class)
                    .newInstance(data.getObject());


            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && componentFactory != null) {
                var delegate = (DelegateComponentFactory) componentFactory;
                if (appInfo.appComponentFactory == null) {
                    delegate.receiver = new AppComponentFactory();
                } else {
                    Object factory = cl.loadClass(appInfo.appComponentFactory).newInstance();
                    delegate.receiver = (AppComponentFactory) factory;
                }
            }

            activeClassLoader = new MappingClassLoader(cl, mapping);


            attachContext(app, context);
        } catch (Exception e) {
            apk.delete();
        } else {

            activeClassLoader = new StubClassLoader(stubInfo);
        }
    }


    private static void replaceClassLoader(Context context) {

        while (context instanceof ContextWrapper) {
            context = ((ContextWrapper) context).getBaseContext();
        }

        try {
            Field mInfo = context.getClass().getDeclaredField("mPackageInfo");
            mInfo.setAccessible(true);
            Object loadedApk = mInfo.get(context);
            assert loadedApk != null;
            Field mcl = loadedApk.getClass().getDeclaredField("mClassLoader");
            mcl.setAccessible(true);
            mcl.set(loadedApk, new DelegateClassLoader());
        } catch (Exception ignored) {}
    }

    private static Map<String, String> generateMapping(PackageInfo stub, PackageInfo app) {
        var mapping = new HashMap<String, String>();
        {
            var src = stub.activities;
            var dest = app.activities;
            for (ActivityInfo source : src) {
                ActivityInfo match = null;
                for (ActivityInfo target : dest) {
                    if (source.exported == target.exported &&
                            hasEmptyTaskAffinity(source) == hasEmptyTaskAffinity(target)) {




                        match = target;
                        break;
                    }
                }
                if (match == null) {
                    throw new IllegalStateException("unable to map manager activity");
                }
                mapping.put(source.name, match.name);
            }
        }

        {
            var src = stub.services;
            var dest = app.services;

            final ServiceInfo sa;
            final ServiceInfo da;
            final ServiceInfo sb;
            final ServiceInfo db;
            if (JobService.PERMISSION_BIND.equals(src[0].permission)) {
                sa = src[0];
                sb = src[1];
            } else {
                sa = src[1];
                sb = src[0];
            }
            if (JobService.PERMISSION_BIND.equals(dest[0].permission)) {
                da = dest[0];
                db = dest[1];
            } else {
                da = dest[1];
                db = dest[0];
            }
            mapping.put(sa.name, da.name);
            mapping.put(sb.name, db.name);
        }

        {
            var src = stub.receivers;
            var dest = app.receivers;
            mapping.put(src[0].name, dest[0].name);
        }

        {
            var src = stub.providers;
            var dest = app.providers;
            for (ProviderInfo source : src) {
                ProviderInfo match = null;
                String sourceSuffix = authoritySuffix(source.authority);
                for (ProviderInfo target : dest) {
                    if (sourceSuffix.equals(authoritySuffix(target.authority))) {
                        match = target;
                        break;
                    }
                }
                if (match == null) {
                    throw new IllegalStateException("unable to map manager provider");
                }
                mapping.put(source.name, match.name);
            }
        }
        return mapping;
    }

    private static String authoritySuffix(String authority) {
        if (authority == null) return "";
        int separator = authority.lastIndexOf('.');
        return separator < 0 ? authority : authority.substring(separator);
    }

    private static boolean hasEmptyTaskAffinity(ActivityInfo info) {
        return info.taskAffinity == null || info.taskAffinity.isEmpty();
    }
}
