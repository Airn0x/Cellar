plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release builds derive their version from the git tag CI passes in
// (-PcellarVersion=0.4.0), so a published APK always reports the version
// it was released as. Local builds fall back to a dev marker.
val cellarVersion = (findProperty("cellarVersion") as String?) ?: "0.0.0-dev"

android {
    // Kotlin package is app.cellar ("in" is a Kotlin keyword, so the
    // in.parallex.* store id stays out of source paths).
    namespace = "app.cellar"
    compileSdk = 35

    defaultConfig {
        applicationId = "in.parallex.cellar"
        minSdk = 29
        targetSdk = 35
        // monotonic from the version: 0.4.0 -> 400
        versionCode = cellarVersion.removeSuffix("-dev").split(".").let {
            val p = it.mapNotNull { s -> s.toIntOrNull() }
            if (p.size >= 3) p[0] * 10000 + p[1] * 100 + p[2] else 1
        }
        versionName = cellarVersion
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
