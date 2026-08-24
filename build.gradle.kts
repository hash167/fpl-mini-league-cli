import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.8.10"
    kotlin("plugin.serialization") version "1.8.10"
    application
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "me.random_number"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val dropwizardVersion = "4.0.12"

dependencies {
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("org.jline:jline:3.26.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("io.dropwizard:dropwizard-core:$dropwizardVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
    implementation("io.opentelemetry:opentelemetry-api:1.49.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

application {
    mainClass.set("MainKt")
}

tasks.register<JavaExec>("runWeb") {
    group = "application"
    description = "Run the Dropwizard webapp (server config.yml)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("web.FplWebApplication")
    args("server", "config.yml")
    workingDir = project.projectDir
}

tasks.shadowJar {
    archiveBaseName.set("fpl-web")
    archiveVersion.set("1.0")
    archiveClassifier.set("")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes["Main-Class"] = "web.FplWebApplication"
    }
    doLast {
        // application plugin would otherwise leave Main-Class=MainKt
        exec {
            commandLine(
                "jar",
                "ufe",
                archiveFile.get().asFile.absolutePath,
                "web.FplWebApplication"
            )
        }
    }
}
