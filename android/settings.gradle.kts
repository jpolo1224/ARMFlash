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
        // Discord Social SDK: a private .aar supplied at build time (DISCORD_SDK_DIR), never committed.
        val discordSdkDir = providers.environmentVariable("DISCORD_SDK_DIR").orNull
            ?: providers.gradleProperty("DISCORD_SDK_DIR").orNull
        if (discordSdkDir != null) flatDir { dirs(discordSdkDir) }
    }
}
rootProject.name = "FlashpointAndroid"
include(":app")
