package com.topjohnwu.magisk.ui.home

import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.ktx.await
import com.topjohnwu.magisk.utils.asFlow
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.topjohnwu.magisk.core.R as CoreR

class HomeViewModel : AsyncLoadViewModel() {

    enum class State {
        LOADING, INVALID, OUTDATED, UP_TO_DATE
    }

    data class UiState(
        val showUninstall: Boolean = false,
        val envFixCode: Int = 0,
        val magiskState: State = computeMagiskState(),
        val magiskInstalledVersion: String = computeMagiskInstalledVersion(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val magiskState get() = _uiState.value.magiskState
    val magiskInstalledVersion get() = _uiState.value.magiskInstalledVersion

    companion object {
        private var checkedEnv = false

        fun computeMagiskState() = when {
            Info.isRooted && Info.env.isUnsupported -> State.OUTDATED
            !Info.env.isActive -> State.INVALID
            Info.env.versionCode < BuildConfig.APP_VERSION_CODE -> State.OUTDATED
            else -> State.UP_TO_DATE
        }

        fun computeMagiskInstalledVersion() = Info.env.run {
            if (isActive)
                "$versionString ($versionCode)" + if (isDebug) " (D)" else ""
            else
                ""
        }
    }

    init {
        viewModelScope.launch {
            Info.isConnected.asFlow().collect {
                startLoading()
            }
        }
    }

    override suspend fun doLoadWork() {
        _uiState.update {
            it.copy(
                magiskState = computeMagiskState(),
                magiskInstalledVersion = computeMagiskInstalledVersion(),
            )
        }
        ensureEnv()
    }

    fun onDeletePressed() {
        _uiState.update { it.copy(showUninstall = true) }
    }

    fun onUninstallConsumed() {
        _uiState.update { it.copy(showUninstall = false) }
    }

    fun onEnvFixConsumed() {
        _uiState.update { it.copy(envFixCode = 0) }
    }

    private suspend fun ensureEnv() {
        if (magiskState == State.INVALID || checkedEnv) return
        val cmd = "env_check ${Info.env.versionString} ${Info.env.versionCode}"
        val code = Shell.cmd(cmd).await().code
        if (code != 0) {
            _uiState.update { it.copy(envFixCode = code) }
        }
        checkedEnv = true
    }
}
