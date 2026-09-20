package com.qkt.cli.incident

import com.qkt.cli.daemon.StateDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Adds the daemon logs (one strategy's log when [strategy] is set) under `logs/`. */
internal fun collectLogs(
    bundle: IncidentBundle,
    stateDir: StateDir,
    strategy: String?,
) {
    val logsDir = stateDir.logsDir
    if (!Files.isDirectory(logsDir)) {
        bundle.warn("logs directory not found: $logsDir")
        return
    }
    val files =
        if (strategy != null) {
            listOf(stateDir.logFile(strategy)).filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        } else {
            regularFilesUnder(logsDir)
        }
    for (file in files) {
        val rel = logsDir.relativize(file)
        bundle.addFile(file, "logs/${zipRelative(rel)}")
    }
}

/** Adds persisted state files except the order journal (sliced separately) under `state/`. */
internal fun collectState(
    bundle: IncidentBundle,
    stateDir: StateDir,
    strategy: String?,
) {
    val stateRoot = stateDir.stateRoot
    if (!Files.isDirectory(stateRoot)) {
        bundle.warn("state directory not found: $stateRoot")
        return
    }
    val strategyPrefix = strategy?.let { Path.of(it) }
    for (file in regularFilesUnder(stateRoot)) {
        val rel = stateRoot.relativize(file)
        if (rel.nameCount > 0 && rel.getName(0).toString() == "journal") continue
        if (strategyPrefix != null && !rel.startsWith(strategyPrefix)) continue
        bundle.addFile(file, "state/${zipRelative(rel)}")
    }
}

/** Adds an operator input file (config or strategy) as [entryName] when one was given. */
internal fun collectInput(
    bundle: IncidentBundle,
    entryName: String,
    path: Path?,
) {
    if (path == null) return
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        bundle.warn("input not found: $path")
        return
    }
    bundle.addFile(path, entryName)
}

/** Makes each path segment zip-safe, replacing anything outside `[A-Za-z0-9._=-]` with `_`. */
internal fun sanitizePath(value: String): String =
    value
        .replace('\\', '/')
        .split('/')
        .joinToString("/") { segment ->
            segment.replace(Regex("[^A-Za-z0-9._=-]"), "_")
        }

private fun zipRelative(path: Path): String = sanitizePath(path.toString().replace('\\', '/'))

private fun regularFilesUnder(root: Path): List<Path> =
    Files.walk(root).use { stream ->
        stream
            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .sorted()
            .toList()
    }
