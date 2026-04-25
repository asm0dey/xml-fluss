plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":xml-fluss-runtime"))
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.6")
    implementation("com.squareup:kotlinpoet:2.3.0")
    implementation("com.squareup:kotlinpoet-ksp:2.3.0")

    testImplementation("dev.zacsweers.kctfork:ksp:0.12.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")
    testImplementation(kotlin("test-junit5"))
    testImplementation(project(":xml-fluss-runtime"))
    testImplementation(kotlin("reflect"))
}

tasks.withType<Test> { useJUnitPlatform() }
