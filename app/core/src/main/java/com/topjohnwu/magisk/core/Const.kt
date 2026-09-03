package com.topjohnwu.magisk.core

import android.os.Build
import android.os.Process
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.BuildConfig.APP_VERSION_CODE

@Suppress("DEPRECATION")
object Const {

    val CPU_ABI: String get() = Build.SUPPORTED_ABIS[0]


    val CPU_ABI_32 =
        if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) null
        else Build.SUPPORTED_32_BIT_ABIS.firstOrNull()


    val SECURE_DIR   = BuildConfig.SECURE_DIR
    val DATABIN      = "${BuildConfig.SECURE_DIR}/${BuildConfig.DATA_DIR}"
    val MODULE_PATH  = "${BuildConfig.SECURE_DIR}/modules"
    val MAIN_BIN     = BuildConfig.MAIN_BIN_NAME
    val BUSYBOX_NAME = BuildConfig.BUSYBOX_NAME
    val INTERNAL_DIR = BuildConfig.INTERNAL_DIR
    val STUB_NAME    = BuildConfig.STUB_NAME
    val UDONGE_DIR   = BuildConfig.UDONGE_DIR
    val UDONGE_ARCHIVE = BuildConfig.UDONGE_ARCHIVE
    val TMPDIR       = BuildConfig.TMP_DIR
    val BACKUP_PREFIX = BuildConfig.BACKUP_PREFIX


    val USER_ID = Process.myUid() / 100000

    object Version {
        const val MIN_VERSION = "v22.0"
        const val MIN_VERCODE = 22000

        private fun isCanary() = (Info.env.versionCode % 100) != 0
        fun atLeast_24_0() = Info.env.versionCode >= 24000 || isCanary()
        fun atLeast_25_0() = Info.env.versionCode >= 25000 || isCanary()
        fun atLeast_28_0() = Info.env.versionCode >= 28000 || isCanary()
        fun atLeast_30_1() = Info.env.versionCode >= 30100 || isCanary()
    }

    object ID {
        const val CHECK_UPDATE_JOB_ID = 5
        const val DOWNLOAD_JOB_ID = 6
        const val BACKGROUND_UPDATE_JOB_ID = 7
    }

    object Url {
        const val PATREON_URL = "https://www.patreon.com/topjohnwu"
        const val SOURCE_CODE_URL = "https://github.com/sevcator/Reisenless"
        const val GITHUB_API_URL = "https://api.github.com/"
        const val INVALID_URL = "https://example.com/"
    }

    object Key {

        const val OPEN_SECTION = "section"
    }

    object Value {
        const val FLASH_ZIP = "flash"
        const val PATCH_FILE = "patch"
        const val DOWNLOAD = "download"
        const val FLASH_MAGISK = "magisk"
        const val FLASH_INACTIVE_SLOT = "slot"
        const val UNINSTALL = "uninstall"
    }

    object Nav {
        const val HOME = "home"
        const val SETTINGS = "settings"
        const val MODULES = "modules"
        const val SUPERUSER = "superuser"
    }
}
