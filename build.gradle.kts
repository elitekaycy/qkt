plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.ktlint)
}

group = "com.qkt"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.snakeyaml.engine)
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.logback.classic)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.qkt.cli.MainKt")
    applicationName = "qkt"
}

tasks.register<JavaExec>("runDemo") {
    group = "application"
    description = "Run the legacy mock-tick demo (predates the qkt CLI)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.qkt.app.MainKt")
}

tasks.test {
    useJUnitPlatform {
        val included =
            (project.findProperty("includeTags") as String?)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        if (included.isEmpty()) {
            excludeTags("e2e", "e2e-live", "dockerSmoke")
        } else {
            includeTags(*included.toTypedArray())
        }
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

tasks.register("dockerBuild") {
    group = "distribution"
    description = "Build the qkt Docker image at qkt:local"
    doLast {
        exec { commandLine("docker", "build", "-t", "qkt:local", ".") }
    }
}

kotlin {
    jvmToolchain(21)
}

ktlint {
    version.set("1.5.0")
    verbose.set(true)
    outputToConsole.set(true)
    enableExperimentalRules.set(false)
}

tasks.register<JavaExec>("runLiveDemo") {
    group = "application"
    description = "Run the live TradingView demo"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.qkt.app.LiveDemoKt")
}

tasks.register<JavaExec>("runMaxAudit") {
    group = "application"
    description = "End-to-end live audit across asset classes (FX, gold, crypto, stocks)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.qkt.app.MaxAuditKt")
}
