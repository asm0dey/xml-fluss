import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.vanniktech.maven.publish")
}

val useDokka = pluginManager.hasPlugin("org.jetbrains.kotlin.jvm") ||
    pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")

if (useDokka) {
    pluginManager.apply("org.jetbrains.dokka")
    pluginManager.apply("org.jetbrains.dokka-javadoc")
}

abstract class XmlFlussPublishExtension {
    abstract var artifactName: String
    abstract var artifactDescription: String
    abstract var inceptionYear: String
}

val xmlFlussPublish = extensions.create("xmlFlussPublish", XmlFlussPublishExtension::class.java)

mavenPublishing {
    configure(
        JavaLibrary(
            javadocJar = if (useDokka) {
                JavadocJar.Dokka("dokkaGeneratePublicationJavadoc")
            } else {
                JavadocJar.Javadoc()
            },
            sourcesJar = SourcesJar.Sources(),
        )
    )
    publishToMavenCentral(automaticRelease = true)
    if (project.findProperty("signingInMemoryKey") != null) {
        signAllPublications()
    }
    coordinates(project.group.toString(), project.name, project.version.toString())

    pom {
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

afterEvaluate {
    publishing {
        publications.withType(MavenPublication::class.java).configureEach {
            pom {
                name.set(xmlFlussPublish.artifactName)
                description.set(xmlFlussPublish.artifactDescription)
                inceptionYear.set(xmlFlussPublish.inceptionYear)
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/asm0dey/xml-fluss")
                credentials {
                    username = project.findProperty("gpr.user") as String?
                        ?: System.getenv("GITHUB_ACTOR")
                    password = project.findProperty("gpr.key") as String?
                        ?: System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
