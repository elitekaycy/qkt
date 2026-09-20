package com.qkt.cli.soak

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Health samples collected during a soak, and the worst dropped-tick count any of them saw. */
internal data class HealthEvidence(
    val samples: Long,
    val droppedTicks: Long,
)

/** Reads health JSONL, requiring every sample to be `ok` with [strategy] present and running. */
internal fun inspectHealth(
    path: Path,
    strategy: String,
): HealthEvidence {
    var samples = 0L
    var maxDroppedTicks = 0L
    Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
        var lineNumber = 0L
        while (true) {
            val line = reader.readLine() ?: break
            lineNumber += 1L
            if (line.isBlank()) continue
            val root = parseObject(line, path, lineNumber)
            require(root["status"]?.jsonPrimitive?.contentOrNull == "ok") {
                "unhealthy sample at $path:$lineNumber"
            }
            val row =
                root["perStrategy"]
                    ?.jsonArray
                    ?.map { it.jsonObject }
                    ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == strategy }
                    ?: throw IllegalArgumentException("strategy '$strategy' missing at $path:$lineNumber")
            require(row["running"]?.jsonPrimitive?.booleanOrNull == true) {
                "strategy '$strategy' was not running at $path:$lineNumber"
            }
            val dropped =
                row["droppedTicks"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: throw IllegalArgumentException("missing droppedTicks at $path:$lineNumber")
            require(dropped >= 0L) { "negative droppedTicks at $path:$lineNumber" }
            maxDroppedTicks = maxOf(maxDroppedTicks, dropped)
            samples += 1L
        }
    }
    require(samples > 0L) { "health evidence contains no samples" }
    return HealthEvidence(samples, maxDroppedTicks)
}
