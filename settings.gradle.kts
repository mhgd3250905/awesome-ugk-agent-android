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
    }
}

rootProject.name = "ugk-agent-sdk"
include(":ugk-pi-android")
include(":pi-file-skill-android")
include(":pi-schedule-skill-android")
include(":pi-system-skill-android")
include(":demo-app")
