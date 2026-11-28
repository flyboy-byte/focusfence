plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.focusfence"
    compileSdk = 35

    // Plan 15: the warnings triaged on 2026-11-26 (kept on purpose, or waiting for targetSdk 35) live in the baseline,
    // so lint's report shows only new ones. Regenerate with ./gradlew updateLintBaseline after fixing one.
    lint {
        baseline = file("lint-baseline.xml")
    }

    defaultConfig {
        // Separate app id from the original Focus Fence so this
        // accountability build installs alongside it, not over it.
        applicationId = "app.focusfence.accountable"
        minSdk = 26
        targetSdk = 35
        versionCode = 59
        versionName = "3.1.7-accountable"
    }

    // Release signing is driven by environment variables so the keystore and
    // passwords live only in CI secrets, never in the repo. Without them
    // (e.g. local debug builds) the release config is simply unsigned.
    signingConfigs {
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Minification is off for the first signed release so obfuscation
            // can't break reflection/native paths (TFLite); re-enable with
            // proguard rules once verified on a device.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The bundled classifier is copied out via AssetManager.openFd, which only works
    // for stored (uncompressed) assets. A .tflite barely compresses anyway.
    androidResources {
        noCompress += "tflite"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("com.google.android.material:material:1.13.0")
    // Background sync to the accountability server. Newer lines (2.11+, and core 1.16+,
    // activity 1.11+, material 1.14+, appcompat 1.8) need compileSdk 36 and AGP 8.9-9.1 (tried
    // 2026-11-27): kept in the lint baseline.
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // Plan 13: the invite's QR code, drawn on the phone (pure Java, Apache-2.0; encoding only, no camera).
    implementation("com.google.zxing:core:3.5.3")

    // On-device screenshot classifier (optional at runtime: capture works with
    // no model present, it just produces score-less shots). Model is supplied
    // by the owner into filesDir, never bundled.
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    testImplementation("junit:junit:4.13.2")
}
