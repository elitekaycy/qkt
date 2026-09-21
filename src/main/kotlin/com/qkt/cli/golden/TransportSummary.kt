package com.qkt.cli.golden

import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val MUTATING_ENDPOINTS =
    setOf("/order", "/close_position", "/position_close_partial", "/modify_sl_tp", "/cancel_order")

/** MT5 gateway exchanges inside the audit window, and how many placed or mutated orders. */
internal data class TransportSummary(
    val exchangeCount: Long,
    val linkedPlacements: Long,
    val mutationCount: Long,
)

/**
 * Scans MT5 transport journals for exchanges inside the audit window. A placement is linked when a
 * `POST /order` names an order the audit journal saw filled. Its HTTP response is not required: a
 * placement whose response was lost (a timeout on a loaded gateway) is an UNKNOWN outcome the engine
 * resolves from the venue, and the fill in the audit journal is the proof that it was placed.
 * Requiring a 2xx made a correctly handled session impossible to capture.
 */
internal fun scanTransport(
    files: List<Path>,
    audit: AuditSummary,
): TransportSummary {
    var exchanges = 0L
    var linkedPlacements = 0L
    var mutations = 0L
    for (file in files) {
        journalReader(file).use { reader ->
            var lineNumber = 0L
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber += 1L
                if (line.isBlank()) continue
                val record = parseRecord(file, lineNumber, line)
                if (timestamp(record, file, lineNumber) !in audit.firstTimestampMs..audit.lastTimestampMs) continue
                exchanges += 1L
                val endpoint = record["path"]?.jsonPrimitive?.contentOrNull?.substringBefore('?')
                if (endpoint in MUTATING_ENDPOINTS) mutations += 1L
                val idempotencyKey = record["idempotencyKey"]?.jsonPrimitive?.contentOrNull
                val engineOrderId = record["engineOrderId"]?.jsonPrimitive?.contentOrNull
                val brokerOrderId = responseBrokerOrderId(record)
                val responseCode = record["responseCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (
                    record["method"]?.jsonPrimitive?.contentOrNull == "POST" &&
                    endpoint == "/order" &&
                    (responseCode == null || responseCode in 200..299 || responseCode >= 500) &&
                    (
                        engineOrderId in audit.filledOrderIds ||
                            idempotencyKey in audit.filledOrderIds ||
                            brokerOrderId in audit.filledBrokerOrderIds
                    )
                ) {
                    linkedPlacements += 1L
                }
            }
        }
    }
    return TransportSummary(exchanges, linkedPlacements, mutations)
}

private fun responseBrokerOrderId(record: JsonObject): String? {
    val raw = record["responseBody"]?.jsonPrimitive?.contentOrNull ?: return null
    val response = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    val result = response["result"] as? JsonObject ?: return null
    return result["order"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
}
