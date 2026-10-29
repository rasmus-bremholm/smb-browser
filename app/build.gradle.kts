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
        // CI passes -PbuildNumber=<run number> so every dev build is a higher version.
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toInt() ?: 1
        versionCode = buildNumber
        versionName = "0.0.$buildNumber"
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
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("com.hierynomus:smbj:0.15.0")
    implementation("com.rapid7.client:dcerpc:0.12.13") // smbj-rpc: share listing
}
