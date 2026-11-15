import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

// ==========================================
// Release signing
// Local: android/keystore/keystore.properties (ignored by git).
// CI:    RUMMIKUB_KEYSTORE_FILE / RUMMIKUB_KEYSTORE_PASSWORD / RUMMIKUB_KEY_ALIAS / RUMMIKUB_KEY_PASSWORD
// Without a key, builds fall back to the debug key so local development keeps working.
// ==========================================
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

fun signingValue(propertyKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

// Public app configuration (android/app-config.properties)
val appConfig = Properties().apply {
    val f = rootProject.file("app-config.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
// -PgoogleWebClientId= (empty) builds an anonymous-play app, handy for the emulator; -PserverUrl=ws://10.0.2.2:8090 targets a local server
val googleWebClientId: String =
    (project.findProperty("googleWebClientId") as String?) ?: appConfig.getProperty("googleWebClientId", "").trim()
val serverUrl: String = (project.findProperty("serverUrl") as String?) ?: "wss://rummikub-friends.onrender.com"

val releaseStorePath = signingValue("storeFile", "RUMMIKUB_KEYSTORE_FILE")
val releaseStoreFile = releaseStorePath?.let { rootProject.file(it) }?.takeIf { it.exists() }
val hasReleaseKey = releaseStoreFile != null

android {
    namespace = "com.rummikub.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rummikub.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 26
        versionName = "1.4.13"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")

        // Phones only: drop the x86/x86_64 WebRTC binaries (emulators), halves the APK size.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Two ways to distribute the app:
    //  direct: the APK shared by link, with built-in updates (GitHub releases)
    //  play:   the Play Store bundle (the store updates it, no self-update code or permission)
    flavorDimensions += "channel"
    productFlavors {
        create("direct") {
            dimension = "channel"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "channel"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("storePassword", "RUMMIKUB_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RUMMIKUB_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RUMMIKUB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            // Same key for debug builds when available, so a release APK installs over a debug one.
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-engine"))
    implementation(project(":core-network"))
    implementation(project(":core-audio"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.animation)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)

    implementation(libs.webrtc.android)

    // Google sign-in (Credential Manager)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.google.id)

    // Profile photos
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
