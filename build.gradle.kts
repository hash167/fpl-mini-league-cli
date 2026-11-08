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
    implementation("org.glassfish.jersey.media:jersey-media-sse:3.0.17")
    testImplementation(kotlin("test"))
}

val appBuild: String = (findProperty("appBuild") as String?)?.trim().orEmpty().ifEmpty { "dev" }

// Single source of truth for the build version: @APP_BUILD@ in these resources becomes appBuild.
tasks.processResources {
    inputs.property("appBuild", appBuild)
    filesMatching(listOf("assets/index.html", "assets/sw.js", "fpl-build.properties")) {
        filter<org.apache.tools.ant.filters.ReplaceTokens>("tokens" to mapOf("APP_BUILD" to appBuild))
    }
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

// Provenance tooling stays on the test classpath so it is not packaged into fpl-web-1.0.jar.
// writeBuildMeta records the git commit beside a future jar. It does not change jar bytes.
// compareLiveJar is opt-in and refuses a .partial capture. Neither task deploys or restarts anything.

fun gitOutput(vararg args: String): String? {
    return try {
        val proc = ProcessBuilder(listOf("git", *args))
            .directory(rootDir)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val text = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0) text else null
    } catch (ex: Exception) {
        null
    }
}

tasks.register<JavaExec>("writeBuildMeta") {
    group = "verification"
    description = "Write build/fpl-build-meta.json beside the shadow jar. Sidecar only: not in the image, and not evidence of the running jar."
    dependsOn(tasks.named("testClasses"), tasks.shadowJar)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("JarParityKt")
    doFirst {
        val jar = tasks.shadowJar.get().archiveFile.get().asFile
        val out = layout.buildDirectory.file("fpl-build-meta.json").get().asFile
        val commit = gitOutput("rev-parse", "HEAD") ?: "unknown"
        val dirty = when (val porcelain = gitOutput("status", "--porcelain")) {
            null -> "unknown"
            "" -> "false"
            else -> "true"
        }
        setArgs(listOf(
            "digest",
            "--jar", jar.absolutePath,
            "--out", out.absolutePath,
            "--git-commit", commit,
            "--git-dirty", dirty,
            "--app-build", appBuild
        ))
    }
}

tasks.register<JavaExec>("compareLiveJar") {
    group = "verification"
    description = "Byte-compare a validated jar or complete extract to a candidate jar. Refuses .partial files. A match is not deploy provenance."
    dependsOn(tasks.named("testClasses"), tasks.shadowJar)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("JarParityKt")
    val live = providers.gradleProperty("liveJar")
    onlyIf { live.isPresent }
    doFirst {
        val candidate = (findProperty("candidateJar") as String?)
            ?: tasks.shadowJar.get().archiveFile.get().asFile.absolutePath
        val arguments = mutableListOf(
            "compare",
            "--live", live.get(),
            "--candidate", candidate
        )
        val expect = findProperty("expectSha256") as String?
        if (!expect.isNullOrBlank()) {
            arguments += listOf("--expect-sha256", expect.trim())
        }
        setArgs(arguments)
    }
}
