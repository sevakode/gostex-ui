pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version "2.4.21"
        id("org.jetbrains.kotlin.jvm") version "2.4.21"
        id("org.jetbrains.compose") version "1.12.2"
    }
}
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "OrbitTV"
include(":release-runtime")
if (providers.gradleProperty("gostex.android").orNull != "false") include(":app")
// Desktop is opt-in so an Android-only release never resolves desktop tooling.
if (providers.gradleProperty("gostex.desktop").orNull == "true") {
    include(":shared-core", ":desktop")
}
