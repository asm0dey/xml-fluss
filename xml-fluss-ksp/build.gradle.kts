import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
    id("org.jetbrains.dokka-javadoc")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":xml-fluss-runtime"))
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.7")
    implementation("com.squareup:kotlinpoet:2.3.0")
    implementation("com.squareup:kotlinpoet-ksp:2.3.0")

    testImplementation("dev.zacsweers.kctfork:ksp:0.12.1")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation(kotlin("test-junit5"))
    testImplementation(project(":xml-fluss-runtime"))
    testImplementation(kotlin("reflect"))
}

tasks.withType<Test> { useJUnitPlatform() }

mavenPublishing {
    configure(JavaLibrary(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationJavadoc"), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral(automaticRelease = true)
    if (project.findProperty("signingInMemoryKey") != null) {
        signAllPublications()
    }

    coordinates(project.group.toString(), project.name, project.version.toString())

    pom {
        name.set("xml-fluss-ksp")
        description.set("KSP processor that generates streaming XML parsers from annotated Kotlin data classes.")
        inceptionYear.set("2025")
        url.set("https://github.com/asm0dey/xml-fluss")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("asm0dey")
                name.set("Pavel Finkelshtein")
                email.set("pavel.finkelshtein@gmail.com")
                url.set("https://github.com/asm0dey")
            }
        }
        scm {
            url.set("https://github.com/asm0dey/xml-fluss")
            connection.set("scm:git:git://github.com/asm0dey/xml-fluss.git")
            developerConnection.set("scm:git:ssh://git@github.com/asm0dey/xml-fluss.git")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/asm0dey/xml-fluss")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
