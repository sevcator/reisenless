package com.topjohnwu.magisk.ui.module

import android.annotation.SuppressLint
import android.net.Uri
import androidx.databinding.Bindable
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.MainDirections
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.base.ContentResultCallback
import com.topjohnwu.magisk.core.model.module.LocalModule
import com.topjohnwu.magisk.core.model.module.OnlineModule
import com.topjohnwu.magisk.databinding.MergeObservableList
import com.topjohnwu.magisk.databinding.RvItem
import com.topjohnwu.magisk.databinding.bindExtra
import com.topjohnwu.magisk.databinding.diffList
import com.topjohnwu.magisk.databinding.set
import com.topjohnwu.magisk.dialog.LocalModuleInstallDialog
import com.topjohnwu.magisk.dialog.OnlineModuleInstallDialog
import com.topjohnwu.magisk.events.GetContentEvent
import com.topjohnwu.magisk.events.SnackbarEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import com.topjohnwu.magisk.core.R as CoreR
import com.topjohnwu.magisk.ui.webui.WebUIActivity

class ModuleViewModel : AsyncLoadViewModel() {

    val bottomBarBarrierIds = intArrayOf(R.id.module_update, R.id.module_webui, R.id.module_remove)

    private val itemsInstalled = diffList<LocalModuleRvItem>()
    private var allInstalled = emptyList<LocalModuleRvItem>()
    val hasInstalledModules = MutableLiveData(false)

    val items = MergeObservableList<RvItem>()
    val extraBindings = bindExtra {
        it.put(BR.viewModel, this)
    }

    val data get() = uri

    @get:Bindable
    var loading = true
        private set(value) = set(value, field, { field = it }, BR.loading)

    override suspend fun doLoadWork() {
        loading = true
        val moduleLoaded = Info.env.isActive &&
                withContext(Dispatchers.IO) { LocalModule.loaded() }
        if (Info.env.isActive) {
            if (moduleLoaded) {
                loadInstalled()
            }
            if (items.isEmpty()) {
                items.insertItem(InstallModule)
                    .insertList(itemsInstalled)
            }
        }
        loading = false
        if (moduleLoaded) {
            loadUpdateInfo()
        }
    }

    override fun onNetworkChanged(network: Boolean) = startLoading()

    private suspend fun loadInstalled() {
        withContext(Dispatchers.Default) {
            allInstalled = LocalModule.installed().map { LocalModuleRvItem(it) }
            itemsInstalled.update(allInstalled)
            hasInstalledModules.postValue(allInstalled.isNotEmpty())
        }
    }

    fun searchInstalled(query: String) {
        val words = query.trim().lowercase().split(Regex("\\s+"))
            .filter(String::isNotBlank)
        val filtered = if (words.isEmpty()) allInstalled else allInstalled.filter { row ->
            val module = row.item
            val searchable = listOf(
                module.id,
                module.name,
                module.author,
                module.description,
            ).joinToString(" ").lowercase()
            words.all(searchable::contains)
        }
        viewModelScope.launch {
            itemsInstalled.update(filtered)
        }
    }

    private suspend fun loadUpdateInfo() {
        withContext(Dispatchers.IO) {
            itemsInstalled.forEach {
                if (it.item.fetch())
                    it.fetchedUpdateInfo()
            }
        }
    }

    fun downloadPressed(item: OnlineModule?) =
        if (item != null && Info.isConnected.value == true) {
            withExternalRW { OnlineModuleInstallDialog(item).show() }
        } else {
            SnackbarEvent(CoreR.string.no_connection).publish()
        }

    fun installPressed() = withExternalRW {
        GetContentEvent("application/zip", UriCallback()).publish()
    }

    fun requestInstallLocalModule(uri: Uri, displayName: String) {
        LocalModuleInstallDialog(this, uri, displayName).show()
    }

    @Parcelize
    class UriCallback : ContentResultCallback {
        override fun onActivityResult(result: Uri) {
            uri.value = result
        }
    }

    fun runAction(id: String, name: String) {
        MainDirections.actionActionFragment(id, name).navigate()
    }

    @SuppressLint("UnsafeImplicitIntentLaunch")
    fun openWebUi(item: LocalModuleRvItem) {
        AppContext.startActivity(
            WebUIActivity.intent(AppContext, item.item.id, item.item.name)
        )
    }

    companion object {
        private val uri = MutableLiveData<Uri?>()
    }
}
