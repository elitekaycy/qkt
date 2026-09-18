package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.LocalBarStore

/**
 * Reads [LocalMarketSource.bars] from pre-built bar stores: the `--bars` binary tier when
 * injected (exclusively), else the fetched bar store for any covered day. Returns null when
 * neither store can serve the range, so the caller aggregates ticks instead.
 */
internal class PrebuiltBarReader(
    private val barStore: LocalBarStore?,
    private val binaryBarStore: BinaryBarStore?,
) {
    fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle>? {
        val bin = binaryBarStore
        if (bin != null) {
            // --bars research tier: read pre-built binary bars per day (gaps tolerated, like ticks);
            // no slow tick-aggregation fallback. The store stamps the prefixed qktSymbol on read.
            val parts = symbol.split(":", limit = 2)
            val broker = if (parts.size == 2) parts[0] else "BACKTEST"
            val sym = parts.last()
            val days = daysCovering(range)
            val rangeFromMs = range.from.toEpochMilli()
            val rangeToMs = range.to.toEpochMilli()
            return sequence {
                for (day in days) {
                    if (!bin.hasDay(broker, sym, window, day)) continue
                    for (candle in bin.readDay(broker, sym, window, day)) {
                        if (candle.startTime < rangeFromMs) continue
                        if (candle.endTime > rangeToMs) continue
                        yield(candle)
                    }
                }
            }
        }
        val bs = barStore
        if (bs != null) {
            val parts = symbol.split(":", limit = 2)
            if (parts.size == 2) {
                val broker = parts[0]
                val sym = parts[1]
                val tf = window.canonicalSpec()
                val days = daysCovering(range)
                // Gaps are tolerated, as in the binary tier above: a range of more than a few
                // days ALWAYS misses the days the venue did not trade, so requiring every day
                // to be present made the bar store unusable for any multi-day warmup and sent
                // it down the tick-aggregation fallback instead. In a golden-replay store that
                // fallback is actively wrong -- the tick file also holds warmup ticks rehydrated
                // from every stream's timeframe, each carrying its whole bar's volume, so
                // aggregating it into one timeframe sums volume across all of them. Measured on
                // a 4-stream gold capture, a 120-bar 1h warmup read 52,737 on a bar the venue
                // recorded as 9,828; the same warmup shortened to 10 bars (inside one day's
                // file) read it correctly. A store with no coverage at all still falls back,
                // and a short read surfaces through the caller's underfill check rather than
                // silently substituting different numbers.
                val available = days.filter { bs.hasDay(broker, sym, tf, it) }
                if (available.isNotEmpty()) {
                    val rangeFromMs = range.from.toEpochMilli()
                    val rangeToMs = range.to.toEpochMilli()
                    return sequence {
                        for (day in available) {
                            for (candle in bs.readDay(broker, sym, tf, day)) {
                                if (candle.startTime < rangeFromMs) continue
                                if (candle.endTime > rangeToMs) continue
                                yield(candle)
                            }
                        }
                    }
                }
            }
        }
        return null
    }
}
