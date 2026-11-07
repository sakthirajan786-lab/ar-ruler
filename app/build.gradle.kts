plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arruler.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arruler.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        create("release") {
            // Bundled key so every CI build is signed identically (updates install cleanly).
            // Replace with your own private keystore before publishing to Google Play.
            storeFile = file("release.keystore")
            storePassword = "arruler123"
            keyAlias = "arruler"
            keyPassword = "arruler123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("com.google.ar:core:1.45.0")
}
