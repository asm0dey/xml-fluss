plugins {
    kotlin("jvm") version "2.3.20" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
}

allprojects {
    group = "site.asm0dey.xmlfluss"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    repositories { mavenCentral() }
}
