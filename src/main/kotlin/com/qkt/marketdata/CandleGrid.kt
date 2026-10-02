package com.qkt.marketdata

import com.qkt.candles.TimeWindow

/**
 * Rebuilds [window]-sized bars on the epoch-aligned UTC grid from finer [bars] whose size divides it.
 *
 * A bucket is emitted when it holds at least one finer bar and closes at or before [upperMs] —
 * partial-coverage buckets are legitimate (a week-open 4h bucket only has the hours the venue traded,
 * exactly like a bar built live from ticks).
 */
internal fun rollUpToGrid(
    bars: Sequence<Candle>,
    window: TimeWindow,
    upperMs: Long,
): Sequence<Candle> {
    val durationMs = window.durationMs
    val buckets = linkedMapOf<Long, MutableList<Candle>>()
    for (bar in bars.sortedBy { it.startTime }) {
        buckets.getOrPut((bar.startTime / durationMs) * durationMs) { mutableListOf() }.add(bar)
    }
    return buckets
        .asSequence()
        .filter { (start, _) -> start + durationMs <= upperMs }
        .map { (start, inBucket) ->
            Candle(
                symbol = inBucket.first().symbol,
                open = inBucket.first().open,
                high = inBucket.maxOf { it.high },
                low = inBucket.minOf { it.low },
                close = inBucket.last().close,
                volume = inBucket.sumOf { it.volume },
                startTime = start,
                endTime = start + durationMs,
                bid = inBucket.last().bid,
                ask = inBucket.last().ask,
            )
        }
}
