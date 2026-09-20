package com.qkt.cli.golden

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Opens a journal day-file for line reading, transparently gunzipping `.jsonl.gz` files
 * produced by [com.qkt.observe.JournalRetention] compression.
 */
internal fun journalReader(file: Path): BufferedReader {
    val input = Files.newInputStream(file)
    val stream = if (file.fileName.toString().endsWith(".gz")) GZIPInputStream(input) else input
    return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
}

/** Journal day-files directly inside [dir], sorted; symlinks are not followed. */
internal fun jsonlFiles(dir: Path): List<Path> {
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    return Files.list(dir).use { stream ->
        stream
            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .filter { isJournalFile(it) }
            .sorted()
            .toList()
    }
}

/** Journal day-files anywhere under [dir], sorted; symlinks are not followed. */
internal fun jsonlFilesRecursive(dir: Path): List<Path> {
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    return Files.walk(dir).use { stream ->
        stream
            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .filter { isJournalFile(it) }
            .sorted()
            .toList()
    }
}

/** Parses one journal line, failing with the file position when it is not a JSON object. */
internal fun parseRecord(
    file: Path,
    lineNumber: Long,
    line: String,
) = try {
    Json.parseToJsonElement(line).jsonObject
} catch (error: Exception) {
    throw IllegalArgumentException("malformed JSONL at $file:$lineNumber: ${error.message}")
}

/** The journal record's `ts` epoch milliseconds, required. */
internal fun timestamp(
    record: JsonObject,
    file: Path,
    lineNumber: Long,
): Long =
    record["ts"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: throw IllegalArgumentException("missing numeric ts at $file:$lineNumber")

private fun isJournalFile(file: Path): Boolean {
    val name = file.fileName.toString()
    return name.endsWith(".jsonl") || name.endsWith(".jsonl.gz")
}
