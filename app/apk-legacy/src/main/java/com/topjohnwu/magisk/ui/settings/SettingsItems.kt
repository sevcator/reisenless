package com.topjohnwu.magisk.ui.settings

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.Udonge
import com.topjohnwu.magisk.core.ktx.activity
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.core.utils.TextHolder
import com.topjohnwu.magisk.core.utils.asText
import com.topjohnwu.magisk.ui.hideapps.HideAppsRootClient
import com.topjohnwu.magisk.view.MagiskDialog
import com.topjohnwu.superuser.Shell
import com.topjohnwu.magisk.core.R as CoreR

// --- Customization

object Customization : BaseSettingsItem.Section() {
    override val title = CoreR.string.settings_customization.asText()
}

object Language : BaseSettingsItem.Selector() {
    private val names: Array<String> get() = LocaleSetting.available.names
    private val tags: Array<String> get() = LocaleSetting.available.tags

    override var value
        get() = tags.indexOf(Config.locale)
        set(value) {
            Config.locale = tags[value]
        }

    override val title = CoreR.string.language.asText()

    override fun entries(res: Resources) = names
    override fun descriptions(res: Resources) = names
}

object LanguageSystem : BaseSettingsItem.Blank() {
    override val title = CoreR.string.language.asText()
    override val description: TextHolder
        get() {
            val locale = LocaleSetting.instance.appLocale
            return locale?.getDisplayName(locale)?.asText() ?: CoreR.string.system_default.asText()
        }
}

object Theme : BaseSettingsItem.Blank() {
    override val icon = R.drawable.ic_paint
    override val title = CoreR.string.section_theme.asText()
}

// --- Magisk

object Magisk : BaseSettingsItem.Section() {
    override val title = CoreR.string.magisk.asText()
}

object SystemlessHosts : BaseSettingsItem.Blank() {
    override val title = CoreR.string.settings_hosts_title.asText()
    override val description = CoreR.string.settings_hosts_summary.asText()
}

object Zygisk : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.zygisk.asText()
    override val description get() =
        if (mismatch) CoreR.string.reboot_apply_change.asText()
        else CoreR.string.settings_zygisk_summary.asText()
    override var value
        get() = Config.zygisk
        set(value) {
            Config.zygisk = value
            notifyPropertyChanged(BR.description)
        }
    val mismatch get() = value != Info.isZygiskEnabled
}

object SuList : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_sulist_title.asText()
    override val description get() = CoreR.string.settings_sulist_summary.asText()

    // Filled by the guarded runtime refresh, never query the daemon on class initialization.
    override var value = false
        set(value) {
            field = value
            notifyPropertyChanged(BR.checked)
        }

    fun updateRuntimeState(enabled: Boolean) {
        value = enabled
    }
}

object SuListConfig : BaseSettingsItem.Blank() {
    override val title = CoreR.string.settings_sulist_config_title.asText()
    override val description = CoreR.string.settings_sulist_config_summary.asText()
}

object HideAppsConfig : BaseSettingsItem.Blank() {
    override val title = CoreR.string.hide_apps_title.asText()
    override val description = CoreR.string.hide_apps_summary.asText()
}

object UdongeSettings : BaseSettingsItem.Section() {
    override val title = CoreR.string.udonge.asText()
}

object UdongeEnabled : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.udonge_integrity_title.asText()
    override val description = CoreR.string.udonge_integrity_summary.asText()
    override var value
        get() = Config.udongeEnabled
        set(value) { Shell.EXECUTOR.execute { Udonge.setEnabled(value) } }
}

object UdongeBackgroundUpdates : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.udonge_background_updates_title.asText()
    override val description = CoreR.string.udonge_background_updates_summary.asText()
    override var value
        get() = Config.udongeBackgroundUpdates
        set(value) { Shell.EXECUTOR.execute { Udonge.setBackgroundUpdates(value) } }
}

