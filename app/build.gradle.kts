import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing credentials. CI supplies them as environment variables (decoded from
// GitHub secrets); local builds can use a git-ignored keystore.properties at the repo root
// (see keystore.properties.example). Nothing secret is ever committed. With neither present,
// assembleRelease still succeeds but produces an unsigned APK that Android won't install.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

val releaseStoreFile = signingValue("FOCUSFENCE_KEYSTORE", "storeFile")
val releaseStorePassword = signingValue("FOCUSFENCE_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("FOCUSFENCE_KEY_ALIAS", "keyAlias")
// PKCS12 keystores (keytool's default) use one password for the store and the key.
val releaseKeyPassword = signingValue("FOCUSFENCE_KEY_PASSWORD", "keyPassword") ?: releaseStorePassword
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias).all { it != null }

android {
    namespace = "app.focusfence"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.focusfence"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "2.1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8 is off so the release runs exactly the code the debug builds were tested
            // with. Turn it on only alongside an on-device pass: the VPN/accessibility
            // services are reached via the manifest, and shrinking bugs only show at runtime.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Separate package so a debug/beta build installs alongside the real app
            // instead of colliding with its signature. Label is overridden in src/debug.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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

    testImplementation("junit:junit:4.13.2")
}
