plugins {
    alias(libs.plugins.android.library)
    kotlin("plugin.parcelize")
    alias(libs.plugins.moshix)
    alias(libs.plugins.wire)
}

setupCoreLib()

wire {
    kotlin {}
}

android {
    namespace = "com.topjohnwu.magisk.core"

    defaultConfig {
        buildConfigField("String", "APP_PACKAGE_NAME", "\"${Config.appPackageName}\"")
        buildConfigField("int", "APP_VERSION_CODE", "${Config.versionCode}")
        buildConfigField("String", "APP_VERSION_NAME", "\"${Config.version}\"")
        buildConfigField("int", "STUB_VERSION", Config.stubVersion)
        buildConfigField("String", "SECURE_DIR", "\"${Config.secureDir}\"")
        buildConfigField("String", "MAIN_BIN_NAME", "\"${Config.mainBinName}\"")
        buildConfigField("String", "DATA_DIR", "\"${Config.dataDir}\"")
        buildConfigField("String", "DB_NAME", "\"${Config.dbName}\"")
        buildConfigField("String", "INTERNAL_DIR", "\"${Config.internalDir}\"")
        buildConfigField("String", "POLICY_NAME", "\"${Config.policyName}\"")
        buildConfigField("String", "BIN32_NAME", "\"${Config.bin32Name}\"")
        buildConfigField("String", "BUSYBOX_NAME", "\"${Config.busyboxName}\"")
        buildConfigField("String", "STUB_NAME", "\"${Config.stubName}\"")
        buildConfigField("String", "INIT_LD_NAME", "\"${Config.initLdName}\"")
        buildConfigField("String", "UDONGE_DIR", "\"${Config.udongeDir}\"")
        buildConfigField("String", "UDONGE_ARCHIVE", "\"${Config.udongeArchive}\"")
        buildConfigField("String", "TMP_DIR", "\"${Config.tmpDir}\"")
        buildConfigField("String", "BACKUP_PREFIX", "\"${Config.backupPrefix}\"")
        consumerProguardFile("proguard-rules.pro")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    api(project(":shared"))
    coreLibraryDesugaring(libs.jdk.libs)

    api(libs.markwon.core)
    implementation(libs.bcpkix)
    implementation(libs.commons.compress)
    implementation(libs.wire.runtime)

    api(libs.libsu.core)
    api(libs.libsu.service)
    api(libs.libsu.nio)

    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.retrofit.scalars)

    implementation(libs.okhttp)

    implementation(libs.core.splashscreen)
    implementation(libs.core.ktx)
    implementation(libs.activity)
    implementation(libs.collection.ktx)
    implementation(libs.profileinstaller)

}
