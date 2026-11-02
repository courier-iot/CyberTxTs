plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Set by the GitHub build when the SIGNING_KEYSTORE secret exists
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
// Each GitHub test build gets a higher version number, so phones see it as an update
val buildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
// Set by the Release workflow, like "1.0.0". Releases get version codes far above any test build
// (1.0.0 -> 1000000), so a release installs as an update over an old test build and keeps its data.
val releaseVersion: String? = System.getenv("RELEASE_VERSION")?.removePrefix("v")
    ?.takeIf { Regex("""\d{1,3}\.\d{1,2}\.\d{1,2}""").matches(it) }
fun versionCodeOf(v: String): Int = v.split(".").map { it.toInt() }.let { (a, b, c) -> a * 1_000_000 + b * 10_000 + c * 100 }

android {
    namespace = "com.cybertxts.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.cybertxts.app"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersion?.let(::versionCodeOf) ?: buildNumber
        versionName = releaseVersion ?: "0.1.$buildNumber"
        manifestPlaceholders["appLabel"] = "CyberTxTs"
    }
    signingConfigs {
        create("stellarite") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "cybertxts"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }
    buildTypes {
        // Test builds: a separate app ("CyberTxTs Dev") so they sit next to the real release
        // instead of fighting it over version numbers. Separate app = separate identity and chats.
        getByName("debug") {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "CyberTxTs Dev"
            if (keystorePath != null) signingConfig = signingConfigs.getByName("stellarite")
        }
        // Releases: always signed with the permanent key (the Release workflow refuses to run without it).
        // Code shrinking stays off for now: WebRTC, ZXing and OkHttp need hand-tuned keep rules,
        // and an over-shrunk build breaks calls or scanning in ways only a real phone shows.
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("stellarite")
        }
    }
    // No Google-encrypted dependency report baked into the APK (F-Droid rejects it, and nobody else reads it)
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Video: shrinking clips before sending (Media3 Transformer)
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Internet link: direct phone-to-phone WebRTC data channels, and relay websockets for finding each other
    implementation("io.getstream:stream-webrtc-android:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR invites: make and read QR codes (ZXing), live camera scanning (ZXing Android Embedded).
    // Both open source and fully offline: no Google Play Services needed.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }

    // App lock: fingerprint / face unlock (AndroidX, works offline, no Google Play Services)
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
}
