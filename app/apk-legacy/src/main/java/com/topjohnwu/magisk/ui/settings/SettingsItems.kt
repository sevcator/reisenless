package com.topjohnwu.magisk.ui.settings

import android.content.res.Resources
import android.os.Build
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.core.utils.TextHolder
import com.topjohnwu.magisk.core.utils.asText
import com.topjohnwu.magisk.core.R as CoreR

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
