plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// GitHub Actions sets GITHUB_RUN_NUMBER, so every cloud build gets a higher version.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "ph.weatherapp.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dansalasim.weatheralarmph"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // Debug: a fixed key, so each test APK installs over the previous one.
    // Release (Google Play): the upload key, keystore/upload.p12, encrypted with a password
    // that exists only in the GitHub secret UPLOAD_KEY_PASSWORD and with Dan.
    val uploadPassword = System.getenv("UPLOAD_KEY_PASSWORD")
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (uploadPassword != null) create("upload") {
            storeFile = rootProject.file("keystore/upload.p12")
            storeType = "PKCS12"
            storePassword = uploadPassword
            keyAlias = "upload"
            keyPassword = uploadPassword
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "PREMIUM_PREVIEW", "true")
        }
        getByName("release") {
            // No free "Premium preview" switch in the Play Store version.
            buildConfigField("boolean", "PREMIUM_PREVIEW", "false")
            isMinifyEnabled = false
            if (uploadPassword != null) signingConfig = signingConfigs.getByName("upload")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
}
