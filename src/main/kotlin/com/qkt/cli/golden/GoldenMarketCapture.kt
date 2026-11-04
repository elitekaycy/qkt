package com.qkt.cli.golden

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import kotlinx.serialization.json.JsonObject

/** Bar provenance: the engine's own tick-aggregated `CandleEvent`. */
internal const val CAPTURED_CANDLE_EVENT = "CAPTURED_CANDLE_EVENT"

/** Bar provenance: the gateway's `StreamCandleEvent`. */
internal const val CAPTURED_STREAM_CANDLE_EVENT = "CAPTURED_STREAM_CANDLE_EVENT"

/** Bar provenance: rebuilt from warmup ticks that carry their source timeframe. */
internal const val REHYDRATED_WARMUP_TICKS = "REHYDRATED_WARMUP_TICKS"

/**
 * One tick read from a golden engine journal, with its journal sequence and warmup origin. [receivedMs] is
 * when a live tick reached qkt (its journal record's time); null for a warmup tick, which was seeded, not received.
 */
internal data class RecordedTick(
    val sequence: Long,
    val warmup: Boolean,
    val sourceTimeframeMs: Long?,
    val tick: Tick,
    val receivedMs: Long? = null,
)

/** One bar read from (or rebuilt out of) a golden engine journal, tagged with its provenance. */
internal data class RecordedCandle(
    val sequence: Long,
    val candle: Candle,
    val provenance: String,
)

/**
 * The verified market records of a golden bundle, plus the bundle manifest they were checked against.
 * [firstEvaluatedCandle] is the earliest-closing bar a strategy evaluated live, null when none did.
 */
internal data class GoldenMarketCapture(
    val manifest: JsonObject,
    val ticks: List<RecordedTick>,
    val candles: List<RecordedCandle>,
    val streamCandles: List<RecordedCandle>,
    val strategyCandleEvaluations: Long,
    val firstEvaluatedCandle: Candle? = null,
)
