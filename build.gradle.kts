plugins {
    kotlin("jvm") version "2.3.20" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    `jacoco-report-aggregation`
}

allprojects {
    group = "site.asm0dey.xmlfluss"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    repositories { mavenCentral() }
    apply(plugin = "jacoco")
    tasks.withType<Test> {
        extensions.configure<JacocoTaskExtension> {
            // Optional: ensures .exec files are generated
            isEnabled = true
        }
    }
}

dependencies {
    // Add all modules you want to include in the root report
    jacocoAggregation(project(":xml-fluss-ksp"))
    jacocoAggregation(project(":xml-fluss-runtime"))
    jacocoAggregation(project(":xml-fluss-test"))
}

reporting {
    reports {
        val testCodeCoverageReport by creating(JacocoCoverageReport::class) {
            testSuiteName.set("test")
        }
    }
}