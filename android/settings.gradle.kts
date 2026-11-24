pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // The Google-hosted Maven Central mirror also serves test dependencies
        // such as JUnit and ASM, which can hit HTTP 429 on GitHub Actions.
        maven {
            name = "GoogleHostedMavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
    }
}
rootProject.name = "ReiAnixNative"
include(":app")
include(":macrobenchmark")
