pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // The ~200 MB Robolectric framework jar repeatedly receives HTTP 429
        // from Maven Central on PR runners. Google Cloud hosts a Maven Central
        // mirror; route ONLY the framework artifact there so ordinary Android
        // dependencies keep their established resolution order.
        maven {
            name = "GoogleHostedRobolectricFrameworkMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
            content { includeModule("org.robolectric", "android-all-instrumented") }
        }
        mavenCentral()
    }
}
rootProject.name = "ReiAnixNative"
include(":app")
include(":macrobenchmark")
