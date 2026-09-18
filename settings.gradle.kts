pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "CloudDrive"

include(
    ":app",
    ":core:model",
    ":core:data",
    ":core:transfers",
    ":feature:drive",
    ":documentsprovider",
)

