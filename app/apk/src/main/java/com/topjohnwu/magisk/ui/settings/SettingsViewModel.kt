package com.topjohnwu.magisk.ui.settings

import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.magisk.core.sulist.SulistController
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.ui.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel : BaseViewModel() {

    private val _suListEnabled = MutableStateFlow(Config.sulist)
    val suListEnabled: StateFlow<Boolean> = _suListEnabled.asStateFlow()

    private val _suListBusy = MutableStateFlow(false)
    val suListBusy: StateFlow<Boolean> = _suListBusy.asStateFlow()

    val zygiskMismatch get() = Config.zygisk != Info.isZygiskEnabled

    var authenticate: (onSuccess: () -> Unit) -> Unit = { it() }

    init {
        if (Info.env.isActive && Const.Version.atLeast_24_0()) refreshSuList()
    }

    fun navigateToSuList() {
        navigateTo(Route.DenyList)
    }

    fun navigateToHideApps() {
        navigateTo(Route.HideApps)
    }

    fun createHosts() {
        viewModelScope.launch {
            RootUtils.addSystemlessHosts()
            AppContext.toast(R.string.settings_hosts_toast, Toast.LENGTH_SHORT)
        }
    }

    fun toggleSuList(enabled: Boolean) {
        if (_suListBusy.value) return
        viewModelScope.launch {
            _suListBusy.value = true
            try {
                val actual = withContext(Dispatchers.IO) {
                    runCatching { SulistController.setEnabled(enabled) }.getOrNull()
                }
                if (actual != null) {
                    _suListEnabled.value = actual
                }
                if (actual != enabled) {
                    showSnackbar(R.string.failure)
                }
            } finally {
                _suListBusy.value = false
            }
        }
    }

    private fun refreshSuList() {
        if (_suListBusy.value) return
        viewModelScope.launch {
            _suListBusy.value = true
            try {
                val actual = withContext(Dispatchers.IO) {
                    runCatching { SulistController.status() }.getOrNull()
                }
                if (actual != null) {
                    _suListEnabled.value = actual
                } else {
                    showSnackbar(R.string.failure)
                }
            } finally {
                _suListBusy.value = false
            }
        }
    }

    fun withAuth(action: () -> Unit) = authenticate(action)

    fun notifyZygiskChange() {
        if (zygiskMismatch) showSnackbar(R.string.reboot_apply_change)
    }
}
