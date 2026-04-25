import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    kotlin("jvm") version "2.3.20" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    `jacoco-report-aggregation`
    `maven-publish`
}

allprojects {
    group = "site.asm0dey.xmlfluss"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "jacoco")
    apply(plugin = "maven-publish")

    tasks.withType<Test> {
        extensions.configure<JacocoTaskExtension> {
            isEnabled = true
        }
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                afterEvaluate {
                    from(components["java"])
                }
            }
        }
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
}

dependencies {
    jacocoAggregation(project(":xml-fluss-ksp"))
    jacocoAggregation(project(":xml-fluss-runtime"))
    jacocoAggregation(project(":xml-fluss-test"))
}

reporting {
    @Suppress("UnstableApiUsage")
    reports {
        val testCodeCoverageReport by creating(JacocoCoverageReport::class) {
            testSuiteName.set("test")
        }
    }
}