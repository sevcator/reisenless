package com.topjohnwu.magisk.ui.deny

import android.annotation.SuppressLint
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import androidx.databinding.Bindable
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.ktx.concurrentMap
import com.topjohnwu.magisk.core.sulist.SulistController
import com.topjohnwu.magisk.databinding.bindExtra
import com.topjohnwu.magisk.databinding.filterList
import com.topjohnwu.magisk.databinding.set
import com.topjohnwu.magisk.events.SnackbarEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DenyListViewModel : AsyncLoadViewModel() {

    private val mutationMutex = Mutex()

    var isShowSystem = false
        set(value) {
            field = value
            doQuery(query)
        }

    var isShowOS = false
        set(value) {
            field = value
            doQuery(query)
        }

    var query = ""
        set(value) {
            field = value
            doQuery(value)
        }

    val items = filterList<DenyListRvItem>(viewModelScope)
    val extraBindings = bindExtra {
        it.put(BR.viewModel, this)
    }

    @get:Bindable
    var loading = true
        private set(value) = set(value, field, { field = it }, BR.loading)

    @SuppressLint("InlinedApi")
    override suspend fun doLoadWork() {
        loading = true
        val sulist = withContext(Dispatchers.IO) {
            runCatching { SulistController.list() }.getOrNull()
        }
        if (sulist == null) {
            SnackbarEvent(R.string.failure).publish()
            loading = false
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
                    .concurrentMap { DenyListRvItem(it, ::toggleAll, ::toggleProcess) }
                    .toCollection(ArrayList(size + 1))
            }
            apps += DenyListRvItem(
                AppProcessInfo.webViewZygote(
                    pm,
                    denyList,
                    "WebView Zygote",
                ),
                ::toggleAll,
                ::toggleProcess,
            )
            apps.sort()
            apps
        }
        items.set(apps)
        doQuery(query)
    }

    private fun toggleAll(item: DenyListRvItem, enabled: Boolean) {
        mutate {
            var success = true
            if (enabled) {
                for (process in item.processes.filterNot { it.isEnabled }
                    .filter { item.isExpanded || it.defaultSelection }) {
                    val (name, pkg) = process.process
                    if (!SulistController.add(pkg, name)) success = false
                }
            } else {
                if (!SulistController.remove(item.info.packageName)) success = false
                for (process in item.processes.filter {
                    it.isEnabled && it.process.isIsolated
                }) {
                    val (name, pkg) = process.process
                    if (!SulistController.remove(pkg, name)) success = false
                }
            }
            success
        }
    }

    private fun toggleProcess(item: ProcessRvItem, enabled: Boolean) {
        val (name, pkg) = item.process
        mutate {
            if (enabled) SulistController.add(pkg, name)
            else SulistController.remove(pkg, name)
        }
    }

    private fun mutate(action: () -> Boolean) {
        if (loading) return
        loading = true
        viewModelScope.launch {
            mutationMutex.withLock {
                val success = withContext(Dispatchers.IO) {
                    runCatching(action).getOrDefault(false)
                }
                if (!success) SnackbarEvent(R.string.failure).publish()
                doLoadWork()
            }
        }
    }

    private fun doQuery(s: String) {
        items.filter {
            fun filterSystem() = isShowSystem || !it.info.isSystemApp()

            fun filterOS() = (isShowSystem && isShowOS) || it.info.isApp()

            fun filterQuery(): Boolean {
                fun inName() = it.info.label.contains(s, true)
                fun inPackage() = it.info.packageName.contains(s, true)
                fun inProcesses() = it.processes.any { p -> p.process.name.contains(s, true) }
                return inName() || inPackage() || inProcesses()
            }

            (it.isChecked || (filterSystem() && filterOS())) && filterQuery()
        }
        loading = false
    }
}
