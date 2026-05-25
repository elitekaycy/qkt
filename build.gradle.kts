plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.ktlint)
    alias(libs.plugins.dokka)
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
    implementation(libs.logback.classic)
    testImplementation(libs.logback.classic)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.qkt.cli.MainKt")
    applicationName = "qkt"
}

distributions {
    named("main") {
        contents {
            // Bundle the editor integrations under share/editor/ in the tarball.
            // `qkt editor install <target>` reads them from $QKT_HOME/share/editor/.
            // .vsix is included when present (built by release.yml before distTar).
            from(rootProject.file("editor")) {
                into("share/editor")
                exclude("**/node_modules/**")
            }
        }
    }
}

tasks.register<JavaExec>("runDemo") {
    group = "application"
    description = "Run the legacy mock-tick demo (predates the qkt CLI)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.qkt.app.MainKt")
}

tasks.register<JavaExec>("runParityBarsXauusd") {
    group = "verification"
    description = "Compare TradingView vs MT5 historical M5 bars for XAUUSD"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.qkt.tools.parity.ParityBarsXauusdKt")
}

tasks.register<JavaExec>("runParityTicksXauusd") {
    group = "verification"
    description = "Compare live TradingView vs MT5 ticks for XAUUSD over a fixed window"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.qkt.tools.parity.ParityTicksXauusdKt")
}

tasks.register<JavaExec>("runStateDemo") {
    group = "verification"
    description = "Write a sample engine-state snapshot to /tmp/qkt-demo-state and list the files"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.qkt.tools.persistence.StateDemoKt")
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

tasks.register<JavaExec>("runMaxAudit") {
    group = "application"
    description = "End-to-end live audit across asset classes (FX, gold, crypto, stocks)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.qkt.app.MaxAuditKt")
}
