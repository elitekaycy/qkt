// File-size ratchet (qkt skill §13). Kotlin sources and build scripts stay at or under their
// limit; files that predate the rule are recorded in the baseline and may only shrink.
// Shrinking one means lowering its entry (updateFileSizeBaseline does it), so the ceiling
// never drifts back up.
plugins {
    base
}

val defaultLimit = 200
val limitsByPrefix = mapOf("src/test/" to 220)
val sourceRoots = listOf("src/main/kotlin", "src/test/kotlin", "build-logic")
val baselineFile = rootProject.file("config/file-size-baseline.txt")
val repoRoot = rootProject.projectDir

fun limitFor(path: String): Int = limitsByPrefix.entries.firstOrNull { path.startsWith(it.key) }?.value ?: defaultLimit

fun isGenerated(file: File): Boolean = file.invariantSeparatorsPath.let { "/build/" in it || "/.gradle/" in it }

fun measureSourceFiles(): Map<String, Int> {
    val rootScripts = repoRoot.listFiles { f -> f.name.endsWith(".gradle.kts") }.orEmpty().asSequence()
    val sources =
        sourceRoots
            .asSequence()
            .map { File(repoRoot, it) }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension in setOf("kt", "kts") && !isGenerated(it) }
    return (rootScripts + sources).associate { it.relativeTo(repoRoot).invariantSeparatorsPath to it.readLines().size }
}

fun readBaseline(): Map<String, Int> =
    if (!baselineFile.exists()) {
        emptyMap()
    } else {
        baselineFile
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val (count, path) = line.trim().split(Regex("\\s+"), limit = 2)
                path to count.toInt()
            }
    }

fun writeBaseline(entries: Map<String, Int>) {
    baselineFile.parentFile.mkdirs()
    baselineFile.writeText(
        buildString {
            appendLine("# Files over the size limit (200 lines, tests 220) when the rule landed.")
            appendLine("# Entries may only go down. Split a file, then run ./gradlew updateFileSizeBaseline.")
            entries.entries.sortedWith(compareBy({ -it.value }, { it.key })).forEach {
                appendLine("${it.value} ${it.key}")
            }
        },
    )
}

val checkFileSize by tasks.registering {
    group = "verification"
    description = "Fail when a source file exceeds the size limit or an oversized file grows."
    inputs.files(sourceRoots.map { File(repoRoot, it) })
    inputs.files(baselineFile).optional()
    doLast {
        val actual = measureSourceFiles()
        val baseline = readBaseline()
        val problems = mutableListOf<String>()
        actual.forEach { (path, lines) ->
            val allowed = baseline[path] ?: limitFor(path)
            if (lines > allowed) {
                problems.add(
                    if (path in baseline) {
                        "$path grew to $lines lines (baseline $allowed); extract before adding"
                    } else {
                        "$path has $lines lines (limit $allowed); split it by responsibility"
                    },
                )
            }
        }
        baseline.forEach { (path, recorded) ->
            val lines = actual[path]
            if (lines == null || lines < recorded) {
                problems.add("$path shrank to ${lines ?: 0} lines (baseline $recorded); lower the baseline")
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("File size check failed (see qkt skill §13).")
                    problems.sorted().forEach { appendLine("  $it") }
                    appendLine("After shrinking files, run ./gradlew updateFileSizeBaseline.")
                },
            )
        }
    }
}

tasks.register("updateFileSizeBaseline") {
    group = "verification"
    description = "Lower or drop baseline entries for files that shrank. Never raises an entry."
    doLast {
        val actual = measureSourceFiles()
        val lowered =
            readBaseline()
                .mapNotNull { (path, recorded) ->
                    val lines = actual[path] ?: return@mapNotNull null
                    if (lines <= limitFor(path)) null else path to minOf(lines, recorded)
                }.toMap()
        writeBaseline(lowered)
    }
}

tasks.named("check") {
    dependsOn(checkFileSize)
}
