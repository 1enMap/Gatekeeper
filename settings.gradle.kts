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
        // Guardian Project's repository, for tor-android and jtorctl only (Tor mode).
        maven {
            url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master")
            content { includeGroup("info.guardianproject") }
        }
    }
}

rootProject.name = "Gatekeeper"
include(":app")
