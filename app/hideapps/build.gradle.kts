plugins {
    alias(libs.plugins.android.library)
}

setupCommon()

android {
    namespace = "com.topjohnwu.magisk.hideapps"

}

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("org.mockito:mockito-core:4.11.0")
    implementation(project(":shared"))
}
