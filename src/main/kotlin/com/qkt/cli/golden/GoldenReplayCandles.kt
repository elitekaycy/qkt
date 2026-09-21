package com.qkt.cli.golden

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle
import java.math.BigDecimal

/**
 * Merges every source of bars in a capture (captured candles, stream candles and bars rehydrated
 * from warmup ticks) into one list per bar identity, ordered by start time then sequence. A bar
 * seen from several sources keeps the record with the highest-priority provenance; an OHLC
 * disagreement between sources fails closed - with one exception, below.
 *
 * A bar that closed before the session's first live tick was built by the engine purely from
 * warmup ticks, and warmup feeds every timeframe's ticks of a symbol through the same aggregator.
 * MT5 converts bid bars to mid with each bar's own spread, so one M5 bar and the five M1 bars it
 * covers disagree whenever the spread moves inside it (42 then 48 points near the New York
 * rollover): the engine's mixed bar matches neither. The stream itself was seeded with its own
 * timeframe's bar - the rehydrated one - so that is the bar a replay must use.
 */
internal fun replayCandles(capture: GoldenMarketCapture): List<RecordedCandle> {
    val firstLiveTick =
        capture.ticks
            .filterNot { it.warmup }
            .groupBy { it.tick.symbol }
            .mapValues { (_, ticks) -> ticks.minOf { it.tick.timestamp } }

    fun builtFromWarmup(record: RecordedCandle): Boolean =
        record.provenance == CAPTURED_CANDLE_EVENT &&
            record.candle.endTime <= (firstLiveTick[record.candle.symbol] ?: Long.MIN_VALUE)
    val merged = linkedMapOf<BarIdentity, RecordedCandle>()
    for (record in capture.candles + capture.streamCandles + rehydrateWarmupCandles(capture.ticks)) {
        val candle = record.candle
        val identity = BarIdentity(candle.symbol, candle.startTime, candle.endTime)
        val existing = merged[identity]
        if (existing == null) {
            merged[identity] = record
            continue
        }
        if (!sameCandle(existing.candle, candle)) {
            val rehydrated = listOf(existing, record).singleOrNull { it.provenance == REHYDRATED_WARMUP_TICKS }
            val warmupBuilt = listOf(existing, record).singleOrNull(::builtFromWarmup)
            require(rehydrated != null && warmupBuilt != null) { "conflicting golden candles for $identity" }
            merged[identity] = rehydrated
            continue
        }
        if (provenancePriority(record.provenance) > provenancePriority(existing.provenance)) {
            merged[identity] = record
        }
    }
    return merged.values.sortedWith(compareBy({ it.candle.startTime }, { it.sequence }))
}

private fun rehydrateWarmupCandles(records: List<RecordedTick>): List<RecordedCandle> =
    records
        .filter { it.warmup && it.sourceTimeframeMs != null }
        .groupBy { it.tick.symbol to checkNotNull(it.sourceTimeframeMs) }
        .flatMap { (_, streamRecords) ->
            val timeframeMs = checkNotNull(streamRecords.first().sourceTimeframeMs)
            require(timeframeMs % 1_000L == 0L) { "unsupported sub-second warmup timeframe: ${timeframeMs}ms" }
            val emitted = mutableListOf<RecordedCandle>()
            val sequence = streamRecords.minOf { it.sequence }
            val aggregator =
                CandleAggregator.standalone(TimeWindow(timeframeMs)) { candle ->
                    emitted.add(RecordedCandle(sequence, candle, REHYDRATED_WARMUP_TICKS))
                }
            val sorted = streamRecords.sortedWith(compareBy({ it.tick.timestamp }, { it.sequence }))
            for (record in sorted) aggregator.onTick(record.tick)
            val lastTick = sorted.last().tick.timestamp
            aggregator.flushClosed(lastTick + timeframeMs)
            emitted
        }

/**
 * Whether two records of the same bar agree on what the bar was.
 *
 * One bar legitimately reaches the journal from three places: the tick aggregator's
 * `CandleEvent`, the gateway's `StreamCandleEvent`, and -- when warmup ticks overlap the
 * session -- a candle rehydrated from those ticks. Only the OHLC has to agree; a genuine
 * disagreement there means the capture is corrupt and still fails closed.
 *
 * Volume and quotes deliberately do not. The aggregator counts the ticks it saw while the
 * stream candle carries the venue's own figure, so the same bar arrives as `volume=1` on one
 * path and `volume=0` on the other; and warmup ticks carry no bid/ask at all, so a rehydrated
 * candle has no quotes to compare. Treating either as a conflict made an otherwise sound live
 * capture refuse to materialize, which cost the ability to replay that session at all. The
 * merged record keeps the values of the highest-priority provenance, so the captured bar wins
 * over the reconstructed one.
 */
private fun sameCandle(
    left: Candle,
    right: Candle,
): Boolean =
    left.symbol == right.symbol &&
        left.startTime == right.startTime &&
        left.endTime == right.endTime &&
        left.open.compareTo(right.open) == 0 &&
        left.high.compareTo(right.high) == 0 &&
        left.low.compareTo(right.low) == 0 &&
        left.close.compareTo(right.close) == 0 &&
        quotesCompatible(left.bid, right.bid) &&
        quotesCompatible(left.ask, right.ask)

/** Equal when both sides carry the quote; compatible when either side simply has none. */
private fun quotesCompatible(
    left: BigDecimal?,
    right: BigDecimal?,
): Boolean = left == null || right == null || left.compareTo(right) == 0

private fun provenancePriority(provenance: String): Int =
    when (provenance) {
        CAPTURED_STREAM_CANDLE_EVENT -> 3
        CAPTURED_CANDLE_EVENT -> 2
        REHYDRATED_WARMUP_TICKS -> 1
        else -> 0
    }

private data class BarIdentity(
    val symbol: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
)
