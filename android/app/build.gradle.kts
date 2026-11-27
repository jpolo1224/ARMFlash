import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Discord Rich Presence (as in ARMSX2): the Social SDK is a private, optional build input, and the
// feature needs ARMFlash's own Discord application id. Without both it compiles out.
// The SDK's folder: DISCORD_SDK_DIR, or discord.sdkDir in this machine's local.properties (kept out
// of git). The application id is ARMFlash's own (gradle.properties), public in every APK anyway.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val discordSdkDir: String? = (System.getenv("DISCORD_SDK_DIR") ?: providers.gradleProperty("DISCORD_SDK_DIR").orNull
    ?: localProps.getProperty("discord.sdkDir"))
    ?.takeIf { File(it, "include/discordpp.h").isFile }
val discordAppId: String? = (System.getenv("FLASHARM_DISCORD_APP_ID") ?: providers.gradleProperty("flasharm.discordAppId").orNull)
    ?.takeIf { it.matches(Regex("\\d{15,20}")) }
val haveDiscord = discordSdkDir != null && discordAppId != null

// Release signing key: android/keystore.properties (storeFile, storePassword, keyAlias,
// keyPassword), kept out of git like ARMSX2's. Without it, release builds are signed with this
// machine's debug key, which is fine for testing and never for a published APK.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val haveReleaseKey = keystoreProps.getProperty("storeFile") != null
if (!haveReleaseKey) logger.warn("ARMFlash: no android/keystore.properties, release builds use the debug key")

android {
    namespace = "com.armsx.flashpoint"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.armsx.flasharm"
        minSdk = 28
        targetSdk = 37
        versionCode = 7
        versionName = "1.6"
        ndk { abiFilters.add("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                if (haveDiscord) arguments += listOf("-DDISCORD_SDK_DIR=$discordSdkDir", "-DFLASHARM_DISCORD_APP_ID=$discordAppId")
            }
        }
        // The sign-in return link: discord-<application id>, as registered in the developer portal.
        manifestPlaceholders["discordScheme"] = "discord-" + (discordAppId ?: "0")
        // Whether this build has Discord at all: the game's Friends tab says so instead of offering
        // a Connect that cannot work.
        buildConfigField("boolean", "DISCORD", haveDiscord.toString())
    }

    signingConfigs {
        if (haveReleaseKey) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (haveReleaseKey) "release" else "debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }

    // As in ARMSX2: the GitHub build may keep its Flashpoint folder anywhere (all-files access,
    // src/github's manifest); the Play build stays scoped storage (internal or SD card).
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            isDefault = true
            buildConfigField("boolean", "STORAGE_ALL_FILES", "true")
            // The in-app updater (src/github): never in the Play build.
            buildConfigField("boolean", "IN_APP_UPDATER", "true")
            // LSFG frame generation (libarmflash_lsfg.so and src/github's settings): never in the
            // Play build either, which gets neither the library nor its strings.
            externalNativeBuild { cmake { arguments += listOf("-DARMFLASH_LSFG=ON") } }
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "STORAGE_ALL_FILES", "false")
            buildConfigField("boolean", "IN_APP_UPDATER", "false")
        }
    }
    androidResources {
        // Ruffle's wasm must stay uncompressed-readable; and big zips should not be re-deflated.
        noCompress += listOf("wasm")
    }
    // libadrenotools' hook libraries are loaded by path from nativeLibraryDir, so native
    // libraries are installed as files (as in ARMSX3).
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // The .aar ships the SDK .so and the private dir supplies the same file for CMake.
            pickFirsts += "**/libdiscord_partner_sdk.so"
        }
    }
    sourceSets { getByName("main") { if (haveDiscord) jniLibs.srcDir(discordSdkDir!!) } }
    // libfpdriver.so: custom Vulkan driver loading (libadrenotools).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// libfpcore.so comes from Rust: scripts/build-native.sh (cargo + NDK clang).
val buildNative by tasks.registering(Exec::class) {
    val script = rootProject.file("../scripts/build-native.sh")
    commandLine(script.absolutePath, "release")
    inputs.dir(rootProject.file("../rust/fp-proxy/src"))
    inputs.dir(rootProject.file("../rust/fp-jni/src"))
    inputs.dir(rootProject.file("../rust/fp-ruffle/src"))
    outputs.file(file("src/main/jniLibs/arm64-v8a/libfpcore.so"))
    outputs.file(file("src/main/jniLibs/arm64-v8a/libfpruffle.so"))
}
tasks.named("preBuild") { dependsOn(buildNative) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.games.activity)
    implementation(libs.androidx.appcompat)
    // Discord Social SDK, only when supplied (see haveDiscord), and Custom Tabs, which its sign-in needs.
    if (haveDiscord) implementation(group = "", name = "discord_partner_sdk", ext = "aar")
    implementation(libs.androidx.browser)
}
