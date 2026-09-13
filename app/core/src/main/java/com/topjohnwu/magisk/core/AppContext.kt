package com.topjohnwu.magisk.core

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.system.Os
import androidx.profileinstaller.ProfileInstaller
import com.topjohnwu.magisk.core.base.UntrackedActivity
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.core.utils.NetworkObserver
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.core.utils.ShellInit
import com.topjohnwu.magisk.view.Notifications
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

lateinit var AppApkPath: String
    private set
lateinit var AppBinaryPath: String
    private set

object AppContext : ContextWrapper(null),
    Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {

    val foregroundActivity: Activity? get() = ref.get()

    private var ref = WeakReference<Activity>(null)
    private lateinit var application: Application
    private lateinit var networkObserver: NetworkObserver
    private var profileInstallScheduled = false

    init {
        Os.setenv("PATH", "${Os.getenv("PATH")}:/debug_ramdisk:/sbin", true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        LocaleSetting.instance.updateResource(resources)
    }

    override fun onActivityStarted(activity: Activity) {
        if (!profileInstallScheduled && !BuildConfig.DEBUG) {
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



        (base.classLoader as? BaseDexClassLoader)?.findLibrary(BuildConfig.MAIN_LIB_NAME)
            ?: File(
                appInfo.nativeLibraryDir,
                "lib${BuildConfig.MAIN_LIB_NAME}.so",
            ).absolutePath
    }.getOrNull()

    fun attachApplication(app: Application) {
        application = app
        val base = app.baseContext
        attachBaseContext(base)
        base.deleteDatabase("sulogs.db")
        listOf(Const.STUB_NAME, "patched.apk").forEach {
            java.io.File(base.cacheDir, it).delete()
        }
        base.cacheDir.listFiles { file -> file.extension == "md" }?.forEach { it.delete() }
        java.io.File(base.cacheDir, "flash").deleteRecursively()
        app.registerActivityLifecycleCallbacks(this)
        app.registerComponentCallbacks(this)

        AppApkPath = base.packageResourcePath
        AppBinaryPath = preparePackagedSu(base).orEmpty()
        resources.patch()
        // Request callbacks can start the provider/activity directly without
        // ever opening MainActivity. Create channels during application setup
        // so status-bar notifications are always deliverable.
        Notifications.setup()




        val (suCmd, needsArgvShim) = run {
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
                AppBinaryPath.takeIf(String::isNotEmpty) to true
            }
        }
        val shellBuilder = Shell.Builder.create()
            .setFlags(Shell.FLAG_MOUNT_MASTER)
            .setInitializers(ShellInit::class.java)
            .setContext(this)
            .setTimeout(20)
        if (suCmd != null) {
            val rootCommand = if (needsArgvShim) {
                "(exec -a su '$suCmd' --mount-master -c 'exec /system/bin/sh')"
            } else {
                "'$suCmd' --mount-master -c 'exec /system/bin/sh'"
            }
            // Explicit commands bypass libsu's built-in non-root fallback.
            // Keep a usable local shell when a different/missing daemon denies
            // this manager, otherwise getShell never delivers its callback.
            shellBuilder.setCommands(
                "/system/bin/sh",
                "-c",
                "export PATH=/debug_ramdisk:/sbin:/system/bin:/system/xbin; " +
                    "$rootCommand || exec /system/bin/sh",
            )
        }
        Shell.setDefaultBuilder(shellBuilder)
        Shell.EXECUTOR = Dispatchers.IO.asExecutor()
        RootUtils.bindTask = RootService.bindOrTask(
            intent<RootUtils>(),
            UiThreadHandler.executor,
            RootUtils.Connection
        )
        networkObserver = NetworkObserver(this)
        Udonge.scheduleBackgroundUpdates(this)
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
