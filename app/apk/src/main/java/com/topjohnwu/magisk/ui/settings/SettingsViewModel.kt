package com.topjohnwu.magisk.ui.settings

import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.isRunningAsStub
import com.topjohnwu.magisk.core.ktx.activity
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.magisk.core.Udonge
import com.topjohnwu.magisk.core.tasks.AppMigration
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.databinding.bindExtra
import com.topjohnwu.magisk.events.AddHomeIconEvent
import com.topjohnwu.magisk.events.AuthEvent
import com.topjohnwu.magisk.events.SnackbarEvent
import com.topjohnwu.magisk.ui.hideapps.HideAppsRootClient
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch

class SettingsViewModel : BaseViewModel(), BaseSettingsItem.Handler {

    val items = createItems()
    val extraBindings = bindExtra {
        it.put(BR.handler, this)
    }

    private fun createItems(): List<BaseSettingsItem> {
        val context = AppContext
        val hidden = context.packageName != BuildConfig.APP_PACKAGE_NAME

        // App
        val list = mutableListOf(
            Customization,
            Theme, Language, DoHToggle
        )
        if (isRunningAsStub && ShortcutManagerCompat.isRequestPinShortcutSupported(context))
            list.add(AddShortcut)

        // Reisenless
        list.add(ReisenlessSettings)
        list.add(RepositorySearcher)
        if (Const.USER_ID == 0) {
            list.add(if (hidden) Restore else Hide)
        }
        if (Info.env.isActive) {
            list.add(UdongeBackgroundUpdates)

            list.add(SystemlessHosts)
            if (Const.Version.atLeast_24_0()) {
                list.add(Zygisk)
            }
            list.add(SuList)
            list.add(HideApps)

            // Udonge contains only integrity, keyboxes, and ROM hiding.
            list.addAll(
                listOf(
                    UdongeSettings,
                    UdongeEnabled,
                    UdongeKeyboxes,
                    UdongeRomKeywords,
                )
            )
        }

        if (Info.showSuperUser) {
            list.addAll(listOf(
                Superuser,
                Authentication,
                AutomaticResponse,
                RequestTimeout,
                SUNotification,
            ))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                list.add(list.indexOf(Authentication), Tapjack)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                list.add(Reauthenticate)
            }
        }

        return list
    }

    override fun onItemPressed(view: View, item: BaseSettingsItem, doAction: () -> Unit) {
        when (item) {
            Authentication -> AuthEvent(doAction).publish()
            AutomaticResponse -> if (Config.suAuth) AuthEvent(doAction).publish() else doAction()
            else -> doAction()
        }
    }

    override fun onItemAction(view: View, item: BaseSettingsItem) {
        when (item) {
            Theme -> SettingsFragmentDirections.actionSettingsFragmentToThemeFragment().navigate()
            AddShortcut -> AddHomeIconEvent().publish()
            SystemlessHosts -> createHosts()
            SuList -> SettingsFragmentDirections.actionSettingsFragmentToDenyFragment().navigate()
            HideApps -> SettingsFragmentDirections.actionSettingsFragmentToHideAppsFragment().navigate()
            Hide -> viewModelScope.launch { AppMigration.hide(view.activity) }
            Restore -> viewModelScope.launch { AppMigration.restore(view.activity) }
            Zygisk -> if (Zygisk.mismatch) SnackbarEvent(R.string.reboot_apply_change).publish()
            else -> Unit
        }
    }

    override fun onItemToggleAction(view: View, item: BaseSettingsItem) {
        when (item) {
            SuList -> SnackbarEvent(R.string.reboot_apply_change).publish()
            HideApps -> {
                val requested = HideApps.value
                Shell.EXECUTOR.execute {
                    if (requested) {
                        Udonge.setEnabled(true)
                        Config.zygisk = true
                    }
                    HideAppsRootClient.syncCurrentConfig()
                }
                SnackbarEvent(R.string.reboot_apply_change).publish()
            }
            UdongeEnabled -> {
                val requested = UdongeEnabled.value
                Shell.EXECUTOR.execute {
                    if (!Udonge.setEnabled(requested) && Config.udongeEnabled == requested) {
                        Config.udongeEnabled = !requested
                        view.post { UdongeEnabled.notifyPropertyChanged(BR.checked) }
                    }
                }
            }
            UdongeBackgroundUpdates -> {
                val requested = UdongeBackgroundUpdates.value
                Shell.EXECUTOR.execute {
                    if (!Udonge.setBackgroundUpdates(requested) &&
                        Config.udongeBackgroundUpdates == requested
                    ) {
                        Config.udongeBackgroundUpdates = !requested
                        view.post {
                            UdongeBackgroundUpdates.notifyPropertyChanged(BR.checked)
                        }
                    }
                }
            }
            else -> onItemAction(view, item)
        }
    }

    private fun createHosts() {
        viewModelScope.launch {
            RootUtils.addSystemlessHosts()
            AppContext.toast(R.string.settings_hosts_toast, Toast.LENGTH_SHORT)
        }
    }
}
