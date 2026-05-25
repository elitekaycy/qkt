import java.io.ByteArrayOutputStream
import java.time.Instant

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.ktlint)
    alias(libs.plugins.dokka)
}

group = "com.qkt"
version = rootProject.file("VERSION").readText().trim()

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

val generatedResourcesDir = layout.buildDirectory.dir("generated/resources/build-info")

val generateBuildInfo by tasks.registering {
    val outDir = generatedResourcesDir
    val versionProvider = providers.provider { project.version.toString() }
    val gitShaProvider =
        providers.of(GitShaSource::class) {
            parameters.repoRoot.set(rootProject.projectDir)
        }
    outputs.dir(outDir)
    inputs.property("version", versionProvider)
    inputs.property("gitSha", gitShaProvider)
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        val props = dir.resolve("build-info.properties")
        val timestamp = Instant.now().toString()
        props.writeText(
            """
            |version=${versionProvider.get()}
            |gitSha=${gitShaProvider.get()}
            |buildTimestamp=$timestamp
            |
            """.trimMargin(),
        )
    }
}

sourceSets.named("main") {
    resources.srcDir(generatedResourcesDir)
}

tasks.named("processResources") {
    dependsOn(generateBuildInfo)
}

abstract class GitShaSource : ValueSource<String, GitShaSource.Params> {
    interface Params : ValueSourceParameters {
        val repoRoot: DirectoryProperty
    }

    @get:javax.inject.Inject
    abstract val execOps: ExecOperations

    override fun obtain(): String {
        val out = ByteArrayOutputStream()
        return try {
            val result =
                execOps.exec {
                    workingDir = parameters.repoRoot.get().asFile
                    commandLine("git", "rev-parse", "--short", "HEAD")
                    standardOutput = out
                    errorOutput = ByteArrayOutputStream()
                    isIgnoreExitValue = true
                }
            if (result.exitValue == 0) out.toString().trim() else "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
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
