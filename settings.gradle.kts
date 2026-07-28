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
        google()
        mavenCentral()
    }
}

rootProject.name = "Neon"

// Reines Kotlin/JVM — ohne Android-SDK baubar und testbar. Hier liegt die gesamte
// Entscheidungslogik des Routers.
include(":core:router")

// Android-Module.
include(":app")
include(":service")
include(":core:audio")
include(":core:speech")
include(":core:inference")
include(":core:memory")
include(":core:tools")
include(":core:platform")
