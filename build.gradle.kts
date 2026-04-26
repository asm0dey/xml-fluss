plugins {
    kotlin("jvm") apply false
    id("com.google.devtools.ksp") version "2.3.7" apply false
    id("org.jetbrains.dokka") apply false
    id("org.jetbrains.dokka-javadoc") apply false
    `jacoco-report-aggregation`
}

allprojects {
    group = "site.asm0dey.xmlfluss"
    version = providers.environmentVariable("RELEASE_VERSION").orElse("0.1.0-SNAPSHOT").get()

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
    jacocoAggregation(project(":xml-fluss-apt"))
    jacocoAggregation(project(":xml-fluss-runtime"))
    jacocoAggregation(project(":xml-fluss-test"))
}

reporting {
    @Suppress("UnstableApiUsage")
    reports {
        @Suppress("unused") val testCodeCoverageReport by creating(JacocoCoverageReport::class) {
            testSuiteName.set("test")
        }
    }
}
