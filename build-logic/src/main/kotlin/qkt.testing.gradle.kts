// Unit-test wiring: tag filtering for the default run, soak knobs forwarded to the test
// JVM, and the per-suite log budget that keeps CI logs readable.
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
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
    // DslReferenceCodeBlocksTest reads these pages; without the input a docs-only edit leaves `test` up to date.
    inputs
        .dir("docs/reference/dsl")
        .withPropertyName("dslReferenceDocs")
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
