group = "dev.samoylenko"
version = "0.1.2-SNAPSHOT"

repositories {
    mavenCentral()
}

plugins {
    application

    alias(libs.plugins.kotlin.plugin.jvm)
    alias(libs.plugins.kotest)
}

kotlin {
    jvmToolchain(providers.gradleProperty("versionOfJava").get().toInt())

    sourceSets.configureEach {
        languageSettings {
            optIn("kotlin.time.ExperimentalTime")
            optIn("kotlin.uuid.ExperimentalUuidApi")
            optIn("kotlinx.serialization.ExperimentalSerializationApi")
        }
    }
}

dependencies {
    implementation(platform(libs.kotlinx.coroutines.bom))
    implementation(platform(kotlin("bom")))

    implementation(libs.client.snyk)
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    runtimeOnly(libs.slf4j.klogging)

    testImplementation(platform(libs.junit.bom))
    testImplementation(platform(libs.kotest.bom))
    testImplementation(libs.kotest.runner.junit6)
    testImplementation(libs.kotest.assertions.core)
}

tasks {
    test {
        useJUnitPlatform()
        testLogging.showStandardStreams = true
    }
}

application {
    mainClass.set("dev.samoylenko.client.snyk.cli.AppKt")
}
