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
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("com.fasterxml:aalto-xml:1.3.4")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation(kotlin("test-junit5"))
}

tasks.withType<Test> { useJUnitPlatform() }

mavenPublishing {
    configure(JavaLibrary(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationJavadoc"), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(project.group.toString(), project.name, project.version.toString())

    pom {
        name.set("xml-fluss-runtime")
        description.set("Streaming XML parser runtime for the JVM — Aalto StAX + Kotlin Coroutines Flow.")
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
