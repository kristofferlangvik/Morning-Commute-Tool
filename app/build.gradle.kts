plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.morningcommutetool"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.morningcommutetool"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // A fixed key so every build from GitHub can update the installed app.
    // It only proves "this APK came from this repo's builds" - nothing secret.
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "commutetool"
            keyAlias = "commute"
            keyPassword = "commutetool"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
