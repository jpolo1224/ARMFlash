plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.armsx.flashpoint"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.armsx.flasharm"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters.add("arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static") } }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    androidResources {
        // Ruffle's wasm must stay uncompressed-readable; and big zips should not be re-deflated.
        noCompress += listOf("wasm")
    }
    // libadrenotools' hook libraries are loaded by path from nativeLibraryDir, so native
    // libraries are installed as files (as in ARMSX3).
    packaging { jniLibs { useLegacyPackaging = true } }
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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.games.activity)
    implementation(libs.androidx.appcompat)
}
