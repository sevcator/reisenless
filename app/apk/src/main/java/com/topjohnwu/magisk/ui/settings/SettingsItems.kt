package com.topjohnwu.magisk.ui.settings

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.databinding.Bindable
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.Udonge
import com.topjohnwu.magisk.core.ktx.activity
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.databinding.set
import com.topjohnwu.magisk.core.utils.asText
import com.topjohnwu.magisk.hideapps.HideAppsRepository
import com.topjohnwu.magisk.ui.hideapps.HideAppsRootClient
import com.topjohnwu.magisk.view.MagiskDialog
import com.topjohnwu.superuser.Shell
import com.topjohnwu.magisk.core.R as CoreR

// --- App

object Customization : BaseSettingsItem.Section() {
    override val title = CoreR.string.settings_customization.asText()
}

object ReisenlessSettings : BaseSettingsItem.Section() {
    override val title = CoreR.string.home_app_title.asText()
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

object Theme : BaseSettingsItem.Blank() {
    override val icon = R.drawable.ic_paint
    override val title = CoreR.string.section_theme.asText()
}

// --- App

object Hide : BaseSettingsItem.Blank() {
    override val title = CoreR.string.settings_hide_app_title.asText()
    override val description = CoreR.string.settings_hide_app_summary.asText()

    override fun onPressed(view: View, handler: Handler) {
        handler.onItemPressed(view, this) {
            MagiskDialog(view.activity).apply {
                setTitle(CoreR.string.settings_hide_app_title)
                setMessage(CoreR.string.hide_app_randomize_confirmation)
                setButton(MagiskDialog.ButtonType.POSITIVE) {
                    text = android.R.string.ok
                    onClick { handler.onItemAction(view, this@Hide) }
                }
                setButton(MagiskDialog.ButtonType.NEGATIVE) {
                    text = android.R.string.cancel
                }
                setCancelable(true)
                show()
            }
        }
    }
}

object Restore : BaseSettingsItem.Blank() {
    override val title = CoreR.string.settings_restore_app_title.asText()
    override val description = CoreR.string.settings_restore_app_summary.asText()

    override fun onPressed(view: View, handler: Handler) {
        handler.onItemPressed(view, this) {
            MagiskDialog(view.activity).apply {
                setTitle(CoreR.string.settings_restore_app_title)
                setMessage(CoreR.string.restore_app_confirmation)
                setButton(MagiskDialog.ButtonType.POSITIVE) {
                    text = android.R.string.ok
                    onClick { handler.onItemAction(view, this@Restore) }
                }
                setButton(MagiskDialog.ButtonType.NEGATIVE) {
                    text = android.R.string.cancel
                }
                setCancelable(true)
                show()
            }
        }
    }
}

object AddShortcut : BaseSettingsItem.Blank() {
    override val title = CoreR.string.add_shortcut_title.asText()
    override val description = CoreR.string.setting_add_shortcut_summary.asText()
}

object DoHToggle : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_doh_title.asText()
    override val description = CoreR.string.settings_doh_description.asText()
    override var value by Config::doh
}

object RepositorySearcher : BaseSettingsItem.SplitToggle() {
    override val title = CoreR.string.repository_searcher.asText()
    override val description = CoreR.string.repository_searcher_summary.asText()
    override var value by Config::repositorySearcherEnabled

    override fun onPressed(view: View, handler: Handler) {
        handler.onItemPressed(view, this) {
            val input = EditText(view.context).apply {
                hint = view.resources.getString(CoreR.string.repository_links_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 5
                maxLines = 12
                setText(Config.moduleRepositoryUrls)
                setSelection(text.length)
            }
            MagiskDialog(view.activity).apply {
                setTitle(CoreR.string.repository_links_title)
                setView(input)
                setButton(MagiskDialog.ButtonType.POSITIVE) {
                    text = android.R.string.ok
                    onClick {
                        Config.moduleRepositoryUrls = input.text.lineSequence()
                            .map(String::trim)
                            .filter(String::isNotBlank)
                            .distinct()
                            .joinToString("\n")
                    }
                }
                setButton(MagiskDialog.ButtonType.NEGATIVE) {
                    text = android.R.string.cancel
                }
            }.show()
        }
    }
}

object SystemlessHosts : BaseSettingsItem.Blank() {
    override val title = CoreR.string.settings_hosts_title.asText()
    override val description = CoreR.string.settings_hosts_summary.asText()
}

// --- Magisk

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

object SuList : BaseSettingsItem.SplitToggle() {
    override val title = CoreR.string.settings_sulist_title.asText()
    override val description = CoreR.string.settings_sulist_summary.asText()
    override var value by Config::sulist
}

object HideApps : BaseSettingsItem.SplitToggle() {
    override val title = CoreR.string.hide_apps_title.asText()
    override val description = CoreR.string.hide_apps_summary.asText()
    override var value
        get() = HideAppsRepository(com.topjohnwu.magisk.core.AppContext).config.enabled
        set(value) {
            HideAppsRepository(com.topjohnwu.magisk.core.AppContext).setEnabled(value)
        }

