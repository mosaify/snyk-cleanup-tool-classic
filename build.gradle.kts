group = "dev.samoylenko"
version = "0.1.2-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()

    maven {
        name = "Central Snapshots"
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
    }
}

plugins {
    application

    alias(libs.plugins.kotlin.plugin.jvm)
}

kotlin {
    sourceSets.configureEach {
        languageSettings {
            optIn("kotlin.time.ExperimentalTime")
            optIn("kotlin.uuid.ExperimentalUuidApi")
            optIn("kotlinx.serialization.ExperimentalSerializationApi")
        }
    }
}

dependencies {
    implementation(libs.client.snyk)
    implementation(libs.clikt)
    implementation(libs.kotlinx.datetime)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
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
