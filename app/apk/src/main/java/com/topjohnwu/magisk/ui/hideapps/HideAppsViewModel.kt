package com.topjohnwu.magisk.ui.hideapps

import android.annotation.SuppressLint
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.utils.AppCatalog
import com.topjohnwu.magisk.hideapps.HideAppsRepository
import com.topjohnwu.magisk.hideapps.HideAppsRule
import com.topjohnwu.magisk.hideapps.HideAppsStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class HideAppsViewModel : AsyncLoadViewModel() {
    private val repository = HideAppsRepository(AppContext)
    private val _config = MutableStateFlow(repository.config)
    val config = _config.asStateFlow()
    init { viewModelScope.launch { AppCatalog.generation.drop(1).collect { reload() } } }

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

    companion object {
        const val ALL_APPS_CALLER = "*"
    }

    private val sortedTargets = _apps.map { apps ->
        apps.sortedWith(compareBy({ it.isSystem }, { it.label.lowercase(Locale.ROOT) }, { it.packageName }))
    }
    val targets = combine(sortedTargets, _selectedCaller, _query) { apps, caller, query ->
        apps.asSequence()
            .filter { caller == ALL_APPS_CALLER || it.packageName != caller }
            .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @SuppressLint("InlinedApi", "QueryPermissionsNeeded")
    override suspend fun doLoadWork() {
        val loaded = withContext(Dispatchers.IO) {
            AppCatalog.apps().map { info ->
                HidePackageInfo(
                    packageName = info.packageName,
                    label = info.label,
                    isSystem = info.isSystem,
                )
            }
        }
        _apps.value = loaded
        val initial = if (repository.config.enabled || repository.config.hiddenPackages.isNotEmpty()) {
            ALL_APPS_CALLER
        } else {
            repository.config.scope.keys.firstOrNull { key -> loaded.any { it.packageName == key } }
                ?: ALL_APPS_CALLER
        }
        val previous = _selectedCaller.value
        selectCaller(previous?.takeIf { it == ALL_APPS_CALLER || loaded.any { app -> app.packageName == it } } ?: initial)
        withContext(Dispatchers.IO) {
            val synced = HideAppsRootClient.sync(repository.config, systemPackages)
            _status.value = if (synced) HideAppsRootClient.status() else HideAppsStatus(false, 0, 0)
        }
    }

    fun selectCaller(packageName: String?) {
        _config.value = repository.config
        _selectedCaller.value = packageName
        _rule.value = if (packageName == ALL_APPS_CALLER) {
            if (repository.config.enabled) HideAppsRule(packages = repository.config.hiddenPackages) else null
        } else {
            packageName?.let(repository.config.scope::get)
        }
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun setEnabled(enabled: Boolean) {
        if (_selectedCaller.value == ALL_APPS_CALLER) {
            repository.setEnabled(enabled)
            _config.value = repository.config
            _rule.value = if (enabled) HideAppsRule(packages = repository.config.hiddenPackages) else null
            viewModelScope.launch(Dispatchers.IO) {
                val synced = HideAppsRootClient.sync(repository.config, systemPackages)
                _status.value = if (synced) HideAppsRootClient.status() else HideAppsStatus(false, 0, 0)
            }
        } else {
            updateRule(if (enabled) _rule.value ?: HideAppsRule() else null)
        }
    }

    fun setWhitelist(enabled: Boolean) = updateRule((_rule.value ?: HideAppsRule()).copy(useWhitelist = enabled))

    fun setExcludeSystem(enabled: Boolean) =
        updateRule((_rule.value ?: HideAppsRule()).copy(excludeSystemApps = enabled))

    fun togglePackage(packageName: String) {
        if (_selectedCaller.value == ALL_APPS_CALLER) {
            val hidden = packageName in repository.config.hiddenPackages
            repository.setHidden(packageName, !hidden)
            if (!repository.config.enabled) {
                repository.setEnabled(true)
            }
            _rule.value = HideAppsRule(packages = repository.config.hiddenPackages)
            _config.value = repository.config
            viewModelScope.launch(Dispatchers.IO) {
                val synced = HideAppsRootClient.sync(repository.config, systemPackages)
                _status.value = if (synced) HideAppsRootClient.status() else HideAppsStatus(false, 0, 0)
            }
            return
        }
        val config = repository.config
        if (config.enabled && config.shouldHide(_selectedCaller.value, packageName,
                packageName in systemPackages)) return
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
        _config.value = repository.config
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
    val isSystem: Boolean,
)
