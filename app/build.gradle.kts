
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.harddev.snowtouch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.harddev.snowtouch"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
0

