pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Maven Central is rate-limiting multiple Robolectric runtime artifacts
        // with HTTP 429 on GitHub Actions (android-all-instrumented and
        // nativeruntime-dist-compat). Prefer its Google Cloud mirror for the
        // entire org.robolectric group, not only the framework jars; leave
        // every other dependency on the existing repository order.
        maven {
            name = "GoogleHostedRobolectricMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
            content { includeGroup("org.robolectric") }
        }
        mavenCentral()
    }
}
rootProject.name = "ReiAnixNative"
include(":app")
include(":macrobenchmark")
