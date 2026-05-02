rootProject.name = "xml-fluss"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":xml-fluss-runtime")
include(":xml-fluss-ksp")
include(":xml-fluss-apt")
include(":xml-fluss-test")

project(":xml-fluss-runtime").projectDir = file("xml-fluss-runtime")
project(":xml-fluss-ksp").projectDir = file("xml-fluss-ksp")
project(":xml-fluss-apt").projectDir = file("xml-fluss-apt")
project(":xml-fluss-test").projectDir = file("xml-fluss-test")

include(":xml-fluss-codegen-core")
project(":xml-fluss-codegen-core").projectDir = file("xml-fluss-codegen-core")
