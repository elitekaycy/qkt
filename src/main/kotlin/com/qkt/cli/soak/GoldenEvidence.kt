package com.qkt.cli.soak

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val MUTATING_MT5_PATHS =
    setOf("/order", "/close_position", "/position_close_partial", "/modify_sl_tp", "/cancel_order")

/** How many mutating MT5 requests in the golden bundle ended with an unknown outcome. */
internal data class GoldenEvidence(
    val unknownOutcomePlacements: Long,
)

/**
 * Checks the golden bundle belongs to [strategy], has live activity, and that every entry matches
 * its manifest SHA-256, then counts mutating gateway requests whose outcome cannot be known.
 */
internal fun inspectGolden(
    path: Path,
    strategy: String,
): GoldenEvidence {
    var unknownOutcomes = 0L
    ZipFile(path.toFile()).use { zip ->
        val manifestEntry =
            zip.getEntry("manifest.json")
                ?: throw IllegalArgumentException("golden bundle has no manifest.json")
        val manifest =
            zip.getInputStream(manifestEntry).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                parseObject(reader.readText(), path, 1L)
            }
        require(manifest["kind"]?.jsonPrimitive?.contentOrNull == "MT5_GOLDEN_CAPTURE") {
            "unsupported golden bundle kind"
        }
        require(manifest["session"]?.jsonPrimitive?.contentOrNull == strategy) {
            "golden bundle session does not match '$strategy'"
        }
        val counts =
            manifest["counts"]?.jsonObject
                ?: throw IllegalArgumentException("golden bundle has no counts")
        for (field in listOf("ticks", "fills", "gatewayExchanges", "linkedPlacements")) {
            val count = counts[field]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            require(count > 0L) { "golden bundle has no $field" }
        }
        val evidenceEntries =
            manifest["entries"]?.jsonArray
                ?: throw IllegalArgumentException("golden bundle has no entries")
        for (item in evidenceEntries) {
            val evidence = item.jsonObject
            val name =
                evidence["path"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("golden entry path missing")
            val expected =
                evidence["sha256"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("golden entry SHA-256 missing")
            val entry = zip.getEntry(name) ?: throw IllegalArgumentException("golden entry missing: $name")
            val actual = zip.getInputStream(entry).use(::sha256)
            require(actual == expected) { "golden entry SHA-256 mismatch: $name" }
            if (name.startsWith("gateway/") && name.endsWith(".jsonl")) {
                zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val record = Json.parseToJsonElement(line).jsonObject
                        val method = record["method"]?.jsonPrimitive?.contentOrNull
                        val endpoint = record["path"]?.jsonPrimitive?.contentOrNull?.substringBefore('?')
                        if (method == "POST" && endpoint in MUTATING_MT5_PATHS && isUnknownMutation(record)) {
                            unknownOutcomes += 1L
                        }
                    }
                }
            }
        }
    }
    return GoldenEvidence(unknownOutcomes)
}

private fun isUnknownMutation(record: JsonObject): Boolean {
    val responseCode = record["responseCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    if (responseCode == null || responseCode == 409 || responseCode >= 500) return true
    if (responseCode !in 200..299) return false
    val responseBody = record["responseBody"]?.jsonPrimitive?.contentOrNull ?: return true
    return runCatching {
        val result = Json.parseToJsonElement(responseBody).jsonObject["result"]?.jsonObject
        result
            ?.get("retcode")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toIntOrNull() == null
    }.getOrDefault(true)
}
