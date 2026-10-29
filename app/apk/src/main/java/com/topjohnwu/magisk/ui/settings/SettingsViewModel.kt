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
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.hideapps.HideAppsRepository
import com.topjohnwu.magisk.ui.hideapps.HideAppsRootClient
import com.topjohnwu.magisk.ui.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel : BaseViewModel() {

    private val hideAppsRepo = HideAppsRepository(AppContext)
    private val _hideAppsEnabled = MutableStateFlow(hideAppsRepo.config.enabled)
    val hideAppsEnabled: StateFlow<Boolean> = _hideAppsEnabled.asStateFlow()

    val zygiskMismatch get() = Config.zygisk != Info.isZygiskEnabled

    var authenticate: (onSuccess: () -> Unit) -> Unit = { it() }

    fun refreshHideApps() {
        _hideAppsEnabled.value = hideAppsRepo.config.enabled
    }

    fun toggleHideApps(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                hideAppsRepo.setEnabled(enabled)
                HideAppsRootClient.syncCurrentConfig()
            }
            _hideAppsEnabled.value = enabled
        }
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

    fun withAuth(action: () -> Unit) = authenticate(action)

    fun notifyZygiskChange() {
        if (zygiskMismatch) showSnackbar(R.string.reboot_apply_change)
    }
}
