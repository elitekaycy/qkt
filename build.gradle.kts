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

val toolExt = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""

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
    implementation(libs.xz)
    implementation(libs.snakeyaml.engine)
    implementation(libs.logback.classic)
    implementation(libs.lsp4j)
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

// A jlink-minimized JRE carrying only the modules qkt loads (mirrors the Dockerfile).
// jdk.crypto.ec (TLS) and jdk.unsupported (sun.misc.Unsafe, used by okio/kotlin) are
// loaded reflectively, so jdeps misses them and they are named explicitly.
val jlinkRuntimeDir = layout.buildDirectory.dir("jlink-runtime")
val jlinkRuntime by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build a minimal JRE with only the modules qkt needs."
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    outputs.dir(jlinkRuntimeDir)
    doFirst {
        val out = jlinkRuntimeDir.get().asFile
        delete(out) // jlink refuses to write into an existing directory
        commandLine(
            "${launcher.get().metadata.installationPath.asFile.absolutePath}/bin/jlink$toolExt",
            "--add-modules",
            "java.base,java.logging,java.naming,java.xml,jdk.httpserver,jdk.crypto.ec,jdk.unsupported",
            "--strip-debug",
            "--no-man-pages",
            "--no-header-files",
            "--compress=zip-6",
            "--output",
            out.absolutePath,
        )
    }
}

// Self-contained linux-x64 distribution: the app plus the bundled runtime, so `qkt`
// runs with no system Java. Powers the one-shot installer (#57). The launcher reads
// JAVA_HOME, which the installer points at the bundled runtime/ directory.
tasks.register<Tar>("selfContainedDist") {
    group = "distribution"
    description = "App plus a bundled jlink runtime (no system Java required)."
    dependsOn("installDist", jlinkRuntime)
    compression = Compression.GZIP
    archiveFileName.set("qkt-${project.version}-linux-x64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("qkt") {
        from(layout.buildDirectory.dir("install/qkt"))
        from(jlinkRuntimeDir) { into("runtime") }
    }
}

// Windows self-contained app-image: a real qkt.exe plus the bundled jlink runtime,
// so qkt runs on Windows with no system Java. The Windows twin of selfContainedDist.
// jpackage targets the host OS, so a real Windows image is produced only when this
// runs on a Windows JDK (CI: windows-latest). On Linux it yields a host-OS image,
// which is enough to validate the task wiring locally.
val jpackageImageDir = layout.buildDirectory.dir("jpackage")

val windowsAppImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build a Windows app-image (qkt.exe + bundled runtime) via jpackage."
    dependsOn("installDist", jlinkRuntime)
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    val libDir = layout.buildDirectory.dir("install/qkt/lib")
    val runtimeDir = jlinkRuntimeDir
    val outDir = jpackageImageDir
    val ver = project.version.toString()
    outputs.dir(outDir)
    doFirst {
        val out = outDir.get().asFile

        delete(out) // jpackage refuses to write into an existing app-image dir
        out.mkdirs()
        val jpackage =
            "${launcher.get().metadata.installationPath.asFile.absolutePath}/bin/jpackage$toolExt"
        val args =
            mutableListOf(
                jpackage,
                "--type",
                "app-image",
                "--name",
                "qkt",
                "--input",
                libDir.get().asFile.absolutePath,
                "--main-jar",
                "qkt-$ver.jar",
                "--main-class",
                "com.qkt.cli.MainKt",
                "--runtime-image",
                runtimeDir.get().asFile.absolutePath,
                "--dest",
                out.absolutePath,
            )

        // qkt is a console CLI, but jpackage app-image launchers default to the GUI
        // subsystem, so the JVM exit code never reaches the shell (qkt.exe --version
        // prints fine yet reports failure). Force a console launcher. --win-console is
        // Windows-only, so guard on the host tool suffix.
        if (toolExt == ".exe") args.add("--win-console")
        commandLine(args)
    }
}

