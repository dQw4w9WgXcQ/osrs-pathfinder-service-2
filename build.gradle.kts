plugins {
    kotlin("jvm") version "1.9.21"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        url = uri("https://repo.runelite.net")
    }
    mavenLocal()
}

dependencies {
    implementation("dev.dqw4w9wgxcq.pathfinder:commons:1.0.0")
    implementation("dev.dqw4w9wgxcq.pathfinder:pathfinding:1.0.0")
    implementation("dev.dqw4w9wgxcq.pathfinder:graph-generation:1.0.0")
    implementation("io.javalin:javalin:5.6.3")
    implementation("io.javalin.community.ssl:ssl-plugin:5.6.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.0")
    implementation("commons-cli:commons-cli:1.5.0")
    //https://stackoverflow.com/questions/60074168/java-lang-illegalstateexception-no-server-alpnprocessors-wiremock/69072775#69072775
    implementation("org.eclipse.jetty:jetty-alpn-server:11.0.17")
    implementation("org.eclipse.jetty:jetty-alpn-java-server:11.0.17")

    runtimeOnly("org.slf4j:slf4j-simple:2.0.7")
}

tasks {
    shadowJar {
        archiveFileName.set("${archiveBaseName.get()}.${archiveExtension.get()}")
        manifest {
            attributes["Main-Class"] = "MainKt"
        }
    }
}

kotlin {
    jvmToolchain(17)
}