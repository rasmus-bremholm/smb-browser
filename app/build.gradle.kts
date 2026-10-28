plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.rasmusbremholm.smbbrowser"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.rasmusbremholm.smbbrowser"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    // Shared debug key so each CI build installs over the previous one.
    // ponytail: public on purpose, debug keys grant nothing. Release signing comes in M5.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
}
