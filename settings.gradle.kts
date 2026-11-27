rootProject.name = "snyk-cleanup-tool-classic"

if (gradle.parent == null) {
    includeBuild("../util-platform-classic")
    includeBuild("../client-snyk-classic")
}

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version
                providers.gradleProperty("versionOfToolchainsFoojayResolver").get()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}
