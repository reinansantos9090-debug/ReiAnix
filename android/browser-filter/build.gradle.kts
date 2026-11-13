plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace="io.github.edsuns.adblockclient"
    compileSdk=36
    ndkVersion="28.0.13004108"
    defaultConfig {
        minSdk=24
        consumerProguardFiles("consumer-rules.pro")
    }
    buildTypes { getByName("release") { ndk { abiFilters += "arm64-v8a" } } }
    externalNativeBuild { cmake { path=file("CMakeLists.txt"); version="3.22.1" } }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies { implementation("com.jakewharton.timber:timber:5.0.1") }
