// Unit-test wiring: tag filtering for the default run, soak knobs forwarded to the test
// JVM, and the per-suite log budget that keeps CI logs readable.
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.util.concurrent.ConcurrentHashMap

plugins {
    java
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
    // On CI (GitHub Actions sets CI=true) the console carries only failed and skipped tests: full stack traces,
    // plus the output a failing test wrote. Every suite's output still lands in build/test-results, which
    // checkTestLogBudget reads and the failed-run artifact keeps. Locally every event and stream prints.
    if (System.getenv("CI") == "true") {
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = false
        }
        printOutputOfFailedTests()
    } else {
        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = true
        }
    }
    // The CLI logs to stderr, which test reports keep; tests log to stdout so suites stay within their log budget.
    systemProperty("QKT_CONSOLE_TARGET", "System.out")
    // Backtest bundles default under ~/.qkt/runs; redirect them into the build dir in tests.
    systemProperty(
        "qkt.runs.home",
        layout.buildDirectory
            .dir("test-runs")
            .get()
            .asFile
            .absolutePath,
    )

    // DslReferenceCodeBlocksTest reads these pages; without the input a docs-only edit leaves `test` up to date.

    inputs
        .dir("docs/reference/dsl")
        .withPropertyName("dslReferenceDocs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // IndicatorVerificationTest replays these oracle fixtures; a fixture-only change must re-run `test` too.
    inputs
        .dir("verification/oracle/indicators")
        .withPropertyName("indicatorOracleFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
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

// Buffers each test's (and each suite's) stdout/stderr and prints it only when that test or suite fails.
fun Test.printOutputOfFailedTests() {
    fun key(descriptor: TestDescriptor): String =
        generateSequence(descriptor) { it.parent }.joinToString(" < ") { it.name }
    val buffers = ConcurrentHashMap<String, StringBuilder>()
    addTestOutputListener { descriptor, event ->
        buffers.computeIfAbsent(key(descriptor)) { StringBuilder() }.append(event.message)
    }

    fun flush(
        descriptor: TestDescriptor,
        result: TestResult,
    ) {
        val output = buffers.remove(key(descriptor))
        if (result.resultType == TestResult.ResultType.FAILURE && !output.isNullOrBlank()) {
            logger.lifecycle("\n----- output of ${descriptor.className ?: ""} > ${descriptor.name} -----\n$output")
        }
    }
    addTestListener(
        object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) = Unit

            override fun afterSuite(
                suite: TestDescriptor,
                result: TestResult,
            ) = flush(suite, result)

            override fun beforeTest(testDescriptor: TestDescriptor) = Unit

            override fun afterTest(
                testDescriptor: TestDescriptor,
                result: TestResult,
            ) = flush(testDescriptor, result)
        },
    )
}
