// Root build.gradle.kts file

plugins {
    kotlin("jvm") version "1.5.0"
    application
}

application {
    mainClassName = "com.example.MainKt"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
}