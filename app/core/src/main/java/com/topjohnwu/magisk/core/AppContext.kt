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
import android.util.Log
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
import java.util.jar.JarFile
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
        val apkDir = File(appInfo.sourceDir).parentFile
        val candidates = buildList {
            (base.classLoader as? BaseDexClassLoader)?.findLibrary("magisk")?.let {
                add(File(it))
            }
            add(File(appInfo.nativeLibraryDir, "libmagisk.so"))
            Build.SUPPORTED_ABIS.forEach { abi ->
                add(File(apkDir, "lib/$abi/libmagisk.so"))
                val instructionSet = when {
                    abi.startsWith("arm64") -> "arm64"
                    abi.startsWith("armeabi") -> "arm"
                    abi == "x86_64" -> "x86_64"
                    else -> "x86"
                }
                add(File(appInfo.nativeLibraryDir, "$instructionSet/libmagisk.so"))
            }
            File(apkDir, "lib").listFiles()?.forEach { abiDir ->
                add(File(abiDir, "libmagisk.so"))
            }
        }
        candidates.firstOrNull(File::isFile)?.let {
            Log.i("ReisenlessRoot", "Using packaged client: ${it.absolutePath}")
            return@runCatching it.absolutePath
        }
        Log.e("ReisenlessRoot", "Packaged candidates: ${candidates.joinToString { "${it.path}=${it.isFile}" }}")
        val target = File(base.filesDir, "su")
        check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        target.delete()
        target.outputStream().use { output ->
            if (isRunningAsStub) {
                JarFile(StubApk.current(base)).use { apk ->
                    val entry = apk.getJarEntry("lib/${Const.CPU_ABI}/libmagisk.so")
                        ?: error("missing packaged root client")
                    apk.getInputStream(entry).use { it.copyTo(output) }
                }
            } else {
                JarFile(base.packageResourcePath).use { apk ->
                    val entry = apk.getJarEntry("lib/${Const.CPU_ABI}/libmagisk.so")
                        ?: error("missing packaged root client")
                    apk.getInputStream(entry).use { it.copyTo(output) }
                }
            }
        }
        check(target.setReadable(true, true))
        check(target.setExecutable(true, true))
        check(target.setWritable(false, false))
        Log.i("ReisenlessRoot", "Using extracted client: ${target.absolutePath}")
        target.absolutePath
    }.onFailure {
        Log.e("ReisenlessRoot", "Unable to prepare packaged root client", it)
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

        // Prefer the APK client because Android permits an app to execute its own
        // native libraries. Some older installations label the tmpfs client as
        // system_file, which Android 15 rejects as an untrusted_app entry point.
        // Keep the mounted client only as a recovery fallback.
        val suCmd = run {
            val packaged = preparePackagedSu(base)
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
            packaged ?: mounted
        }
        val shellBuilder = Shell.Builder.create()
            .setFlags(Shell.FLAG_MOUNT_MASTER)
            .setInitializers(ShellInit::class.java)
            .setContext(this)
            .setTimeout(20)
        if (suCmd != null) {
            // Native multicall dispatch uses argv[0] to select the su applet.
            // Keep the executable in Android's allowed native-lib directory,
            // but present the expected applet name to the process.
            shellBuilder.setCommands(
                "/system/bin/sh",
                "-c",
                "exec -a su '$suCmd' --mount-master",
            )
        } else {
            Log.e("ReisenlessRoot", "No packaged or mounted root client is available")
        }
        Shell.enableVerboseLogging = true
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