    override fun refresh() = notifyPropertyChanged(BR.checked)
}

object UdongeSettings : BaseSettingsItem.Section() {
    override val title = CoreR.string.udonge.asText()
}

object UdongeBackgroundUpdates : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.udonge_background_updates_title.asText()
    override val description = CoreR.string.udonge_background_updates_summary.asText()
    override var value by Config::udongeBackgroundUpdates
}

object UdongeKeyboxes : BaseSettingsItem.Blank() {
    override val title = CoreR.string.udonge_keybox_list_title.asText()
    override val description = CoreR.string.udonge_keybox_list_summary.asText()

    override fun onPressed(view: View, handler: Handler) {
        handler.onItemPressed(view, this) {
            val input = EditText(view.context).apply {
                hint = view.resources.getString(CoreR.string.udonge_keybox_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 4
                maxLines = 10
                setText(Config.udongeKeyboxUrls)
                setSelection(text.length)
            }
            MagiskDialog(view.activity).apply {
                setTitle(CoreR.string.udonge_keybox_list_title)
                setView(input)
                setButton(MagiskDialog.ButtonType.POSITIVE) {
                    text = android.R.string.ok
                    onClick {
                        Shell.EXECUTOR.execute {
                            if (Udonge.setKeyboxUrls(input.text.toString())) {
                                Udonge.refreshKeyboxes()
                            }
                        }
                    }
                }
                setButton(MagiskDialog.ButtonType.NEGATIVE) {
                    text = android.R.string.cancel
                }
            }.show()
        }
    }
}

object UdongeRomKeywords : BaseSettingsItem.Blank() {
    override val title = CoreR.string.udonge_rom_keywords_title.asText()
    override val description = CoreR.string.udonge_rom_keywords_summary.asText()

    override fun onPressed(view: View, handler: Handler) {
        handler.onItemPressed(view, this) {
            val input = EditText(view.context).apply {
                hint = view.resources.getString(CoreR.string.udonge_rom_keywords_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 4
                maxLines = 10
                setText(Config.udongeRomKeywords)
                setSelection(text.length)
            }
            MagiskDialog(view.activity).apply {
                setTitle(CoreR.string.udonge_rom_keywords_title)
                setView(input)
                setButton(MagiskDialog.ButtonType.POSITIVE) {
                    text = android.R.string.ok
                    onClick {
                        val keywords = input.text.toString()
                        Shell.EXECUTOR.execute {
                            if (Udonge.setRomKeywords(keywords)) {
                                HideAppsRootClient.syncRomKeywordsHideApps(keywords)
                            }
                        }
                    }
                }
                setButton(MagiskDialog.ButtonType.NEGATIVE) {
                    text = android.R.string.cancel
                }
            }.show()
        }
    }
}

object Superuser : BaseSettingsItem.Section() {
    override val title = CoreR.string.superuser.asText()
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

object AutomaticResponse : BaseSettingsItem.Selector() {
    override val title = CoreR.string.auto_response.asText()
    override val entryRes = CoreR.array.auto_response
    override var value by Config::suAutoResponse
}

object RequestTimeout : BaseSettingsItem.Selector() {
    override val title = CoreR.string.request_timeout.asText()
    override val entryRes = CoreR.array.request_timeout

    private val entryValues = listOf(10, 15, 20, 30, 45, 60)
    override var value = entryValues.indexOf(Config.suDefaultTimeout).coerceAtLeast(0)
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
