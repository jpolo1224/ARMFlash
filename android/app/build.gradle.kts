plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.armsx.flashpoint"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.armsx.flashpoint"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters.add("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    androidResources {
        // Ruffle's wasm must stay uncompressed-readable; and big zips should not be re-deflated.
        noCompress += listOf("wasm")
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}

// libfpcore.so comes from Rust: scripts/build-native.sh (cargo + NDK clang).
val buildNative by tasks.registering(Exec::class) {
    val script = rootProject.file("../scripts/build-native.sh")
    commandLine(script.absolutePath, "release")
    inputs.dir(rootProject.file("../rust/fp-proxy/src"))
    inputs.dir(rootProject.file("../rust/fp-jni/src"))
    outputs.file(file("src/main/jniLibs/arm64-v8a/libfpcore.so"))
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
}
