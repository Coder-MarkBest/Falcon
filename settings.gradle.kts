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

rootProject.name = "Falcon"
include(":falcon-annotations")
include(":falcon-core")
include(":falcon-ksp")
include(":falcon-benchmark")
include(":falcon-gradle-plugin")
include(":falcon-cross-server")
include(":falcon-cross-client")
