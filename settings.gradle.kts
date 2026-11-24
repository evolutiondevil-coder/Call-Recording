pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Robolectric's ~200 MB Android framework jar (tests only) comes from Google's mirror of
        // Maven Central: repo.maven.apache.org often rate-limits (HTTP 429) such large downloads.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            content { includeModule("org.robolectric", "android-all-instrumented") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "CallRecorder"
include(":app")
