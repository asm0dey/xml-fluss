plugins {
    kotlin("jvm")
    id("xml-fluss-publish")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":xml-fluss-runtime"))
    implementation(project(":xml-fluss-codegen-core"))
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.7")
    implementation("com.squareup:kotlinpoet:2.3.0")
    implementation("com.squareup:kotlinpoet-ksp:2.3.0")

    testImplementation("dev.zacsweers.kctfork:ksp:0.12.1")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation(kotlin("test-junit5"))
    testImplementation(project(":xml-fluss-runtime"))
    testImplementation(kotlin("reflect"))
}

tasks.withType<Test> { useJUnitPlatform() }

xmlFlussPublish {
    artifactName = "xml-fluss-ksp"
    artifactDescription = "KSP processor that generates streaming XML parsers from annotated Kotlin data classes."
    inceptionYear = "2025"
}
