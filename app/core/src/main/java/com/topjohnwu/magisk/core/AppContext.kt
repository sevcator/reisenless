package com.topjohnwu.magisk.core

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.system.Os
import androidx.profileinstaller.ProfileInstaller
import com.topjohnwu.magisk.StubApk
import com.topjohnwu.magisk.core.base.UntrackedActivity
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.core.utils.NetworkObserver
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.core.utils.ShellInit
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.internal.UiThreadHandler
import com.topjohnwu.superuser.ipc.RootService
import dalvik.system.BaseDexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference
import kotlin.system.exitProcess

lateinit var AppApkPath: String
    private set

object AppContext : ContextWrapper(null),
    Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {

    val foregroundActivity: Activity? get() = ref.get()

    private var ref = WeakReference<Activity>(null)
    private lateinit var application: Application
    private lateinit var networkObserver: NetworkObserver
    private var profileInstallScheduled = false

    init {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> exitProcess(1) }

        Os.setenv("PATH", "${Os.getenv("PATH")}:/debug_ramdisk:/sbin", true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        LocaleSetting.instance.updateResource(resources)
    }

    override fun onActivityStarted(activity: Activity) {
        if (!profileInstallScheduled && !BuildConfig.DEBUG && !isRunningAsStub) {
            profileInstallScheduled = true
            GlobalScope.launch(Dispatchers.IO) {
                ProfileInstaller.writeProfile(this@AppContext)
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is UntrackedActivity) return
        ref = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is UntrackedActivity) return
        ref.clear()
    }

    override fun getApplicationContext() = application

    private fun preparePackagedSu(base: Context): String? = runCatching {
        val appInfo = base.applicationInfo
        // Android supplies the exact ABI extraction directory here. Some ROMs
        // allow executing its libraries but intentionally deny metadata probes.
        // Trust the platform path instead of checking File.isFile first.
        (base.classLoader as? BaseDexClassLoader)?.findLibrary("magisk")
            ?: File(appInfo.nativeLibraryDir, "libmagisk.so").absolutePath
    }.getOrNull()

    fun attachApplication(app: Application) {
        application = app
        val base = app.baseContext
        attachBaseContext(base)
        base.deleteDatabase("sulogs.db")
        listOf(Const.STUB_NAME, "stub.apk", "test.apk", "patched.apk").forEach {
            java.io.File(base.cacheDir, it).delete()
        }
        base.cacheDir.listFiles { file -> file.extension == "md" }?.forEach { it.delete() }
        java.io.File(base.cacheDir, "app-migration").deleteRecursively()
        java.io.File(base.cacheDir, "flash").deleteRecursively()
        java.io.File(base.filesDir.parentFile, "install").deleteRecursively()
        app.registerActivityLifecycleCallbacks(this)
        app.registerComponentCallbacks(this)

        AppApkPath = if (isRunningAsStub) {
            StubApk.current(base).path
        } else {
            base.packageResourcePath
        }
        resources.patch()

        // Prefer the mounted client. It starts the interactive root shell
        // directly and keeps libsu's process lifecycle identical to the core
        // that owns the mount. The APK client remains a recovery fallback for
        // safe-mode or partially mounted installations.
        val (suCmd, needsArgvShim) = if (isRunningAsStub) {
            // Java caches its executable search path before the stub appends
            // /debug_ramdisk, so name-based discovery falls through to the
            // packaged client. Execute the live mounted client explicitly.
            "/debug_ramdisk/su" to false
        } else run {
            val tmp = try {
                Runtime.getRuntime()
                    .exec(arrayOf(Const.MAIN_BIN, "--path"))
                    .inputStream.bufferedReader().readLine()?.trim()
            } catch (_: Exception) { null }
            val mounted = if (!tmp.isNullOrEmpty()) {
                val candidate = java.io.File("$tmp/su")
                if (candidate.exists() || java.io.File(candidate.canonicalPath).exists()) {
                    candidate.absolutePath
                } else null
            } else null
            if (mounted != null) {
                mounted to false
            } else {
                preparePackagedSu(base) to true
            }
        }
        val shellBuilder = Shell.Builder.create()
            .setFlags(Shell.FLAG_MOUNT_MASTER)
            .setInitializers(ShellInit::class.java)
            .setContext(this)
            .setTimeout(20)
        if (suCmd != null) {
            if (needsArgvShim) {
                // Native multicall dispatch uses argv[0] to select the su applet.
                shellBuilder.setCommands(
                    "/system/bin/sh",
                    "-c",
                    "exec -a su '$suCmd' --mount-master",
                )
            } else {
                shellBuilder.setCommands(suCmd)
            }
        }
        Shell.setDefaultBuilder(shellBuilder)
        Shell.EXECUTOR = Dispatchers.IO.asExecutor()
        RootUtils.bindTask = RootService.bindOrTask(
            intent<RootUtils>(),
            UiThreadHandler.executor,
            RootUtils.Connection
        )
        if (SDK_INT >= 34 && isRunningAsStub) {
            // Send over the locale config manually
            val lm = getSystemService(LocaleManager::class.java)
            lm.overrideLocaleConfig = LocaleSetting.localeConfig
        }
        networkObserver = NetworkObserver(this)
    }

    override fun createDeviceProtectedStorageContext(): Context {
        return if (SDK_INT >= Build.VERSION_CODES.N) {
            super.createDeviceProtectedStorageContext()
        } else {
            this
        }
    }

    override fun onActivityCreated(activity: Activity, bundle: Bundle?) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
    override fun onLowMemory() {}
    override fun onTrimMemory(level: Int) {}
}