// Zip the Windows app-image with the editor bundle into the release asset. The
// installer extracts this to %LOCALAPPDATA%\Programs\qkt and points QKT_HOME there,
// so `qkt editor install` finds share/editor (jpackage puts jars under app/, not
// lib/, so EditorPaths' lib-dir fallback does not fire — QKT_HOME is the locator).
tasks.register<Zip>("windowsSelfContainedDist") {
    group = "distribution"
    description = "Zip the Windows app-image (qkt.exe + runtime + editor files)."
    dependsOn(windowsAppImage)
    archiveFileName.set("qkt-${project.version}-windows-x64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("qkt") {
        from(jpackageImageDir.map { it.dir("qkt") })
        from(rootProject.file("editor")) {
            into("share/editor")
            exclude("**/node_modules/**")
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

tasks.register<JavaExec>("runParityDataXauusd") {
    group = "verification"
    description = "Compare the backtest data source (dukascopy) vs the live broker feed (MT5) for XAUUSD"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.qkt.tools.parity.ParityDukascopyMt5XauusdKt")
}

tasks.register<JavaExec>("runVerifyDukascopyIndices") {
    group = "verification"
    description = "Decode one dukascopy hour per mapped index and assert the price lands in band"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.qkt.tools.parity.VerifyDukascopyIndexInstrumentsKt")
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
            excludeTags("e2e", "e2e-live", "dockerSmoke", "stress", "soak")
        } else {
            includeTags(*included.toTypedArray())
        }
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
    // Forward `-Dsoak.*` to the test JVM so soak runs can be scaled from the command line
    // (e.g. -Dsoak.ticks=50000000 for a multi-hour run). Gradle's -D lands on the build JVM
    // only; without this the soak knobs never reach the fork.
    for (name in System.getProperties().stringPropertyNames()) {
        if (name.startsWith("soak.")) systemProperty(name, System.getProperty(name))
    }
}

val checkTestLogBudget by tasks.registering {
    group = "verification"
    description = "Fail when a test suite writes more stdout/stderr than the CI log budget allows."
    dependsOn(tasks.test)

    val resultsDir = layout.buildDirectory.dir("test-results/test")
    val maxLines =
        providers
            .gradleProperty("qktTestLogBudgetLines")
            .map(String::toInt)
            .orElse(1_000)
    val maxBytes =
        providers
            .gradleProperty("qktTestLogBudgetBytes")
            .map(String::toInt)
            .orElse(128 * 1024)

    inputs.dir(resultsDir)
    inputs.property("qktTestLogBudgetLines", maxLines)
    inputs.property("qktTestLogBudgetBytes", maxBytes)

    doLast {
        val root = resultsDir.get().asFile
        if (!root.exists()) return@doLast

        val documentBuilder =
            javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
        val offenders = mutableListOf<String>()
        root
            .walkTopDown()
            .filter { it.isFile && it.name.startsWith("TEST-") && it.extension == "xml" }
            .sortedBy { it.name }
            .forEach { file ->
                val doc = documentBuilder.parse(file)
                val output = doc.textFor("system-out") + "\n" + doc.textFor("system-err")
                val lines = output.lineSequence().count { it.isNotBlank() }
                val bytes = output.toByteArray(Charsets.UTF_8).size
                if (lines > maxLines.get() || bytes > maxBytes.get()) {
                    offenders.add("${file.name}: $lines nonblank log lines, $bytes bytes")
                }
            }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Test log budget exceeded.")
                    appendLine("Limits: ${maxLines.get()} nonblank lines or ${maxBytes.get()} bytes per test suite.")
                    appendLine("Override only for intentional diagnostics with:")
                    appendLine("  -PqktTestLogBudgetLines=<n> -PqktTestLogBudgetBytes=<n>")
                    offenders.forEach { appendLine("  $it") }
                },
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkTestLogBudget)
}

fun org.w3c.dom.Document.textFor(tag: String): String {
    val nodes = getElementsByTagName(tag)
    if (nodes.length == 0) return ""
    val out = StringBuilder()
    for (i in 0 until nodes.length) {
        out.append(nodes.item(i).textContent ?: "")
    }
    return out.toString()
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
