plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ugk.pi.android.testapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ugk.pi.android.testapp"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":ugk-pi-android"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
