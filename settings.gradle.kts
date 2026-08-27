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
include(":ugk-agent-task-runtime-android")
include(":pi-system-skill-android")
include(":pi-agent-skill-runtime-android")
include(":pi-terminal-skill-android")
include(":demo-app")
include(":ugk-terminal-runtime-android")
include(":terminal-probe-demo-a")
include(":terminal-probe-demo-b")
