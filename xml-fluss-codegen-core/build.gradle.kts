plugins {
    `java-library`
    kotlin("jvm")
    id("xml-fluss-publish")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":xml-fluss-runtime"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test-junit5"))
}

tasks.withType<Test> { useJUnitPlatform() }

xmlFlussPublish {
    artifactName = "xml-fluss-core"
    artifactDescription = "Shared codegen model and SPI used by the xml-fluss apt and ksp processors."
    inceptionYear = "2026"
}
