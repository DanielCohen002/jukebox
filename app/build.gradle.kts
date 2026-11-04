plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.djcohen.voiceplay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.djcohen.voiceplay"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release { isMinifyEnabled = false }
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
