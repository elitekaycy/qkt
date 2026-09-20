package com.qkt.cli.audit

import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes an audit's JSON to `--out` when given, regardless of the stdout format flag, so operators
 * can append each run to the tick-feed audit results table with one command.
 */
internal fun persistAuditJson(
    outPath: String?,
    json: String,
) {
    outPath?.let {
        val path = Path.of(it)
        path.parent?.let { parent -> Files.createDirectories(parent) }
        Files.writeString(path, json + "\n")
        System.err.println("qkt audit-ticks: wrote $it")
    }
}
