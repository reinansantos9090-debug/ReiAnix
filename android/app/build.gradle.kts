import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    defaultConfig {
        applicationId = "com.reiflix.reiflix_local"
        minSdk = 24
        targetSdk = 36
        resValue("string", "reiflix_google_web_client_id", providers.environmentVariable("REIFLIX_GOOGLE_WEB_CLIENT_ID").orElse("").get())
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

    // earlier validation stage 29: dependencies remain unchanged; the cleanup is structural and reuses
    // the existing Compose/Navigation/Lifecycle/Media3 stack without new abstractions.
dependencies {
    // The official APK still uses Flet/Flutter tooling to render and compile this module.
    // Runtime entry and the primary Compose surface are native Android components.
    implementation("androidx.activity:activity-ktx:1.13.0")
    // Compose line compatible with the existing compileSdk 36 / AGP 8.9.1 toolchain.
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    // Coil 2.7.0 includes its OkHttp-backed network fetcher transitively through coil-base.
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
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
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")

    testImplementation("junit:junit:4.13.2")
    // Robolectric exercises Android SQLiteDatabase on the JVM; no emulator or instrumentation runner.
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}


// Native Gradle validation builds must not invoke Python to generate this runtime contract.
// The checked-in archive is derived from the canonical LibraryStore schema v29. The official
// legacy packaging path may supply its just-generated asset instead; in that case no duplicate
// asset is added and this task is not part of the generated host build graph.
val canonicalContractAsset = layout.projectDirectory
    .file("src/main/assets/native-sqlite-contract-v29.json")
    .asFile
val compressedCanonicalContract = rootProject.file("build-logic/native-sqlite-contract-v29.json.gz.b64")
val generatedContractAssets = layout.buildDirectory.dir("generated/native-sqlite-contract/assets")
val generateNativeSqliteContractAsset by tasks.registering {
    inputs.file(compressedCanonicalContract)
    outputs.file(generatedContractAssets.map { it.file("native-sqlite-contract-v29.json") })

    doLast {
        check(compressedCanonicalContract.isFile) {
            "Canonical SQLite schema v29 contract archive is missing: ${compressedCanonicalContract.absolutePath}"
        }
        val encodedArchive = compressedCanonicalContract.readText(Charsets.US_ASCII).trim()
        val compressedBytes = Base64.getDecoder().decode(encodedArchive)
        val outputFile = generatedContractAssets.get()
            .file("native-sqlite-contract-v29.json")
            .asFile
        outputFile.parentFile.mkdirs()
        GZIPInputStream(ByteArrayInputStream(compressedBytes)).use { input ->
            outputFile.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        check(outputFile.isFile && outputFile.length() > 0L) {
            "Generated canonical SQLite schema v29 contract is empty."
        }
        check(Regex("\\\"schema_version\\\"\\s*:\\s*29(?:\\s*[,}])")
            .containsMatchIn(outputFile.readText(Charsets.UTF_8))) {
            "Embedded SQLite contract does not declare canonical schema v29."
        }
    }
}

if (!canonicalContractAsset.isFile) {
    android.sourceSets.getByName("main").assets.srcDir(generatedContractAssets)
    tasks.named("preBuild").configure {
        dependsOn(generateNativeSqliteContractAsset)
    }
}

// The standalone Gradle APK must ship native fresh-install bootstrap data without Python.
val canonicalBootstrapAsset = layout.projectDirectory
    .file("src/main/assets/native-sqlite-bootstrap-v29.json")
    .asFile
val compressedCanonicalBootstrap = rootProject.file("build-logic/native-sqlite-bootstrap-v29.json.gz.b64")
val generatedBootstrapAssets = layout.buildDirectory.dir("generated/native-sqlite-bootstrap/assets")
val generateNativeSqliteBootstrapAsset by tasks.registering {
    inputs.file(compressedCanonicalBootstrap)
    outputs.file(generatedBootstrapAssets.map { it.file("native-sqlite-bootstrap-v29.json") })

    doLast {
        check(compressedCanonicalBootstrap.isFile) {
            "Canonical SQLite bootstrap archive is missing: ${compressedCanonicalBootstrap.absolutePath}"
        }
        val encodedArchive = compressedCanonicalBootstrap.readText(Charsets.US_ASCII).trim()
        val compressedBytes = Base64.getDecoder().decode(encodedArchive)
        val outputFile = generatedBootstrapAssets.get()
            .file("native-sqlite-bootstrap-v29.json")
            .asFile
        outputFile.parentFile.mkdirs()
        GZIPInputStream(ByteArrayInputStream(compressedBytes)).use { input ->
            outputFile.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        check(outputFile.isFile && outputFile.length() > 0L) {
            "Generated native SQLite bootstrap is empty."
        }
        val generatedJson = outputFile.readText(Charsets.UTF_8)
        check(generatedJson.contains("\"schema_version\": 29,")) {
            "Native SQLite bootstrap must declare schema v29."
        }
        check(generatedJson.contains("\"initial_migration_version\": 29")) {
            "Native SQLite bootstrap must seed schema_migrations with version 29."
        }
    }
}

if (!canonicalBootstrapAsset.isFile) {
    android.sourceSets.getByName("main").assets.srcDir(generatedBootstrapAssets)
    tasks.named("preBuild").configure {
        dependsOn(generateNativeSqliteBootstrapAsset)
    }
}
