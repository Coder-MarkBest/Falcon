pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
    resolutionStrategy.eachPlugin {
        if (requested.id.id == "com.falcon.ipc.falcon-gradle") {
            useModule("com.github.Coder-MarkBest.Falcon:falcon-gradle-plugin:${requested.version}")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
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

include(":falcon-capability", ":falcon-mcp-adapters", ":falcon-agent-runtime", ":falcon-mcp-gateway", ":falcon-agent-demo")
