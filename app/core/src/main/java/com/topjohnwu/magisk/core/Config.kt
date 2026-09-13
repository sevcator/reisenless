package com.topjohnwu.magisk.core

import androidx.core.content.edit
import com.topjohnwu.magisk.core.di.ServiceLocator
import com.topjohnwu.magisk.core.model.ColorMode
import com.topjohnwu.magisk.core.repository.DBConfig
import com.topjohnwu.magisk.core.repository.PreferenceConfig
import com.topjohnwu.magisk.core.utils.LocaleSetting
import kotlinx.coroutines.GlobalScope

object Config : PreferenceConfig, DBConfig {

    const val DEFAULT_UDONGE_KEYBOX_URLS = ""

    override val stringDB get() = ServiceLocator.stringDB
    override val settingsDB get() = ServiceLocator.settingsDB
    override val context get() = ServiceLocator.deContext
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override val coroutineScope get() = GlobalScope

    object Key {

        const val ROOT_ACCESS = "root_access"
        const val SU_MULTIUSER_MODE = "multiuser_mode"
        const val SU_MNT_NS = "mnt_ns"
        const val SU_BIOMETRIC = "su_biometric"
        const val ZYGISK = "zygisk"
        const val SULIST = "sulist"
        const val BOOTLOOP = "bootloop"
        const val KEYSTORE = "keystore"


        const val SU_REQUEST_TIMEOUT = "su_request_timeout"
        const val SU_AUTO_RESPONSE = "su_auto_response"
        const val SU_NOTIFICATION = "su_notification"
        const val SU_REAUTH = "su_reauth"
        const val SU_TAPJACK = "su_tapjack"
        const val SU_RESTRICT = "su_restrict"
        const val LOCALE = "locale"
        const val DARK_THEME = "dark_theme_extended"
        const val COLOR_MODE = "color_mode"
        const val ACCENT_COLOR = "accent_color"
        const val SAFETY = "safety_notice"
        const val THEME_ORDINAL = "theme_ordinal"
        const val UDONGE_ENABLED = "udonge_enabled"
        const val UDONGE_BACKGROUND_UPDATES = "udonge_background_updates"
        const val UDONGE_KEYBOX_URLS = "udonge_keybox_urls_v2"
        const val UDONGE_ROM_KEYWORDS = "udonge_rom_keywords"
        const val UDONGE_ROM_HIDING = "udonge_rom_hiding"

    }

    object Value {

        const val ROOT_ACCESS_DISABLED = 0
        const val ROOT_ACCESS_APPS_ONLY = 1
        const val ROOT_ACCESS_ADB_ONLY = 2
        const val ROOT_ACCESS_APPS_AND_ADB = 3


        const val MULTIUSER_MODE_OWNER_ONLY = 0
        const val MULTIUSER_MODE_OWNER_MANAGED = 1
        const val MULTIUSER_MODE_USER = 2


        const val NAMESPACE_MODE_GLOBAL = 0
        const val NAMESPACE_MODE_REQUESTER = 1
        const val NAMESPACE_MODE_ISOLATE = 2


        const val NO_NOTIFICATION = 0
        const val NOTIFICATION_TOAST = 1
        const val NOTIFICATION_STATUS_BAR = 2


        const val SU_PROMPT = 0
        const val SU_AUTO_DENY = 1
        const val SU_AUTO_ALLOW = 2

        const val THEME_LIGHT = 1
        const val THEME_DARK = 2


        val TIMEOUT_LIST = longArrayOf(0, -1, 10, 20, 30, 60)
    }

    @JvmField var keepVerity = false
    @JvmField var keepEnc = false
    @JvmField var recovery = false
    var bootloop by dbSettings(Key.BOOTLOOP, 0)

    var safetyNotice by preference(Key.SAFETY, true)
    var darkTheme by preference(Key.DARK_THEME, -1)
    var themeOrdinal by preference(Key.THEME_ORDINAL, 0)
    var colorMode by preference(Key.COLOR_MODE, ColorMode.MONET_SYSTEM.value)

    private var localePrefs by preference(Key.LOCALE, "")
    var accentColor by preference(Key.ACCENT_COLOR, 0xFFC95BC8.toInt())
    var udongeEnabled by preference(Key.UDONGE_ENABLED, false)
    var udongeBackgroundUpdates by preference(Key.UDONGE_BACKGROUND_UPDATES, false)
    private var storedUdongeKeyboxUrls by preference(
        Key.UDONGE_KEYBOX_URLS,
        DEFAULT_UDONGE_KEYBOX_URLS,
    )
    var udongeKeyboxUrls
        get() = storedUdongeKeyboxUrls.ifBlank { DEFAULT_UDONGE_KEYBOX_URLS }
        set(value) { storedUdongeKeyboxUrls = value }
    var udongeRomKeywords by preference(Key.UDONGE_ROM_KEYWORDS, "")
    var udongeRomHidingEnabled by preference(Key.UDONGE_ROM_HIDING, false)
    var locale
        get() = localePrefs
        set(value) {
            localePrefs = value
            LocaleSetting.instance.setLocale(value)
        }

    var zygisk by dbSettings(Key.ZYGISK, Info.isEmulator)
    var sulist by dbSettings(Key.SULIST, true)
    var keyStoreRaw by dbStrings(Key.KEYSTORE, "", true)

    var suDefaultTimeout by preferenceStrInt(Key.SU_REQUEST_TIMEOUT, 10)
    var suAutoResponse by preferenceStrInt(Key.SU_AUTO_RESPONSE, Value.SU_PROMPT)
    var suNotification by preferenceStrInt(Key.SU_NOTIFICATION, Value.NOTIFICATION_TOAST)
    var rootMode by dbSettings(Key.ROOT_ACCESS, Value.ROOT_ACCESS_APPS_AND_ADB)
    var suMntNamespaceMode by dbSettings(Key.SU_MNT_NS, Value.NAMESPACE_MODE_REQUESTER)
    var suMultiuserMode by dbSettings(Key.SU_MULTIUSER_MODE, Value.MULTIUSER_MODE_OWNER_ONLY)
    private var suBiometric by dbSettings(Key.SU_BIOMETRIC, false)
    var suAuth
        get() = Info.isDeviceSecure && suBiometric
        set(value) {
            suBiometric = value
        }
    var suReAuth by preference(Key.SU_REAUTH, false)
    var suTapjack by preference(Key.SU_TAPJACK, true)
    var suRestrict by preference(Key.SU_RESTRICT, false)

    private const val SU_FINGERPRINT = "su_fingerprint"
    fun init() {
        prefs.edit {
            if (prefs.getBoolean(SU_FINGERPRINT, false))
                suBiometric = true
            remove(SU_FINGERPRINT)
        }
    }
}
