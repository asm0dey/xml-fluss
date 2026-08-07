plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
    `java-library`
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(project(":xml-fluss-runtime"))
    ksp(project(":xml-fluss-ksp"))
    annotationProcessor(project(":xml-fluss-apt"))

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}

kotlin.sourceSets.named("main") {
    kotlin.setSrcDirs(listOf("src/main/kotlin"))
}
