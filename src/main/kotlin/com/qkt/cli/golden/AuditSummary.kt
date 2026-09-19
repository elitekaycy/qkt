package com.qkt.cli.golden

import java.math.BigDecimal
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What an engine audit journal holds for one session: its time window, event counts and filled orders. */
internal data class AuditSummary(
    val firstTimestampMs: Long,
    val lastTimestampMs: Long,
    val tickCount: Long,
    val warmupTickCount: Long,
    val candleCount: Long,
    val streamCandleCount: Long,
    val strategyCandleEvaluationCount: Long,
    val fillCount: Long,
    val filledOrderIds: Set<String>,
    val filledBrokerOrderIds: Set<String>,
)

/** Scans a session's audit journal files, checking every market record is structured, and summarizes them. */
internal fun scanAudit(files: List<Path>): AuditSummary {
    var first = Long.MAX_VALUE
    var last = Long.MIN_VALUE
    var ticks = 0L
    var warmupTicks = 0L
    var candles = 0L
    var streamCandles = 0L
    var strategyCandleEvaluations = 0L
    var fills = 0L
    val filledOrderIds = mutableSetOf<String>()
    val filledBrokerOrderIds = mutableSetOf<String>()
    for (file in files) {
        journalReader(file).use { reader ->
            var lineNumber = 0L
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber += 1L
                if (line.isBlank()) continue
                val record = parseRecord(file, lineNumber, line)
                val timestamp = timestamp(record, file, lineNumber)
                first = minOf(first, timestamp)
                last = maxOf(last, timestamp)
                when (record["eventType"]?.jsonPrimitive?.contentOrNull) {
                    "com.qkt.events.TickEvent" -> {
                        requireStructuredTick(record, file, lineNumber)
                        ticks += 1L
                    }
                    "com.qkt.events.WarmupTickEvent" -> {
                        requireStructuredTick(record, file, lineNumber)
                        warmupTicks += 1L
                    }
                    "com.qkt.events.CandleEvent" -> {
                        requireStructuredCandle(record, file, lineNumber)
                        candles += 1L
                    }
                    "com.qkt.events.StreamCandleEvent" -> {
                        requireText(record, "broker", file, lineNumber)
                        requireText(record, "timeframe", file, lineNumber)
                        requireStructuredCandle(record, file, lineNumber)
                        streamCandles += 1L
                    }
                    "com.qkt.events.StrategyCandleEvaluatedEvent" -> {
                        requireText(record, "strategyId", file, lineNumber)
                        requireText(record, "alias", file, lineNumber)
                        requireText(record, "broker", file, lineNumber)
                        requireText(record, "timeframe", file, lineNumber)
                        requireStructuredCandle(record, file, lineNumber)
                        strategyCandleEvaluations += 1L
                    }
                    "com.qkt.events.BrokerEvent.OrderFilled",
                    "com.qkt.events.BrokerEvent.OrderPartiallyFilled",
                    -> {
                        fills += 1L
                        record["orderId"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.takeIf(String::isNotBlank)
                            ?.let(filledOrderIds::add)
                        (record["fill"] as? JsonObject)
                            ?.get("brokerOrderId")
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.takeIf(String::isNotBlank)
                            ?.let(filledBrokerOrderIds::add)
                    }
                }
            }
        }
    }
    require(first != Long.MAX_VALUE) { "engine audit journal is empty" }
    return AuditSummary(
        first,
        last,
        ticks,
        warmupTicks,
        candles,
        streamCandles,
        strategyCandleEvaluations,
        fills,
        filledOrderIds,
        filledBrokerOrderIds,
    )
}

private fun requireStructuredTick(
    record: JsonObject,
    file: Path,
    lineNumber: Long,
) {
    requireText(record, "symbol", file, lineNumber)
    val tick =
        record["tick"] as? JsonObject
            ?: throw IllegalArgumentException("missing structured tick at $file:$lineNumber")
    requireLong(tick, "timestampMs", file, lineNumber)
    requireDecimal(tick, "price", file, lineNumber)
}

private fun requireStructuredCandle(
    record: JsonObject,
    file: Path,
    lineNumber: Long,
) {
    requireText(record, "symbol", file, lineNumber)
    val candle =
        record["candle"] as? JsonObject
            ?: throw IllegalArgumentException("missing structured candle at $file:$lineNumber")
    requireLong(candle, "startTimeMs", file, lineNumber)
    requireLong(candle, "endTimeMs", file, lineNumber)
    for (field in listOf("open", "high", "low", "close", "volume")) {
        requireDecimal(candle, field, file, lineNumber)
    }
}

private fun requireText(
    record: JsonObject,
    field: String,
    file: Path,
    lineNumber: Long,
): String =
    record[field]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("missing $field at $file:$lineNumber")

private fun requireLong(
    record: JsonObject,
    field: String,
    file: Path,
    lineNumber: Long,
): Long =
    record[field]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toLongOrNull()
        ?: throw IllegalArgumentException("missing numeric $field at $file:$lineNumber")

private fun requireDecimal(
    record: JsonObject,
    field: String,
    file: Path,
    lineNumber: Long,
): BigDecimal =
    record[field]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.let { runCatching { BigDecimal(it) }.getOrNull() }
        ?: throw IllegalArgumentException("missing decimal $field at $file:$lineNumber")
