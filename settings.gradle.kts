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

rootProject.name = "SmartMistakeBook"

include(
    ":app",
    ":core:model",
    ":core:visual-runtime",
    ":core:visual-ui",
    ":core:domain",
    ":core:database",
    ":core:data",
    ":core:export",
    ":core:ui",
    ":feature:capture",
    ":feature:review",
    ":feature:tutor",
    ":feature:library",
    ":feature:profile",
    ":macrobenchmark",
    ":quality:visual-benchmark",
)
