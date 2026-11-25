// PROTOTYPE accountability backend. Built and tested by CI (`./gradlew -p server test`
// from the repo root). Run locally with `../gradlew -p . run`.
plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
}

repositories { mavenCentral() }

val ktor = "2.3.13"
dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-cio-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    implementation("io.ktor:ktor-client-cio-jvm:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("ch.qos.logback:logback-classic:1.5.38")
    // QR code for the admin's authenticator setup (drawn as inline SVG; no images, no scripts).
    implementation("com.google.zxing:core:3.5.3")
    // Plain SMTP as an alternative to Resend (2026-11-25, Logan: "option for both, lean towards Resend").
    implementation("org.eclipse.angus:angus-mail:2.0.3")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor")
    testImplementation("io.ktor:ktor-client-core-jvm:$ktor")
}

// The phone's pure-Kotlin decision code (no Android types) is compiled in too, so
// the server alerts on exactly what the phone's Digest.alertText alerts on, and
// pairing hashes match by construction. One source of truth for both ends.
kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir("../app/src/main/java/app/focusfence/dns")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
    // Gradle 8 passes a run with zero tests; for a CI gate that's a silent hole.
    var ran = 0L
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) ran = result.testCount
        }
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
    })
    doLast { if (ran == 0L) throw GradleException("no server tests ran") }
}

application { mainClass.set("app.focusfence.server.MainKt") }

// The About / privacy pages (docs/site) are on GitHub Pages; the server doesn't serve them.
