plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val reianixEnableR8 = providers.gradleProperty("reianixEnableR8")
    .map { it.toBoolean() }
    .getOrElse(true)

android {
    namespace = "com.reianix.reianix_local"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    defaultConfig {
        applicationId = "com.reiflix.reiflix_local"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.gradleProperty("reianixBuildNumber").orNull?.toIntOrNull()?.takeIf { it >= 2 } ?: 2
        versionName = providers.gradleProperty("reianixBuildVersion").orNull?.takeIf { it.isNotBlank() } ?: "0.2.1"
        val aniListClientId = providers.gradleProperty("anilistClientId")
            .orElse(providers.environmentVariable("REIANIX_ANILIST_CLIENT_ID")).orNull.orEmpty().trim()
        require(aniListClientId.isEmpty() || aniListClientId.matches(Regex("[0-9]+"))) {
            "AniList client ID must be numeric."
        }
        buildConfigField("String", "ANILIST_CLIENT_ID", "\"$aniListClientId\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("ciRelease") {
            val keystorePath = System.getenv("REIANIX_ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("REIANIX_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("REIANIX_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("REIANIX_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // CI disables R8 for PR preview builds and keeps it enabled on main releases.
            isMinifyEnabled = reianixEnableR8
            isShrinkResources = reianixEnableR8
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Release APKs must never silently inherit a changing debug key.
            // Only PR preview artifacts opt in to debug signing explicitly.
            signingConfig = when {
                !System.getenv("REIANIX_ANDROID_KEYSTORE_PATH").isNullOrBlank() ->
                    signingConfigs.getByName("ciRelease")
                System.getenv("REIANIX_ALLOW_DEBUG_RELEASE_FOR_PR") == "true" &&
                    System.getenv("GITHUB_EVENT_NAME") == "pull_request" ->
                    signingConfigs.getByName("debug")
                else -> null // Local release without a key remains unsigned, not debug-signed.
            }
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        // Clone the FINAL release configuration (R8, ABI, assets), then
        // override only the signing needed for an authorized local benchmark.
        // Separate, locally signed performance-test variant. Official release
        // signing and behavior remain unchanged.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }
}

kotlin { jvmToolchain(17) }

// Robolectric otherwise downloads android-all JARs itself during JUnit execution,
// bypassing Gradle repository selection/caching. On GitHub Actions that can
// fail intermittently and make unrelated tests fail before their bodies run.
// Resolve the SDKs used by our @Config tests (API 28 and 35) through Gradle
// first; no emulator, skipped test, or new workflow is involved.
val robolectricFrameworkJars = configurations.create("robolectricFrameworkJars") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add("robolectricFrameworkJars", "org.robolectric:android-all-instrumented:9-robolectric-4913185-2-i7")
    add("robolectricFrameworkJars", "org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
}

val prepareRobolectricFrameworkJars by tasks.registering(Copy::class) {
    from(robolectricFrameworkJars)
    into(layout.buildDirectory.dir("robolectric/frameworks"))
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(prepareRobolectricFrameworkJars)
    systemProperty(
        "robolectric.dependency.dir",
        layout.buildDirectory.dir("robolectric/frameworks").get().asFile.absolutePath,
    )
}

    // earlier validation stage 29: dependencies remain unchanged; the cleanup is structural and reuses
    // the existing Compose/Navigation/Lifecycle/Media3 stack without new abstractions.
dependencies {
    // Native AndroidX/Compose dependencies used by the installed application.
    implementation("androidx.activity:activity-ktx:1.13.0")
    // Compose line compatible with the existing compileSdk 36 / AGP 8.9.1 toolchain.
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    // Opt-in route-tagged frame diagnostics; disabled in normal app usage.
    implementation("androidx.metrics:metrics-performance:1.0.0")
    // Only installed on the isolated benchmark variant for Macrobenchmark's
    // compilation-profile setup; not added to the official release APK.
    add("benchmarkImplementation", "androidx.profileinstaller:profileinstaller:1.4.1")
    debugImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test")
    androidTestImplementation("androidx.navigation:navigation-testing:2.9.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    // Android sender uses the official Cast framework for TV discovery and playback.
    implementation("com.google.android.gms:play-services-cast-framework:23.0.0")
    // Cast 23.0 pulls MediaRouter 1.9 beta (compileSdk 37 + AGP 9.1).
    // Preserve ReiAnix compileSdk 36 / AGP 8.9.1 with the last compatible 1.8.x.
    implementation("androidx.mediarouter:mediarouter") {
        version { strictly("1.8.1") }
    }
    // NativePlayerActivity uses FragmentActivity for lifecycle and full-screen playback.
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.appcompat:appcompat:1.7.1")

    testImplementation("junit:junit:4.13.2")
    // Robolectric exercises Android SQLiteDatabase on the JVM; no emulator or instrumentation runner.
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
