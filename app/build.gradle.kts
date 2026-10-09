plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.camerold"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.camerold"
        minSdk = 26
        targetSdk = 34
        // Release builds on CI take the version from the git tag (v3.2.0 -> 3.2.0, see .github/workflows/release.yml);
        // local builds use the version below. versionCode is derived from it, so it always goes up with the version.
        val version = System.getenv("RELEASE_VERSION")?.takeIf { it.isNotBlank() } ?: "3.2.0"
        versionName = version
        versionCode = versionCodeOf(version)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // App languages = English (default) + every res/values-<lang>/strings.xml translation.
        // Adding a language needs no code change: the in-app picker and Android 13+ settings pick it up.
        val locales = listOf("en") + (file("src/main/res").listFiles() ?: emptyArray())
            .filter { it.isDirectory && File(it, "strings.xml").exists() }
            .mapNotNull { Regex("^values-([a-z]{2,3})(?:-r([A-Z]{2}))?$").find(it.name) }
            .map { m -> m.groupValues[1] + (m.groupValues[2].takeIf { it.isNotEmpty() }?.let { "-$it" } ?: "") }
            .sorted()
        buildConfigField("String[]", "LOCALES", locales.joinToString(",", "{", "}") { "\"$it\"" })
    }

    buildFeatures { buildConfig = true }
    // Generates the Android 13+ locale list from the same resource folders (see res/resources.properties)
    androidResources { generateLocaleConfig = true }

    // Release signing: CI passes a keystore through environment variables (see .github/workflows/release.yml).
    // Without them, release builds fall back to the debug key so anyone can build and sideload.
    val releaseKeystore = System.getenv("ANDROID_KEYSTORE_FILE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // The web viewer (web/) is bundled into the app for "Watch a camera" mode – one viewer implementation
    sourceSets["main"].assets.srcDirs("src/main/assets", "../web")
}

dependencies {
    implementation("io.github.webrtc-sdk:android:125.6422.03")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM unit tests (Android's is a stub)
}

/** "3.2.1" or "3.2.1-beta.1" -> 30201 (major * 10000 + minor * 100 + patch). */
fun versionCodeOf(v: String): Int {
    val (major, minor, patch) = (v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)).take(3)
    require(minor < 100 && patch < 100) { "Version $v: minor and patch must be below 100" }
    return major * 10000 + minor * 100 + patch
}
