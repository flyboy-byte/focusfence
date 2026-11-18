// PROTOTYPE accountability backend. Built and tested by CI (`./gradlew -p server test`
// from the repo root). Run locally with `../gradlew -p . run`.
plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
}

repositories { mavenCentral() }

val ktor = "2.3.12"
dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-cio-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    implementation("io.ktor:ktor-client-cio-jvm:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("ch.qos.logback:logback-classic:1.5.6")
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

tasks.test { useJUnitPlatform() }

application { mainClass.set("app.focusfence.server.MainKt") }
