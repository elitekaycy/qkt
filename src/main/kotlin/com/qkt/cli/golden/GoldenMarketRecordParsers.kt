package com.qkt.cli.golden

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import kotlinx.serialization.json.JsonObject

/** Parses and sanity-checks one structured tick record (live or warmup) from an engine journal. */
internal fun readTick(
    record: JsonObject,
    sequence: Long,
    warmup: Boolean,
    source: String,
    lineNumber: Long,
): RecordedTick {
    val symbol = requireQktSymbol(record, source, lineNumber)
    val tick =
        record["tick"] as? JsonObject
            ?: throw IllegalArgumentException("missing structured tick at $source:$lineNumber")
    val value =
        Tick(
            symbol = symbol,
            price = requireDecimal(tick, "price", source, lineNumber),
            timestamp = requireLong(tick, "timestampMs", source, lineNumber),
            volume = optionalDecimal(tick, "volume", source, lineNumber),
            bid = optionalDecimal(tick, "bid", source, lineNumber),
            ask = optionalDecimal(tick, "ask", source, lineNumber),
            bidVolume = optionalDecimal(tick, "bidVolume", source, lineNumber),
            askVolume = optionalDecimal(tick, "askVolume", source, lineNumber),
        )
    require(value.timestamp >= 0L && value.price.signum() > 0) { "invalid tick at $source:$lineNumber" }
    require(value.bid == null || value.ask == null || value.bid <= value.ask) {
        "crossed tick quote at $source:$lineNumber"
    }
    val sourceTimeframeMs =
        if (warmup) optionalLong(record, "sourceTimeframeMs", source, lineNumber) else null
    require(sourceTimeframeMs == null || sourceTimeframeMs > 0L) {
        "invalid warmup sourceTimeframeMs at $source:$lineNumber"
    }
    return RecordedTick(sequence, warmup, sourceTimeframeMs, value)
}

/** Parses a gateway stream candle and checks its broker and timeframe agree with the bar itself. */
internal fun readStreamCandle(
    record: JsonObject,
    sequence: Long,
    source: String,
    lineNumber: Long,
): RecordedCandle {
    val broker = requireText(record, "broker", source, lineNumber)
    val timeframe = requireText(record, "timeframe", source, lineNumber)
    val recorded =
        readCandle(
            record,
            sequence,
            source,
            lineNumber,
            provenance = CAPTURED_STREAM_CANDLE_EVENT,
        )
    val symbolBroker = splitSymbol(recorded.candle.symbol).first
    require(symbolBroker == broker) { "stream candle broker mismatch at $source:$lineNumber" }
    val window = TimeWindow.parse(timeframe)
    require(recorded.candle.endTime - recorded.candle.startTime == window.durationMs) {
        "stream candle timeframe mismatch at $source:$lineNumber"
    }
    return recorded
}

/** Parses and sanity-checks one structured candle record from an engine journal. */
internal fun readCandle(
    record: JsonObject,
    sequence: Long,
    source: String,
    lineNumber: Long,
    provenance: String = CAPTURED_CANDLE_EVENT,
): RecordedCandle {
    val symbol = requireQktSymbol(record, source, lineNumber)
    val value =
        record["candle"] as? JsonObject
            ?: throw IllegalArgumentException("missing structured candle at $source:$lineNumber")
    val candle =
        Candle(
            symbol = symbol,
            open = requireDecimal(value, "open", source, lineNumber),
            high = requireDecimal(value, "high", source, lineNumber),
            low = requireDecimal(value, "low", source, lineNumber),
            close = requireDecimal(value, "close", source, lineNumber),
            volume = requireDecimal(value, "volume", source, lineNumber),
            startTime = requireLong(value, "startTimeMs", source, lineNumber),
            endTime = requireLong(value, "endTimeMs", source, lineNumber),
            bid = optionalDecimal(value, "bid", source, lineNumber),
            ask = optionalDecimal(value, "ask", source, lineNumber),
        )
    require(candle.startTime >= 0L && candle.endTime > candle.startTime) {
        "invalid candle window at $source:$lineNumber"
    }
    require(
        candle.low <= candle.open &&
            candle.low <= candle.close &&
            candle.high >= candle.open &&
            candle.high >= candle.close,
    ) {
        "invalid candle OHLC at $source:$lineNumber"
    }
    require(candle.volume.signum() >= 0) { "negative candle volume at $source:$lineNumber" }
    return RecordedCandle(sequence, candle, provenance)
}
