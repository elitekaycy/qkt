package com.qkt.cli.soak

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parses one JSON object from soak evidence, failing with the file position when malformed. */
internal fun parseObject(
    text: String,
    path: Path,
    line: Long,
): JsonObject =
    try {
        Json.parseToJsonElement(text).jsonObject
    } catch (error: Exception) {
        throw IllegalArgumentException("malformed JSON at $path:$line: ${error.message}")
    }

/** Fails unless the final engine-to-venue reconciliation evidence reports `clean: true`. */
internal fun inspectReconciliation(path: Path) {
    val root = parseObject(Files.readString(path), path, 1L)
    require(root["clean"]?.jsonPrimitive?.booleanOrNull == true) {
        "final engine-to-venue reconciliation is not clean"
    }
}

/** Fails unless [path] is a non-empty JSON object; [label] names the evidence in the message. */
internal fun inspectJsonArtifact(
    path: Path,
    label: String,
) {
    parseObject(Files.readString(path), path, 1L)
    require(Files.size(path) > 0L) { "$label evidence is empty" }
}
