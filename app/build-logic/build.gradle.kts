plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

gradlePlugin {
    plugins {
        register("MagiskPlugin") {
            id = "MagiskPlugin"
            implementationClass = "MagiskPlugin"
        }
    }
}

dependencies {
    implementation("org.smali:dexlib2:2.5.2")
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.android.build.sdk.common)
}
