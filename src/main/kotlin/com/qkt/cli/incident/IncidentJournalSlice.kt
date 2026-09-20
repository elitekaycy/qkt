package com.qkt.cli.incident

import com.qkt.cli.daemon.StateDir
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Adds each strategy's order-journal lines whose `ts` falls in `[since, until)` under
 * `journal-slice/<strategy>/`. Lines without a timestamp are kept only when no window is set.
 */
internal fun collectJournal(
    bundle: IncidentBundle,
    stateDir: StateDir,
    strategy: String?,
    since: Long?,
    until: Long?,
) {
    val journalRoot = stateDir.stateRoot.resolve("journal")
    if (!Files.isDirectory(journalRoot)) {
        bundle.warn("journal directory not found: $journalRoot")
        return
    }
    val strategyDirs =
        if (strategy != null) {
            listOf(journalRoot.resolve(strategy))
        } else {
            Files.list(journalRoot).use { stream ->
                stream
                    .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .sorted()
                    .toList()
            }
        }
    for (strategyDir in strategyDirs) {
        if (!Files.isDirectory(strategyDir)) continue
        val strategyName = journalRoot.relativize(strategyDir).toString().replace('\\', '/')
        val files =
            Files.list(strategyDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .filter { it.fileName.toString().startsWith("journal-") }
                    .filter { it.fileName.toString().endsWith(".jsonl") }
                    .sorted()
                    .toList()
            }
        for (file in files) {
            val entryName = "journal-slice/${sanitizePath(strategyName)}/${file.fileName}"
            bundle.addMatchingLines(file, entryName) { line -> withinJournalWindow(line, since, until) }
        }
    }
}

private fun withinJournalWindow(
    line: String,
    since: Long?,
    until: Long?,
): Boolean {
    val ts = journalTs(line) ?: return since == null && until == null
    if (since != null && ts < since) return false
    if (until != null && ts >= until) return false
    return true
}

private fun journalTs(line: String): Long? {
    val start = line.indexOf("\"ts\":")
    if (start < 0) return null
    var i = start + "\"ts\":".length
    while (i < line.length && line[i].isWhitespace()) i++
    val valueStart = i
    while (i < line.length && line[i].isDigit()) i++
    return line.substring(valueStart, i).toLongOrNull()
}
