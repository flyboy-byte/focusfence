plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.focusfence"
    compileSdk = 34

    defaultConfig {
        // Separate app id from the original Focus Fence so this
        // accountability build installs alongside it, not over it.
        applicationId = "app.focusfence.accountable"
        minSdk = 26
        targetSdk = 34
        versionCode = 43
        versionName = "2.19.1-accountable"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")
    // Background sync to the accountability server / partner mailbox. 2.9.x is the
    // last line that builds against compileSdk 34.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // On-device screenshot classifier (optional at runtime: capture works with
    // no model present, it just produces score-less shots). Model is supplied
    // by the owner into filesDir, never bundled.
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    testImplementation("junit:junit:4.13.2")
}
