import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun localProperty(name: String): String? = localProperties.getProperty(name)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

fun loadLocalApiConfig(path: String?): Map<String, String> {
    val file = path?.let(::File) ?: return emptyMap()
    if (!file.isFile) return emptyMap()
    return file.readLines()
        .mapNotNull { line ->
            val value = line.trim()
            if (value.isBlank() || value.startsWith("#")) return@mapNotNull null
            val separator = value.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            value.substring(0, separator).trim().lowercase() to value.substring(separator + 1).trim()
        }
        .toMap()
}

val fixedDebugKeystore = localProperty("ugk.debug.keystore")
val fixedDebugKeystorePath = requireNotNull(fixedDebugKeystore) {
    "demo-app requires a stable ugk.debug.keystore in local.properties; refusing to fall back to a different debug key"
}
val localApiConfig = loadLocalApiConfig(localProperty("ugk.api.config"))

require(File(fixedDebugKeystorePath).isFile) {
    "demo-app requires a stable ugk.debug.keystore in local.properties; refusing to fall back to a different debug key"
}

android {
    namespace = "com.ugk.pi.android.testapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ugk.pi.android.testapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 13
        versionName = "0.9.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Keep local development upgrades installable without changing the app
    // identity. The keystore path lives in ignored local.properties.
    signingConfigs.getByName("debug").apply {
        storeFile = file(fixedDebugKeystorePath)
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }

    buildTypes {
        getByName("debug") {
            // These values are read from the developer's ignored local config
            // and are intentionally empty for Release builds.
            resValue("string", "ugk_default_api_provider_id", "deepseek-default")
            resValue("string", "ugk_default_api_base_url", localApiConfig["baseurl"].orEmpty())
            resValue("string", "ugk_default_api_key", localApiConfig["apikey"].orEmpty())
            resValue("string", "ugk_default_api_model", localApiConfig["model"].orEmpty())
        }
    }

    // The terminal Runtime launches ELF files from nativeLibraryDir. Keep
    // installation-time extraction explicit for every consuming application.
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
    implementation(project(":ugk-pi-android"))
    implementation(project(":pi-file-skill-android"))
    implementation(project(":pi-schedule-skill-android"))
    implementation(project(":ugk-agent-task-runtime-android"))
    implementation(project(":pi-system-skill-android"))
    implementation(project(":pi-agent-skill-runtime-android"))
    implementation(project(":pi-terminal-skill-android"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.core:core-ktx:1.12.0")

    // 成熟优秀的 Android 原生 Markdown 渲染框架 (CommonMark + Spannable)
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
