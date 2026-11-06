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

rootProject.name = "karoo-interval"

include(":app")

// Hammerhead's karoo-ext library (v1.1.9, Apache 2.0), built from source so no
// GitHub Packages token is needed. See karoo-ext/LICENSE.
include(":karoo-ext")
