import java.util.Properties

// Every release APK, wherever it is built, has to carry the same signature or
// a phone treats it as a different app and refuses to upgrade - the user has
// to uninstall first, losing whatever was stored. So the key is not a CI
// detail: local builds here and the automated builds in the public APK
// repository all sign with it.
//
// The key itself is never in the tree. It is found, in order, from:
//   1. OEPL_KEYSTORE / OEPL_KEYSTORE_PASSWORD / OEPL_KEY_ALIAS /
//      OEPL_KEY_PASSWORD in the environment, which is how CI passes it
//   2. signing.properties next to this file (git-ignored), for local builds:
//        storeFile=/path/to/oeplnfc-release.jks
//        storePassword=...
//        keyAlias=oeplnfc
//        keyPassword=...
//
// With neither present the release build stays unsigned instead of failing,
// so a fresh clone still builds.
fun releaseSigning(): Map<String, String>? {
    System.getenv("OEPL_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { store ->
        return mapOf(
            "storeFile" to store,
            "storePassword" to (System.getenv("OEPL_KEYSTORE_PASSWORD") ?: ""),
            "keyAlias" to (System.getenv("OEPL_KEY_ALIAS") ?: "oeplnfc"),
            "keyPassword" to (System.getenv("OEPL_KEY_PASSWORD") ?: ""),
        )
    }
    val props = rootProject.file("signing.properties")
        .takeIf { it.exists() }
        ?: project.file("signing.properties").takeIf { it.exists() }
        ?: return null
    val p = Properties().apply { props.inputStream().use { load(it) } }
    val store = p.getProperty("storeFile")?.takeIf { it.isNotBlank() } ?: return null
    return mapOf(
        "storeFile" to store,
        "storePassword" to (p.getProperty("storePassword") ?: ""),
        "keyAlias" to (p.getProperty("keyAlias") ?: "oeplnfc"),
        "keyPassword" to (p.getProperty("keyPassword") ?: ""),
    )
}

val releaseKey = releaseSigning()

// A phone refuses an APK whose versionCode is lower than the installed one, so
// the automated builds pass their run number in here. A local build keeps the
// baseline, which is fine until you sideload one over an automated build - then
// set OEPL_VERSION_CODE to at least what that build used.
val oeplVersionCode = (System.getenv("OEPL_VERSION_CODE")?.toIntOrNull() ?: 1)
val oeplVersionName = (System.getenv("OEPL_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.atc1441.oeplnfc"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.atc1441.oeplnfc"
        minSdk = 21
        targetSdk = 34
        versionCode = oeplVersionCode
        versionName = oeplVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey["storeFile"]!!)
                storePassword = releaseKey["storePassword"]
                keyAlias = releaseKey["keyAlias"]
                keyPassword = releaseKey["keyPassword"]
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKey != null) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.lifecycle(
                    "No signing key found (OEPL_KEYSTORE or signing.properties); " +
                        "the release APK will be unsigned."
                )
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.google.android.material:material:1.11.0")
    // Reads EXIF orientation on every API level, which BitmapFactory ignores.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    testImplementation("junit:junit:4.13.2")
    // Gesture routing cannot be unit tested and `adb shell input` cannot
    // express a pinch, so the ScrollView interaction is covered on device.
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
}
