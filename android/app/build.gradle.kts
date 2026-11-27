import java.util.Base64
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

plugins {
    id("com.android.application")
    id("com.google.android.gms.oss-licenses-plugin")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val reianixEnableR8 = providers.gradleProperty("reianixEnableR8")
    .map { it.toBoolean() }
    .getOrElse(true)

// Every build uses the same real Google Play and AdMob integrations.
// Real ads are enabled by default; an explicit false can pause delivery without demo ads.
val liveAds = providers.gradleProperty("reianixEnableLiveAds").map { it.toBooleanStrict() }.getOrElse(true)
val billingPublicKey = providers.gradleProperty("reianixBillingPublicKey").orNull.orEmpty().trim()
require(billingPublicKey.isEmpty() || runCatching {
    val decoded = Base64.getDecoder().decode(billingPublicKey)
    KeyFactory.getInstance("RSA")
        .generatePublic(X509EncodedKeySpec(decoded))
}.isSuccess) {
    "The Play Console billing public key must be a valid Base64 RSA public key."
}
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
        applicationId = "com.reianix.app"
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
        buildConfigField("boolean", "MONETIZATION_LIVE_ADS", liveAds.toString())
        buildConfigField("String", "MONETIZATION_BILLING_KEY", "\"$billingPublicKey\"")
        manifestPlaceholders["admobAppId"] = "ca-app-pub-5427640733122650~3098200260"
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
// Resolve API 28 and 35 independently; one configuration would select only
// one version of the same Maven module and leave the other SDK unavailable.
val robolectricFrameworkJars28 = configurations.create("robolectricFrameworkJars28") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val robolectricFrameworkJars35 = configurations.create("robolectricFrameworkJars35") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add("robolectricFrameworkJars28", "org.robolectric:android-all-instrumented:9-robolectric-4913185-2-i7")
    add("robolectricFrameworkJars35", "org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
}

val prepareRobolectricFrameworkJars by tasks.registering(Copy::class) {
    from(robolectricFrameworkJars28, robolectricFrameworkJars35)
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
    implementation("com.google.android.gms:play-services-ads:24.9.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("com.android.billingclient:billing:8.3.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    // Compose line compatible with the existing compileSdk 36 / AGP 8.9.1 toolchain.
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
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
    // Cast 22.3.x targets MediaRouter 1.8; Cast 23.x requires 1.9 / Android 37.
    // Keep the supported Android 36 / AGP 8.9.1 build without unsafe downgrades.
    implementation("com.google.android.gms:play-services-cast-framework:22.3.0")
    // The 17.5.x UI pulls Compose Adaptive 1.3 (SDK 37 / AGP 9.1).
    // Keep the compatible classic OSS viewer and generated license resources.
    implementation("com.google.android.gms:play-services-oss-licenses:17.1.0")
    implementation("androidx.mediarouter:mediarouter:1.8.1")
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