private fun textListDialog(
    view: View,
    title: Int,
    hint: Int,
    initial: String,
    save: (String) -> Unit,
) = MagiskDialog(view.activity).apply {
    val input = EditText(view.context).apply {
        this.hint = resources.getString(hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 4
        maxLines = 10
        setText(initial)
    }
    setTitle(title)
    setView(input)
    setButton(MagiskDialog.ButtonType.POSITIVE) {
        text = android.R.string.ok
        onClick { save(input.text.toString()) }
    }
    setButton(MagiskDialog.ButtonType.NEGATIVE) { text = android.R.string.cancel }
    show()
}

object UdongeKeyboxes : BaseSettingsItem.Blank() {
    override val title = CoreR.string.udonge_keybox_list_title.asText()
    override val description = CoreR.string.udonge_keybox_list_summary.asText()

    override fun onPressed(view: View, handler: Handler) {
        textListDialog(
            view,
            CoreR.string.udonge_keybox_list_title,
            CoreR.string.udonge_keybox_hint,
            Config.udongeKeyboxUrls,
        ) { value ->
            Shell.EXECUTOR.execute {
                if (Udonge.setKeyboxUrls(value)) Udonge.refreshKeyboxes()
            }
        }
    }
}

object UdongeRomHiding : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.udonge_rom_keywords_title.asText()
    override val description = CoreR.string.udonge_rom_keywords_summary.asText()
    override var value
        get() = Config.udongeRomHidingEnabled
        set(value) {
            Shell.EXECUTOR.execute {
                if (Udonge.setRomHidingEnabled(value)) {
                    HideAppsRootClient.syncRomKeywordsHideApps(
                        if (value) Config.udongeRomKeywords else "",
                    )
                }
            }
        }
}

object UdongeRomKeywords : BaseSettingsItem.Blank() {
    override val title = CoreR.string.udonge_rom_keywords_config_title.asText()
    override val description = CoreR.string.udonge_rom_keywords_config_summary.asText()

    override fun onPressed(view: View, handler: Handler) {
        textListDialog(
            view,
            CoreR.string.udonge_rom_keywords_config_title,
            CoreR.string.udonge_rom_keywords_hint,
            Config.udongeRomKeywords,
        ) { value ->
            Shell.EXECUTOR.execute {
                if (Udonge.setRomKeywords(value)) {
                    HideAppsRootClient.syncRomKeywordsHideApps(value)
                }
            }
        }
    }
}

// --- Superuser

object Tapjack : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_su_tapjack_title.asText()
    override val description = CoreR.string.settings_su_tapjack_summary.asText()
    override var value by Config::suTapjack
}

object Authentication : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_su_auth_title.asText()
    override var description = CoreR.string.settings_su_auth_summary.asText()
    override var value by Config::suAuth

    override fun refresh() {
        isEnabled = Info.isDeviceSecure
        if (!isEnabled) {
            description = CoreR.string.settings_su_auth_insecure.asText()
        }
    }
}

object Superuser : BaseSettingsItem.Section() {
    override val title = CoreR.string.superuser.asText()
}

object AccessMode : BaseSettingsItem.Selector() {
    override val title = CoreR.string.superuser_access.asText()
    override val entryRes = CoreR.array.su_access
    override var value by Config::rootMode
}

object MultiuserMode : BaseSettingsItem.Selector() {
    override val title = CoreR.string.multiuser_mode.asText()
    override val entryRes = CoreR.array.multiuser_mode
    override val descriptionRes = CoreR.array.multiuser_summary
    override var value by Config::suMultiuserMode

    override fun refresh() {
        isEnabled = Const.USER_ID == 0
    }
}

object MountNamespaceMode : BaseSettingsItem.Selector() {
    override val title = CoreR.string.mount_namespace_mode.asText()
    override val entryRes = CoreR.array.namespace
    override val descriptionRes = CoreR.array.namespace_summary
    override var value by Config::suMntNamespaceMode
}

object AutomaticResponse : BaseSettingsItem.Selector() {
    override val title = CoreR.string.auto_response.asText()
    override val entryRes = CoreR.array.auto_response
    override var value by Config::suAutoResponse
}

object RequestTimeout : BaseSettingsItem.Selector() {
    override val title = CoreR.string.request_timeout.asText()
    override val entryRes = CoreR.array.request_timeout

    private val entryValues = listOf(10, 15, 20, 30, 45, 60)
    override var value = entryValues.indexOfFirst { it == Config.suDefaultTimeout }
        set(value) {
            field = value
            Config.suDefaultTimeout = entryValues[value]
        }
}

object SUNotification : BaseSettingsItem.Selector() {
    override val title = CoreR.string.superuser_notification.asText()
    override val entryRes = CoreR.array.su_notification
    override var value by Config::suNotification
}

object Reauthenticate : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_su_reauth_title.asText()
    override val description = CoreR.string.settings_su_reauth_summary.asText()
    override var value by Config::suReAuth

    override fun refresh() {
        isEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
    }
}

object Restrict : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_su_restrict_title.asText()
    override val description = CoreR.string.settings_su_restrict_summary.asText()
    override var value by Config::suRestrict
}
