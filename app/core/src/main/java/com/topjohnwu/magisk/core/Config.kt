package com.topjohnwu.magisk.core

import android.os.Bundle
import androidx.core.content.edit
import com.topjohnwu.magisk.core.di.ServiceLocator
import com.topjohnwu.magisk.core.repository.DBConfig
import com.topjohnwu.magisk.core.repository.PreferenceConfig
import com.topjohnwu.magisk.core.utils.LocaleSetting
import kotlinx.coroutines.GlobalScope

object Config : PreferenceConfig, DBConfig {

    const val DEFAULT_UDONGE_KEYBOX_URLS =
        "https://raw.githubusercontent.com/sevcator/Reisenless/master/udonge/payload/defaults/keybox.xml\n" +
        "https://gist.githubusercontent.com/GreyElaina/2401596f3b8a01f8602768ad5221e2fd/raw/kb_b.xml"

    const val DEFAULT_MODULE_REPOSITORIES =
        "https://gr.dergoogler.com/gmr/"

    override val stringDB get() = ServiceLocator.stringDB
    override val settingsDB get() = ServiceLocator.settingsDB
    override val context get() = ServiceLocator.deContext
    override val coroutineScope get() = GlobalScope

    object Key {
        // db configs
        const val ROOT_ACCESS = "root_access"
        const val SU_MULTIUSER_MODE = "multiuser_mode"
        const val SU_MNT_NS = "mnt_ns"
        const val SU_BIOMETRIC = "su_biometric"
        const val ZYGISK = "zygisk"
        const val SULIST = "sulist"
        const val BOOTLOOP = "bootloop"
        const val SU_MANAGER = "requester"
        const val KEYSTORE = "keystore"
        const val MIGRATION_SOURCE = "migration_source"
        const val MIGRATION_TARGET = "migration_target"

        // prefs
        const val SU_REQUEST_TIMEOUT = "su_request_timeout"
        const val SU_AUTO_RESPONSE = "su_auto_response"
        const val SU_NOTIFICATION = "su_notification"
        const val SU_REAUTH = "su_reauth"
        const val SU_TAPJACK = "su_tapjack"
        const val LOCALE = "locale"
        const val DARK_THEME = "dark_theme_extended"
        const val ACCENT_PRIMARY = "accent_primary"
        const val ACCENT_SECONDARY = "accent_secondary"
        const val SAFETY = "safety_notice"
        const val ASKED_HOME = "asked_home"
        const val DOH = "doh"
        const val UDONGE_ENABLED = "udonge_enabled"
        const val UDONGE_BACKGROUND_UPDATES = "udonge_background_updates"
        const val UDONGE_BACKGROUND_MODULES = "udonge_background_modules"
        const val UDONGE_BACKGROUND_KEYBOXES = "udonge_background_keyboxes"
        const val UDONGE_KEYBOX_URLS = "udonge_keybox_urls"
        const val UDONGE_ROM_KEYWORDS = "udonge_rom_keywords"
        const val REPOSITORY_SEARCHER_ENABLED = "repository_searcher_enabled"
        const val MODULE_REPOSITORY_URLS = "module_repository_urls"

        val NO_MIGRATION = setOf(
            ASKED_HOME, SU_REQUEST_TIMEOUT, SU_AUTO_RESPONSE, SU_REAUTH, SU_TAPJACK,
        )
    }

    object Value {
        // root access mode
        const val ROOT_ACCESS_DISABLED = 0
        const val ROOT_ACCESS_APPS_ONLY = 1
        const val ROOT_ACCESS_ADB_ONLY = 2
        const val ROOT_ACCESS_APPS_AND_ADB = 3

        // su multiuser
        const val MULTIUSER_MODE_OWNER_ONLY = 0
        const val MULTIUSER_MODE_OWNER_MANAGED = 1
        const val MULTIUSER_MODE_USER = 2

        // su mnt ns
        const val NAMESPACE_MODE_GLOBAL = 0
        const val NAMESPACE_MODE_REQUESTER = 1
        const val NAMESPACE_MODE_ISOLATE = 2

        // su notification
        const val NO_NOTIFICATION = 0
        const val NOTIFICATION_TOAST = 1

        // su auto response
        const val SU_PROMPT = 0
        const val SU_AUTO_DENY = 1
        const val SU_AUTO_ALLOW = 2

        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        // su timeout
        val TIMEOUT_LIST = longArrayOf(0, -1, 10, 20, 30, 60)
    }

    @JvmField var keepVerity = false
    @JvmField var keepEnc = false
    @JvmField var recovery = false
    var suListActive = false

    var askedHome by preference(Key.ASKED_HOME, false)
    var bootloop by dbSettings(Key.BOOTLOOP, 0)

