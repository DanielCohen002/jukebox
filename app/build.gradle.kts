import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: signing.properties in the project root (gitignored) points at the keystore, e.g.
//   STORE_FILE=C:/Users/me/keys/jukebox/release.jks  STORE_PASSWORD=...  KEY_ALIAS=jukebox  KEY_PASSWORD=...
val signingProps = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val canSign = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").all { signingProps.getProperty(it) != null }

android {
    namespace = "com.djcohen.voiceplay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.djcohen.voiceplay"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = file(signingProps.getProperty("STORE_FILE"))
                storePassword = signingProps.getProperty("STORE_PASSWORD")
                keyAlias = signingProps.getProperty("KEY_ALIAS")
                keyPassword = signingProps.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    // Talks to Metrolist's media service (it understands voice-search requests).
    implementation("androidx.media3:media3-session:1.8.0")
}

dependencies {
    // Offline wake-phrase detection (Apache 2.0). The small English model lives in assets/model (not in git).
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
}
