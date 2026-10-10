package com.topjohnwu.magisk.core.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.ktx.getLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

data class InstalledApp(val packageName: String, val label: String, val uid: Int, val isSystem: Boolean)

object AppCatalog {
    private val _generation = MutableStateFlow(0L)
    val generation = _generation.asStateFlow()
    private val icons = LruCache<String, Drawable>(64)
    private var configuration: Configuration? = null
    private var registered = false
    private val snapshot = SnapshotCache {
        withContext(Dispatchers.IO) {
            val pm = AppContext.packageManager
            @SuppressLint("QueryPermissionsNeeded")
            val installed = pm.getInstalledApplications(MATCH_UNINSTALLED_PACKAGES).toMutableList()
            if (installed.none { it.packageName == AppContext.packageName }) installed += AppContext.applicationInfo
            installed.map { app ->
                InstalledApp(app.packageName, app.getLabel(pm), app.uid,
                    app.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            }.sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.packageName }))
        }
    }

    @Synchronized private fun prepare() {
        if (!registered) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) = invalidate()
            }
            val packages = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(AppContext, receiver, packages, ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(AppContext, receiver, IntentFilter(Intent.ACTION_LOCALE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        val current = AppContext.resources.configuration
        if (configuration != current) {
            val wasConfigured = configuration != null
            configuration = Configuration(current)
            if (wasConfigured) invalidate()
        }
    }

    @Synchronized fun invalidate() {
        snapshot.invalidate()
        synchronized(icons) { icons.evictAll() }
        _generation.value += 1
    }

    suspend fun apps(): List<InstalledApp> { prepare(); return snapshot.get() }

    suspend fun icon(packageName: String): Drawable = withContext(Dispatchers.IO) {
        prepare()
        val version = generation.value
        val key = "$version:$packageName"
        val drawable = synchronized(icons) {
            icons.get(key) ?: run {
                val pm = AppContext.packageManager
                val loaded = runCatching { pm.getApplicationIcon(packageName) }.getOrDefault(pm.defaultActivityIcon)
                if (version == generation.value) icons.put(key, loaded)
                loaded
            }
        }
        drawable.constantState?.newDrawable(AppContext.resources)?.mutate() ?: drawable
    }
}