    var safetyNotice by preference(Key.SAFETY, false)
    private var storedDarkTheme by preference(Key.DARK_THEME, Value.THEME_LIGHT)
    var darkTheme
        get() = if (storedDarkTheme == Value.THEME_DARK) {
            Value.THEME_DARK
        } else {
            Value.THEME_LIGHT
        }
        set(value) {
            storedDarkTheme = if (value == Value.THEME_DARK) {
                Value.THEME_DARK
            } else {
                Value.THEME_LIGHT
            }
        }
    private var storedAccentPrimary by preference(Key.ACCENT_PRIMARY, 0xFFF4A6C1.toInt())
    private var storedAccentSecondary by preference(Key.ACCENT_SECONDARY, 0xFFF4A6C1.toInt())
    var accentPrimary
        get() = migrateAccent(storedAccentPrimary, true)
        set(value) { storedAccentPrimary = value }
    var accentSecondary
        get() = migrateAccent(storedAccentSecondary, false)
        set(value) { storedAccentSecondary = value }
    var udongeEnabled by preference(Key.UDONGE_ENABLED, true)
    var udongeBackgroundUpdates by preference(Key.UDONGE_BACKGROUND_UPDATES, false)
    var udongeBackgroundModules by preference(Key.UDONGE_BACKGROUND_MODULES, true)
    var udongeBackgroundKeyboxes by preference(Key.UDONGE_BACKGROUND_KEYBOXES, true)
    private var storedUdongeKeyboxUrls by preference(
        Key.UDONGE_KEYBOX_URLS,
        DEFAULT_UDONGE_KEYBOX_URLS,
    )
    var udongeKeyboxUrls
        get() = storedUdongeKeyboxUrls.ifBlank { DEFAULT_UDONGE_KEYBOX_URLS }
        set(value) { storedUdongeKeyboxUrls = value }
    var udongeRomKeywords by preference(Key.UDONGE_ROM_KEYWORDS, "")
    var repositorySearcherEnabled by preference(Key.REPOSITORY_SEARCHER_ENABLED, true)
    var moduleRepositoryUrls by preference(
        Key.MODULE_REPOSITORY_URLS,
        DEFAULT_MODULE_REPOSITORIES,
    )
    private var localePrefs by preference(Key.LOCALE, "")
    var doh by preference(Key.DOH, false)
    var locale
        get() = localePrefs
        set(value) {
            localePrefs = value
            LocaleSetting.instance.setLocale(value)
        }

    var zygisk by dbSettings(Key.ZYGISK, Info.isEmulator)
    var sulist by dbSettings(Key.SULIST, true)
    var suManager by dbStrings(Key.SU_MANAGER, "", true)
    var keyStoreRaw by dbStrings(Key.KEYSTORE, "", true)
    var migrationSource by dbStrings(Key.MIGRATION_SOURCE, "", true)
    var migrationTarget by dbStrings(Key.MIGRATION_TARGET, "", true)

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

    private const val SU_FINGERPRINT = "su_fingerprint"

    private fun migrateAccent(value: Int, primary: Boolean): Int {
        if (value !in 0..7) return value
        val colors = if (primary) {
            intArrayOf(
                0xFFF4A6C1.toInt(), 0xFF7E57C2.toInt(), 0xFF4EAFF5.toInt(),
                0xFF68A17F.toInt(), 0xFFF2B90D.toInt(), 0xFFDB7366.toInt(),
                0xFF009688.toInt(), 0xFF607D8B.toInt(),
            )
        } else {
            intArrayOf(
                0xFFD97A9C.toInt(), 0xFF5E35B1.toInt(), 0xFF3E78AF.toInt(),
                0xFF2F6D43.toInt(), 0xFFB29667.toInt(), 0xFFB65247.toInt(),
                0xFF00796B.toInt(), 0xFF455A64.toInt(),
            )
        }
        return colors[value]
    }

    fun toBundle(): Bundle {
        val map = prefs.all - Key.NO_MIGRATION
        return Bundle().apply {
            for ((key, value) in map) {
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun fromBundle(bundle: Bundle) {
        val keys = bundle.keySet().apply { removeAll(Key.NO_MIGRATION) }
        prefs.edit {
            for (key in keys) {
                when (val value = bundle.get(key)) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
        }
    }

    fun init(bundle: Bundle?) {
        if (bundle != null && prefs.all.isEmpty()) {
            fromBundle(bundle)
        }
        prefs.edit {
            if (prefs.getBoolean(SU_FINGERPRINT, false))
                suBiometric = true
            remove(SU_FINGERPRINT)
        }
    }
}
