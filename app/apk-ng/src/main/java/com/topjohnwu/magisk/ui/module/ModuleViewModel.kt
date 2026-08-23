package com.topjohnwu.magisk.ui.module

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.R as CoreR
import com.topjohnwu.magisk.core.download.Subject
import com.topjohnwu.magisk.core.model.module.LocalModule
import com.topjohnwu.magisk.core.model.module.OnlineModule
import com.topjohnwu.magisk.core.repository.ModuleRepository
import com.topjohnwu.magisk.core.repository.RepositoryCandidate
import com.topjohnwu.magisk.core.repository.RepositoryModule
import com.topjohnwu.magisk.core.di.ServiceLocator
import com.topjohnwu.magisk.core.utils.TextHolder
import com.topjohnwu.magisk.core.utils.asText
import com.topjohnwu.magisk.ui.flash.FlashUtils
import com.topjohnwu.magisk.ui.navigation.Route
import com.topjohnwu.magisk.view.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

class ModuleItem(val module: LocalModule) {
    val showNotice: Boolean
    val showAction: Boolean
    val showWebUi: Boolean
    val noticeText: TextHolder

    init {
        val isZygisk = module.isZygisk
        val isRiru = module.isRiru
        val zygiskUnloaded = isZygisk && module.zygiskUnloaded

        showNotice = zygiskUnloaded ||
            (Info.isZygiskEnabled && isRiru) ||
            (!Info.isZygiskEnabled && isZygisk)
        showAction = module.hasAction && !showNotice
        showWebUi = module.hasWebUi && !showNotice
        noticeText =
            when {
                zygiskUnloaded -> CoreR.string.zygisk_module_unloaded.asText()
                isRiru -> CoreR.string.suspend_text_riru.asText(CoreR.string.zygisk.asText())
                else -> CoreR.string.suspend_text_zygisk.asText(CoreR.string.zygisk.asText())
            }
    }

    var isEnabled by mutableStateOf(module.enable)
    var isRemoved by mutableStateOf(module.remove)
    var showUpdate by mutableStateOf(module.updateInfo != null)
    val isUpdated = module.updated
    val updateReady get() = module.outdated && !isRemoved && isEnabled
}

@Parcelize
class OnlineModuleSubject(
    override val module: OnlineModule,
    override val autoLaunch: Boolean,
    override val notifyId: Int = Notifications.nextId()
) : Subject.Module() {
    override fun pendingIntent(context: Context) = FlashUtils.installIntent(context, file)
}

class ModuleViewModel : AsyncLoadViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val modules: List<ModuleItem> = emptyList(),
        val repositoryLoading: Boolean = false,
        val repositoryQuery: String = "",
        val repositoryModules: List<RepositoryModule> = emptyList(),
        val repositoryFailed: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private val repository = ModuleRepository(ServiceLocator.networkService)
    private var repositoryCandidates: List<RepositoryCandidate> = emptyList()
    private var repositorySources = ""
    private var repositoryLoadJob: Job? = null
    private var repositorySearchJob: Job? = null

    override suspend fun doLoadWork() {
        _uiState.update { it.copy(loading = true) }
        val moduleLoaded = Info.env.isActive &&
            withContext(Dispatchers.IO) { LocalModule.loaded() }
        if (moduleLoaded) {
            val modules = withContext(Dispatchers.Default) {
                LocalModule.installed().map { ModuleItem(it) }
            }
            _uiState.update { it.copy(loading = false, modules = modules) }
            if (Config.udongeBackgroundUpdates && Config.udongeBackgroundModules) {
                loadUpdateInfo()
            }
        } else {
            _uiState.update { it.copy(loading = false) }
        }
    }

    private val networkObserver: (Boolean) -> Unit = { startLoading() }

    init {
        Info.isConnected.observeForever(networkObserver)
    }

    override fun onCleared() {
        super.onCleared()
        Info.isConnected.removeObserver(networkObserver)
    }

    private suspend fun loadUpdateInfo() {
        withContext(Dispatchers.IO) {
            _uiState.value.modules.forEach { item ->
                if (item.module.fetch()) {
                    item.showUpdate = item.module.updateInfo != null
                }
            }
        }
    }

    fun confirmLocalInstall(uri: Uri) {
        navigateTo(Route.Flash(Const.Value.FLASH_ZIP, uri.toString()))
    }

    fun loadRepository() {
        val sources = Config.moduleRepositoryUrls
        if (sources != repositorySources) {
            repositoryLoadJob?.cancel()
            repositorySearchJob?.cancel()
            repositoryCandidates = emptyList()
            repositorySources = sources
            _uiState.update {
                it.copy(repositoryModules = emptyList(), repositoryFailed = false)
            }
        }
        if (repositoryCandidates.isNotEmpty() || repositoryLoadJob?.isActive == true) return
        repositoryLoadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(repositoryLoading = true, repositoryFailed = false)
            }
            repositoryCandidates = withContext(Dispatchers.IO) {
                repository.loadSources(sources)
            }
            if (repositoryCandidates.isEmpty()) {
                _uiState.update {
                    it.copy(repositoryLoading = false, repositoryFailed = true)
                }
            } else {
                searchRepository(_uiState.value.repositoryQuery)
            }
        }
    }

    fun searchRepository(query: String) {
        _uiState.update { it.copy(repositoryQuery = query) }
        repositorySearchJob?.cancel()
        if (repositoryCandidates.isEmpty()) {
            loadRepository()
            return
        }
        repositorySearchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(repositoryLoading = true, repositoryFailed = false)
            }
            val words = query.trim().lowercase().split(Regex("\\s+"))
                .filter(String::isNotBlank)
            val matches = withContext(Dispatchers.Default) {
                repositoryCandidates.asSequence()
                    .filter { candidate ->
                        if (words.isEmpty()) true else {
                            val searchable = listOf(
                                candidate.id,
                                candidate.name,
                                candidate.author,
                                candidate.description,
                            ).joinToString(" ").lowercase()
                            words.all(searchable::contains)
                        }
                    }
                    .take(50)
                    .toList()
            }
            val resolved = withContext(Dispatchers.IO) {
                repository.resolve(matches)
            }
            _uiState.update {
                it.copy(
                    repositoryLoading = false,
                    repositoryModules = resolved,
                    repositoryFailed = resolved.isEmpty() && words.isEmpty(),
                )
            }
        }
    }

    fun runAction(id: String, name: String) {
        navigateTo(Route.Action(id, name))
    }

    fun toggleEnabled(item: ModuleItem) {
        item.isEnabled = !item.isEnabled
        item.module.enable = item.isEnabled
    }

    fun toggleRemove(item: ModuleItem) {
        item.isRemoved = !item.isRemoved
        item.module.remove = item.isRemoved
    }
}
