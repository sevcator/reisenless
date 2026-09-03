package com.topjohnwu.magisk.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.ktx.activity
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.magisk.core.sulist.SulistController
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.databinding.bindExtra
import com.topjohnwu.magisk.events.AuthEvent
import com.topjohnwu.magisk.events.SnackbarEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel : BaseViewModel(), BaseSettingsItem.Handler {

    val items = createItems()
    val extraBindings = bindExtra {
        it.put(BR.handler, this)
    }

    private fun createItems(): List<BaseSettingsItem> {
        val context = AppContext
        // Customization
        val list = mutableListOf(
            Customization,
            Theme, if (LocaleSetting.useLocaleManager) LanguageSystem else Language
        )
        // Manager
        list.addAll(listOf(
            AppSettings,
            UpdateChannel, UpdateChannelUrl, UpdateChecker, DownloadPath, RandNameToggle
        ))

        // Magisk
        if (Info.env.isActive) {
            list.addAll(listOf(
                Magisk,
                SystemlessHosts
            ))
            if (Const.Version.atLeast_24_0()) {
                list.addAll(listOf(Zygisk, SuList, SuListConfig, HideAppsConfig))
            }
            list.addAll(listOf(
                UdongeSettings, UdongeEnabled, UdongeBackgroundUpdates,
                UdongeKeyboxes, UdongeRomHiding, UdongeRomKeywords,
            ))
        }

        // Superuser
        if (Info.showSuperUser) {
            list.addAll(listOf(
                Superuser,
                Tapjack, Authentication, AccessMode, MultiuserMode, MountNamespaceMode,
                AutomaticResponse, RequestTimeout, SUNotification
            ))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                // Re-authenticate is not feasible on 8.0+
                list.add(Reauthenticate)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Can hide overlay windows on 12.0+
                list.remove(Tapjack)
            }
            if (Const.Version.atLeast_30_1()) {
                list.add(Restrict)
            }
        }

        return list
    }

    override fun onItemPressed(view: View, item: BaseSettingsItem, doAction: () -> Unit) {
        when (item) {
            SuList -> toggleSuList()
            DownloadPath -> withExternalRW(doAction)
            UpdateChecker -> withPostNotificationPermission(doAction)
            Authentication -> AuthEvent(doAction).publish()
            AutomaticResponse -> if (Config.suAuth) AuthEvent(doAction).publish() else doAction()
            else -> doAction()
        }
    }

    init {
        if (Info.env.isActive && Const.Version.atLeast_24_0()) refreshSuList()
    }

    private fun refreshSuList() {
        SuList.isEnabled = false
        viewModelScope.launch {
            val actual = withContext(Dispatchers.IO) {
                runCatching { SulistController.status() }.getOrNull()
            }
            if (actual != null) {
                SuList.updateRuntimeState(actual)
            } else {
                SnackbarEvent(R.string.failure).publish()
            }
            SuList.isEnabled = true
        }
    }

    private fun toggleSuList() {
        if (!SuList.isEnabled) return
        val desired = !SuList.value
        SuList.isEnabled = false
        viewModelScope.launch {
            val actual = withContext(Dispatchers.IO) {
                runCatching { SulistController.setEnabled(desired) }.getOrNull()
            }
            if (actual != null) SuList.updateRuntimeState(actual)
            if (actual != desired) SnackbarEvent(R.string.failure).publish()
            SuList.isEnabled = true
        }
    }

    override fun onItemAction(view: View, item: BaseSettingsItem) {
        when (item) {
            Theme -> SettingsFragmentDirections.actionSettingsFragmentToThemeFragment().navigate()
            LanguageSystem -> view.activity.startActivity(LocaleSetting.localeSettingsIntent)
            SystemlessHosts -> createHosts()
            SuListConfig -> SettingsFragmentDirections.actionSettingsFragmentToDenyFragment().navigate()
            HideAppsConfig -> SettingsFragmentDirections.actionSettingsFragmentToHideAppsFragment().navigate()
            UpdateChannel -> openUrlIfNecessary(view)
            Zygisk -> if (Zygisk.mismatch) SnackbarEvent(R.string.reboot_apply_change).publish()
            else -> Unit
        }
    }

    private fun openUrlIfNecessary(view: View) {
        UpdateChannelUrl.refresh()
        if (UpdateChannelUrl.isEnabled && UpdateChannelUrl.value.isBlank()) {
            UpdateChannelUrl.onPressed(view, this)
        }
    }

    private fun createHosts() {
        viewModelScope.launch {
            RootUtils.addSystemlessHosts()
            AppContext.toast(R.string.settings_hosts_toast, Toast.LENGTH_SHORT)
        }
    }
}
