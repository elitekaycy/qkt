package com.qkt.cli.golden

import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TICK_EVENT = "com.qkt.events.TickEvent"
private const val WARMUP_TICK_EVENT = "com.qkt.events.WarmupTickEvent"
private const val CANDLE_EVENT = "com.qkt.events.CandleEvent"
private const val STREAM_CANDLE_EVENT = "com.qkt.events.StreamCandleEvent"
private const val STRATEGY_CANDLE_EVALUATED_EVENT = "com.qkt.events.StrategyCandleEvaluatedEvent"

/**
 * Reads the market events out of a verified bundle's engine journals and checks their counts
 * against the manifest. Ticks come back ordered by timestamp, then journal sequence.
 */
internal fun readMarketRecords(
    zip: ZipFile,
    engineNames: List<String>,
    manifest: JsonObject,
): GoldenMarketCapture {
    val ticks = mutableListOf<RecordedTick>()
    val candles = mutableListOf<RecordedCandle>()
    val streamCandles = mutableListOf<RecordedCandle>()
    var strategyCandleEvaluations = 0L
    val sequences = mutableSetOf<Long>()
    for (name in engineNames) {
        zip.getInputStream(zip.getEntry(name)).bufferedReader(StandardCharsets.UTF_8).use { reader ->
            var lineNumber = 0L
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber += 1L
                if (line.isBlank()) continue
                val record = parseObject(line, name, lineNumber)
                val sequence = requireLong(record, "seq", name, lineNumber)
                require(sequences.add(sequence)) { "duplicate engine sequence $sequence in $name:$lineNumber" }
                when (record["eventType"]?.jsonPrimitive?.contentOrNull) {
                    TICK_EVENT -> ticks.add(readTick(record, sequence, warmup = false, name, lineNumber))
                    WARMUP_TICK_EVENT -> ticks.add(readTick(record, sequence, warmup = true, name, lineNumber))
                    CANDLE_EVENT -> candles.add(readCandle(record, sequence, name, lineNumber))
                    STREAM_CANDLE_EVENT ->
                        streamCandles.add(readStreamCandle(record, sequence, name, lineNumber))
                    STRATEGY_CANDLE_EVALUATED_EVENT -> strategyCandleEvaluations += 1L
                }
            }
        }
    }
    val counts =
        manifest["counts"]?.jsonObject
            ?: throw IllegalArgumentException("golden bundle has no counts")
    require(ticks.count { !it.warmup }.toLong() == requireLong(counts, "ticks", "manifest.json", 1L)) {
        "golden live tick count does not match manifest"
    }
    require(ticks.count { it.warmup }.toLong() == requireLong(counts, "warmupTicks", "manifest.json", 1L)) {
        "golden warmup tick count does not match manifest"
    }
    require(candles.size.toLong() == requireLong(counts, "candles", "manifest.json", 1L)) {
        "golden candle count does not match manifest"
    }
    val expectedStreamCandles = optionalLong(counts, "streamCandles", "manifest.json", 1L) ?: 0L
    require(streamCandles.size.toLong() == expectedStreamCandles) {
        "golden stream candle count does not match manifest"
    }
    val expectedStrategyEvaluations =
        optionalLong(counts, "strategyCandleEvaluations", "manifest.json", 1L) ?: 0L
    require(strategyCandleEvaluations == expectedStrategyEvaluations) {
        "golden strategy candle evaluation count does not match manifest"
    }
    require(ticks.any { !it.warmup }) { "golden bundle has no live ticks" }
    return GoldenMarketCapture(
        manifest,
        ticks.sortedWith(compareBy({ it.tick.timestamp }, { it.sequence })),
        candles,
        streamCandles,
        strategyCandleEvaluations,
    )
}
