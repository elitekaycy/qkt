// Project declaration only: plugins, dependencies and plugin settings. Task wiring lives in
// the qkt.* convention plugins under build-logic/src/main/kotlin, one file per concern.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.ktlint)
    alias(libs.plugins.dokka)
    id("qkt.build-info")
    id("qkt.testing")
    id("qkt.script-tests")
    id("qkt.distribution")
    id("qkt.dev-tasks")
}

group = "com.qkt"
version = rootProject.file("VERSION").readText().trim()

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.xz)
    implementation(libs.snakeyaml.engine)
    implementation(libs.logback.classic)
    implementation(libs.lsp4j)
    implementation(libs.apache.poi.ooxml)
    testImplementation(libs.logback.classic)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.qkt.cli.MainKt")
    applicationName = "qkt"
    // A backtest is a single-threaded, allocation-heavy throughput batch (millions of transient
    // BigDecimals per run). G1, the JDK default, taxes every reference write with a barrier for its
    // concurrent machinery — pure overhead for a job that never needs low pause times. ParallelGC
    // (the throughput collector, no write barriers) measured ~10-24% less CPU per backtest. GC is
    // transparent to results, so this is byte-identical. Live trading allocates little, so it rarely
    // collects and the choice is immaterial there; override with JAVA_OPTS if a deploy wants G1.
    applicationDefaultJvmArgs = listOf("-XX:+UseParallelGC")
}

ktlint {
    version.set("1.5.0")
    verbose.set(true)
    outputToConsole.set(true)
    enableExperimentalRules.set(false)
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":ktlintCheck"))
}

tasks.named<org.jetbrains.dokka.gradle.DokkaTask>("dokkaHtml") {
    moduleName.set("qkt")
    outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    val logoAsset = rootProject.file("docs/assets/logo-icon.svg")
    pluginsMapConfiguration.set(
        mapOf(
            "org.jetbrains.dokka.base.DokkaBase" to
                """{ "customAssets": ["$logoAsset"], "footerMessage": "qkt - Apache 2.0" }""",
        ),
    )
    dokkaSourceSets.configureEach {
        includeNonPublic.set(false)
        skipDeprecated.set(false)
        reportUndocumented.set(true)
        jdkVersion.set(21)
    }
}
