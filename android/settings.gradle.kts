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
        // Discord Social SDK: a private .aar supplied at build time (DISCORD_SDK_DIR, or discord.sdkDir
        // in this machine's local.properties), never committed.
        val localProps = java.util.Properties().apply {
            val f = File(rootDir, "local.properties")
            if (f.isFile) f.inputStream().use { load(it) }
        }
        val discordSdkDir = providers.environmentVariable("DISCORD_SDK_DIR").orNull
            ?: providers.gradleProperty("DISCORD_SDK_DIR").orNull
            ?: localProps.getProperty("discord.sdkDir")
        if (discordSdkDir != null) flatDir { dirs(discordSdkDir) }
    }
}
rootProject.name = "FlashpointAndroid"
include(":app")
