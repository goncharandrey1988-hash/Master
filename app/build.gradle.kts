plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lfpmonitor.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lfpmonitor.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // Один и тот же ключ на каждой сборке, чтобы новые версии ставились поверх старой.
    // Для публикации в Google Play нужно будет сделать свой закрытый ключ.
    signingConfigs {
        create("shared") {
            storeFile = file("signing.jks")
            storePassword = "lfpmonitor"
            keyAlias = "lfp"
            keyPassword = "lfpmonitor"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
