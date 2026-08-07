plugins {
    kotlin("jvm")
    id("xml-fluss-publish")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("com.fasterxml:aalto-xml:1.3.4")
    api("org.jspecify:jspecify:1.0.0")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation(kotlin("test-junit5"))
}

tasks.withType<Test> { useJUnitPlatform() }

xmlFlussPublish {
    artifactName = "xml-fluss-runtime"
    artifactDescription = "Streaming XML parser runtime for the JVM — Aalto StAX + Kotlin Coroutines Flow."
    inceptionYear = "2025"
}
