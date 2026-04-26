plugins {
    `java-library`
    id("xml-fluss-publish")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    api(project(":xml-fluss-runtime"))
    implementation("com.palantir.javapoet:javapoet:0.14.0")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }

xmlFlussPublish {
    artifactName = "xml-fluss-apt"
    artifactDescription = "Java annotation processor that generates streaming XML parsers from annotated Java records."
    inceptionYear = "2026"
}
