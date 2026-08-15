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

rootProject.name = "HONORShare"

include(
    ":app",
    ":core",
    ":protocol",
    ":discovery",
    ":transfer",
    ":storage",
    ":history",
    ":ui",
    ":tests",
)
