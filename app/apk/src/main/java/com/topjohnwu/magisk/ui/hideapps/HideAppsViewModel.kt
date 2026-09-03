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
import com.topjohnwu.magisk.hideapps.HideAppsRule
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

    private val _apps = MutableStateFlow<List<HidePackageInfo>>(emptyList())
    val apps: StateFlow<List<HidePackageInfo>> = _apps.asStateFlow()

    private val _selectedCaller = MutableStateFlow<String?>(null)
    val selectedCaller: StateFlow<String?> = _selectedCaller.asStateFlow()

    private val _rule = MutableStateFlow<HideAppsRule?>(null)
    val rule: StateFlow<HideAppsRule?> = _rule.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _status = MutableStateFlow(HideAppsStatus(false, 0, 0))
    val status: StateFlow<HideAppsStatus> = _status.asStateFlow()

    private val systemPackages: Set<String>
        get() = _apps.value.asSequence().filter(HidePackageInfo::isSystem)
            .map(HidePackageInfo::packageName).toSet()

    val targets = combine(_apps, _selectedCaller, _query) { apps, caller, query ->
        apps.asSequence()
            .filter { it.packageName != caller }
            .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }, { it.packageName }))
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
                )
            }.sortedBy { it.label.lowercase() }
        }
        _apps.value = loaded
        val initial = repository.config.scope.keys.firstOrNull { key -> loaded.any { it.packageName == key } }
            ?: loaded.firstOrNull { !it.isSystem && it.packageName != AppContext.packageName }?.packageName
        selectCaller(initial)
        withContext(Dispatchers.IO) {
            val synced = HideAppsRootClient.sync(repository.config, systemPackages)
            _status.value = if (synced) HideAppsRootClient.status() else HideAppsStatus(false, 0, 0)
        }
    }

    fun selectCaller(packageName: String?) {
        _selectedCaller.value = packageName
        _rule.value = packageName?.let(repository.config.scope::get)
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun setEnabled(enabled: Boolean) = updateRule(if (enabled) _rule.value ?: HideAppsRule() else null)

    fun setWhitelist(enabled: Boolean) = updateRule((_rule.value ?: HideAppsRule()).copy(useWhitelist = enabled))

    fun setExcludeSystem(enabled: Boolean) =
        updateRule((_rule.value ?: HideAppsRule()).copy(excludeSystemApps = enabled))

    fun togglePackage(packageName: String) {
        val current = _rule.value ?: HideAppsRule()
        val packages = current.packages.toMutableSet()
        if (!packages.add(packageName)) packages.remove(packageName)
        updateRule(current.copy(packages = packages))
    }

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _status.value = HideAppsRootClient.status()
        }
    }

    private fun updateRule(updated: HideAppsRule?) {
        val caller = _selectedCaller.value ?: return
        repository.setRule(caller, updated)
        _rule.value = updated
        viewModelScope.launch(Dispatchers.IO) {
            val synced = HideAppsRootClient.sync(repository.config, systemPackages, caller)
            _status.value = if (synced) HideAppsRootClient.status() else HideAppsStatus(false, 0, 0)
        }
    }
}

data class HidePackageInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isSystem: Boolean,
)
