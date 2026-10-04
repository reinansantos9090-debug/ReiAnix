plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.reiflix.reiflix_local"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
        compose = true
    }

    defaultConfig {
        applicationId = "com.reiflix.reiflix_local"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Personal-device release APK: target the Galaxy A15 arm64 ABI.
    // Debug builds remain multi-ABI for x86_64/other CI emulators.
    buildTypes {
        getByName("release") {
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }
}

kotlin { jvmToolchain(17) }

    // Prompt 29: dependencies remain unchanged; the cleanup is structural and reuses
    // the existing Compose/Navigation/Lifecycle/Media3 stack without new abstractions.
dependencies {
    // These are merged into the Flet Flutter host by the custom Android template.
    implementation("androidx.activity:activity-ktx:1.13.0")
    // Compose line compatible with the existing compileSdk 36 / AGP 8.9.1 toolchain.
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    debugImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test")
    androidTestImplementation("androidx.navigation:navigation-testing:2.9.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
