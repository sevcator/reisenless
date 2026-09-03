package com.topjohnwu.magisk.ui.hideapps

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.graphics.drawable.Drawable
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.ktx.getLabel
import com.topjohnwu.magisk.hideapps.HideAppsRepository
import com.topjohnwu.magisk.hideapps.HideAppsStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HideAppsViewModel : AsyncLoadViewModel() {
    private val repository = HideAppsRepository(AppContext)

    enum class ListMode { HIDDEN, VIEWER_WHITELIST }

    private data class Filters(
        val showSystem: Boolean = false,
        val showOs: Boolean = false,
    )

    private val _apps = MutableStateFlow<List<HidePackageInfo>>(emptyList())
    val apps: StateFlow<List<HidePackageInfo>> = _apps.asStateFlow()

    private val _config = MutableStateFlow(repository.config)
    val config: StateFlow<com.topjohnwu.magisk.hideapps.HideAppsConfig> = _config.asStateFlow()

    private val _mode = MutableStateFlow(ListMode.HIDDEN)
    val mode: StateFlow<ListMode> = _mode.asStateFlow()

    private val _query = MutableStateFlow("")
    private val _filters = MutableStateFlow(Filters())

    private val _status = MutableStateFlow(HideAppsStatus(false, 0, 0))
    val status: StateFlow<HideAppsStatus> = _status.asStateFlow()

    val rows = combine(_apps, _config, _mode, _query, _filters) {
            apps, config, mode, query, filters ->
        val selected = when (mode) {
            ListMode.HIDDEN -> config.hiddenPackages
            ListMode.VIEWER_WHITELIST -> config.viewerWhitelist
        }
        apps.asSequence()
            .filter {
                mode == ListMode.HIDDEN || it.packageName != AppContext.packageName
            }
            .filter {
                mode != ListMode.HIDDEN || it.packageName !in
                    com.topjohnwu.magisk.hideapps.HideAppsConfig.NEVER_HIDE
            }
            .filter {
                query.isBlank() || it.label.contains(query, true) ||
                    it.packageName.contains(query, true)
            }
            .filter { info ->
                info.packageName in selected ||
                    ((filters.showSystem || !info.isSystem) &&
                        ((filters.showSystem && filters.showOs) || !info.isOs))
            }
            .sortedWith(compareBy(
                { it.packageName !in selected },
                HidePackageInfo::isSystem,
                { it.label.lowercase() },
                HidePackageInfo::packageName,
            ))
            .map { TargetRow(it, it.packageName in selected) }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @SuppressLint("InlinedApi", "QueryPermissionsNeeded")
    override suspend fun doLoadWork() {
        val loaded = withContext(Dispatchers.Default) {
            val pm = AppContext.packageManager
            @Suppress("DEPRECATION")
            val installed = pm.getInstalledApplications(MATCH_UNINSTALLED_PACKAGES)
                .toMutableList()
            if (installed.none { it.packageName == AppContext.packageName }) {
                installed += AppContext.applicationInfo
            }
            installed.map { info ->
                HidePackageInfo(
                    packageName = info.packageName,
                    label = info.getLabel(pm),
                    icon = runCatching { info.loadIcon(pm) }.getOrDefault(pm.defaultActivityIcon),
                    isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    isOs = info.uid < android.os.Process.FIRST_APPLICATION_UID,
                )
            }.sortedBy { it.label.lowercase() }
        }
        _apps.value = loaded
        _config.value = repository.config
        withContext(Dispatchers.IO) {
            val synced = syncConfig()
            _status.value = if (synced) {
                HideAppsRootClient.status()
            } else {
                HideAppsStatus(false, 0, 0)
            }
        }
    }

    fun setMode(mode: ListMode) {
        _mode.value = mode
        _query.value = ""
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun setShowSystem(enabled: Boolean) {
        _filters.value = _filters.value.copy(showSystem = enabled)
    }

    fun setShowOs(enabled: Boolean) {
        _filters.value = _filters.value.copy(showOs = enabled)
    }

    val showSystem get() = _filters.value.showSystem
    val showOs get() = _filters.value.showOs

    fun togglePackage(packageName: String) {
        when (_mode.value) {
            ListMode.HIDDEN -> repository.setHidden(
                packageName,
                packageName !in repository.config.hiddenPackages,
            )
            ListMode.VIEWER_WHITELIST -> repository.setViewerAllowed(
                packageName,
                packageName !in repository.config.viewerWhitelist,
            )
        }
        _config.value = repository.config
        viewModelScope.launch(Dispatchers.IO) {
            val synced = syncConfig()
            _status.value = if (synced) {
                HideAppsRootClient.status()
            } else {
                HideAppsStatus(false, 0, 0)
            }
        }
    }

    private fun syncConfig(): Boolean {
        val apps = _apps.value
        return HideAppsRootClient.sync(
            repository.config,
            apps.mapTo(mutableSetOf(), HidePackageInfo::packageName),
            apps.asSequence().filter(HidePackageInfo::isSystem)
                .mapTo(mutableSetOf(), HidePackageInfo::packageName),
        )
    }
}

data class HidePackageInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isSystem: Boolean,
    val isOs: Boolean,
)

data class TargetRow(
    val app: HidePackageInfo,
    val checked: Boolean,
)
