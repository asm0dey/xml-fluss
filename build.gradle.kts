plugins {
    kotlin("jvm") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.7" apply false
    id("org.jetbrains.dokka") version "2.2.0" apply false
    id("org.jetbrains.dokka-javadoc") version "2.2.0" apply false
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
    `jacoco-report-aggregation`
}

allprojects {
    group = "site.asm0dey.xmlfluss"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "jacoco")

    tasks.withType<Test> {
        extensions.configure<JacocoTaskExtension> {
            isEnabled = true
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
