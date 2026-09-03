package com.topjohnwu.magisk.ui.deny

import android.annotation.SuppressLint
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.ktx.concurrentMap
import com.topjohnwu.magisk.core.sulist.SulistController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SortBy { NAME, PACKAGE_NAME, INSTALL_TIME, UPDATE_TIME }

data class DenyProcessState(
    val process: ProcessInfo,
    val isEnabled: Boolean = process.isEnabled,
) {
    val displayName: String =
        if (process.isIsolated) "(isolated) ${process.name}*" else process.name
}

data class DenyAppState(
    val info: AppProcessInfo,
    val processes: List<DenyProcessState> = info.processes.map { DenyProcessState(it) },
    val isExpanded: Boolean = false,
) : Comparable<DenyAppState> {

    val itemsChecked: Int get() = processes.count { it.isEnabled }
    val isChecked: Boolean get() = itemsChecked > 0
    val checkedPercent: Float get() = if (processes.isEmpty()) 0f else itemsChecked.toFloat() / processes.size

    override fun compareTo(other: DenyAppState) = comparator.compare(this, other)

    companion object {
        private val comparator = compareBy<DenyAppState>(
            { it.itemsChecked == 0 },
            { it.info }
        )
    }
}

class DenyListViewModel : AsyncLoadViewModel() {

    private val mutationMutex = Mutex()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _allApps = MutableStateFlow<List<DenyAppState>>(emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _showSystem = MutableStateFlow(false)
    val showSystem: StateFlow<Boolean> = _showSystem.asStateFlow()

    private val _showOS = MutableStateFlow(false)
    val showOS: StateFlow<Boolean> = _showOS.asStateFlow()

    private val _sortBy = MutableStateFlow(SortBy.NAME)
    val sortBy: StateFlow<SortBy> = _sortBy.asStateFlow()

    private val _sortReverse = MutableStateFlow(false)
    val sortReverse: StateFlow<Boolean> = _sortReverse.asStateFlow()

    val filteredApps: StateFlow<List<DenyAppState>> = combine(
        _allApps, _query, _showSystem, _showOS, _sortBy, _sortReverse
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val apps = args[0] as List<DenyAppState>
        val q = args[1] as String
        val showSys = args[2] as Boolean
        val showOS = args[3] as Boolean
        val sort = args[4] as SortBy
        val reverse = args[5] as Boolean

        val filtered = apps.filter { app ->
            val passFilter = app.isChecked ||
                ((showSys || !app.info.isSystemApp()) &&
                ((showSys && showOS) || app.info.isApp()))
            val passQuery = q.isBlank() ||
                app.info.label.contains(q, true) ||
                app.info.packageName.contains(q, true) ||
                app.processes.any { it.process.name.contains(q, true) }
            passFilter && passQuery
        }

        val secondary: Comparator<DenyAppState> = when (sort) {
            SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.info.label }
            SortBy.PACKAGE_NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.info.packageName }
            SortBy.INSTALL_TIME -> compareByDescending { it.info.firstInstallTime }
            SortBy.UPDATE_TIME -> compareByDescending { it.info.lastUpdateTime }
        }
        val comparator = compareBy<DenyAppState> { it.itemsChecked == 0 }
            .then(if (reverse) secondary.reversed() else secondary)
        filtered.sortedWith(comparator)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(q: String) { _query.value = q }
    fun setShowSystem(v: Boolean) {
        _showSystem.value = v
        if (!v) _showOS.value = false
    }
    fun setShowOS(v: Boolean) { _showOS.value = v }
    fun setSortBy(s: SortBy) { _sortBy.value = s }
    fun toggleSortReverse() { _sortReverse.value = !_sortReverse.value }

    fun toggleExpanded(app: DenyAppState) {
        _allApps.update { apps ->
            apps.map {
                if (it.info.packageName == app.info.packageName) it.copy(isExpanded = !it.isExpanded) else it
            }
        }
    }

    fun toggleAll(app: DenyAppState) {
        val willCheck = !app.isChecked
        mutate {
            var success = true
            if (willCheck) {
                for (proc in app.processes.filterNot { it.isEnabled }) {
                    val (name, pkg) = proc.process
                    if (!SulistController.add(pkg, name)) success = false
                }
            } else {
                if (!SulistController.remove(app.info.packageName)) success = false
                for (proc in app.processes.filter { it.isEnabled && it.process.isIsolated }) {
                    val (name, pkg) = proc.process
                    if (!SulistController.remove(pkg, name)) success = false
                }
            }
            success
        }
    }

    fun toggleProcess(proc: DenyProcessState) {
        val newEnabled = !proc.isEnabled
        val (name, pkg) = proc.process
        mutate {
            if (newEnabled) {
                SulistController.add(pkg, name)
            } else {
                SulistController.remove(pkg, name)
            }
        }
    }

    private fun mutate(action: () -> Boolean) {
        if (_loading.value) return
        _loading.value = true
        viewModelScope.launch {
            mutationMutex.withLock {
                val success = withContext(Dispatchers.IO) {
                    runCatching(action).getOrDefault(false)
                }
                if (!success) showSnackbar(R.string.failure)
                doLoadWork()
            }
        }
    }

    @SuppressLint("InlinedApi")
    override suspend fun doLoadWork() {
        _loading.value = true
        val sulist = withContext(Dispatchers.IO) {
            runCatching { SulistController.list() }.getOrNull()
        }
        if (sulist == null) {
            showSnackbar(R.string.failure)
            _loading.value = false
            return
        }
        val apps = withContext(Dispatchers.Default) {
            val pm = AppContext.packageManager
            val denyList = sulist.map {
                CmdlineListItem("${it.packageName}|${it.processName}")
            }
            val apps = pm.getInstalledApplications(MATCH_UNINSTALLED_PACKAGES).run {
                asFlow()
                    .filter { AppContext.packageName != it.packageName }
                    .concurrentMap { AppProcessInfo(it, pm, denyList) }
                    .filter { it.processes.isNotEmpty() }
                    .concurrentMap { DenyAppState(it) }
                    .toCollection(ArrayList(size + 1))
            }
            apps += DenyAppState(
                AppProcessInfo.webViewZygote(
                    pm,
                    denyList,
                    "WebView Zygote",
                )
            )
            apps.sortWith(compareBy(
                { it.processes.count { p -> p.isEnabled } == 0 },
                { it.info }
            ))
            apps
        }
        _allApps.value = apps
        _loading.value = false
    }
}
