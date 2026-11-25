plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sansui.remote2026"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sansui.remote2026"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

// No third-party libraries on purpose: fewer things to break, smaller APK.
