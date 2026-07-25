plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // Kotlin package is app.cellar ("in" is a Kotlin keyword, so the
    // in.parallex.* store id stays out of source paths).
    namespace = "app.cellar"
    compileSdk = 35

    defaultConfig {
        applicationId = "in.parallex.cellar"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    packaging {
        jniLibs {
            // extract native "libs" to real files: the engine and proot are
            // executables, exec'd from nativeLibraryDir (the only W^X-legal
            // location on Android 10+)
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
}
