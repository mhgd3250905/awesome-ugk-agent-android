plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ugk.runtime.demo.a"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ugk.runtime.demo.a"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // An executable ELF needs a real file in nativeLibraryDir. Uncompressed
    // APK-native libraries can be mapped from the APK without exposing such a
    // file, so both Probe apps deliberately request installation-time
    // extraction. The same rule is part of the current Core validation matrix.
    packaging {
        jniLibs {
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
}

dependencies {
    implementation(project(":ugk-terminal-runtime-android"))
    implementation(project(":pi-terminal-skill-android"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
