plugins {
    alias(libs.plugins.android.application)
}

setupStubApk()

android {
    namespace = "com.topjohnwu.magisk.anchor"
    enableKotlin = false

    buildFeatures {
        buildConfig = false
    }

    defaultConfig {
        applicationId = "${Config.appPackageName}.anchor"
        versionCode = 1
        versionName = "1.0"
    }
}
