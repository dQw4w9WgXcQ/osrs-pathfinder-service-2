plugins {
    kotlin("jvm") version "1.9.21"
}

group = "dev.dqw4w9wgxcq"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        url = uri("https://repo.runelite.net")
    }
    mavenLocal()
}

dependencies {
    implementation(project(":"))
    implementation("dev.dqw4w9wgxcq.pathfinder:commons:+")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks {
    test {
        useJUnitPlatform()
    }
}

kotlin {
    jvmToolchain(17)
}