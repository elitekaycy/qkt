package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/**
 * Buckets [ticks] into [window]-aligned OHLCV candles for [symbol], lazily: the fallback a
 * local read uses when no pre-built bar store covers the range. A tick without volume adds
 * nothing to its bucket's volume.
 */
internal fun aggregateTicksToCandles(
    symbol: String,
    window: TimeWindow,
    ticks: Sequence<Tick>,
): Sequence<Candle> =
    sequence {
        var bucketStart: Long = -1
        var bucketEnd: Long = -1
        var open: BigDecimal = Money.ZERO
        var high: BigDecimal = Money.ZERO
        var low: BigDecimal = Money.ZERO
        var close: BigDecimal = Money.ZERO
        var volume: BigDecimal = Money.ZERO
        var hasData = false

        for (tick in ticks) {
            val ws = window.windowStartFor(tick.timestamp)
            if (!hasData) {
                bucketStart = ws
                bucketEnd = ws + window.durationMs
                open = tick.price
                high = tick.price
                low = tick.price
                close = tick.price
                volume = tick.volume ?: Money.ZERO
                hasData = true
                continue
            }
            if (tick.timestamp >= bucketEnd) {
                yield(Candle(symbol, open, high, low, close, volume, bucketStart, bucketEnd))
                bucketStart = ws
                bucketEnd = ws + window.durationMs
                open = tick.price
                high = tick.price
                low = tick.price
                close = tick.price
                volume = tick.volume ?: Money.ZERO
            } else {
                if (tick.price > high) high = tick.price
                if (tick.price < low) low = tick.price
                close = tick.price
                if (tick.volume != null) volume = volume.add(tick.volume)
            }
        }
        if (hasData) {
            yield(Candle(symbol, open, high, low, close, volume, bucketStart, bucketEnd))
        }
    }
