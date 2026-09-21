package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.MarketSource
import java.time.Instant

/**
 * The part of a window that had already elapsed when a session started.
 *
 * Warmup seeds closed bars only. A session that starts part-way through a 1h, 4h or 1d window
 * would otherwise build that bar from the ticks it sees after the start: the close is right, the
 * open, high and low are not, and every range-based indicator carries the error for its whole
 * lookback (#1196). The elapsed part is read as closed one-minute bars, so nothing later than
 * the last whole minute before `now` is used and no look-ahead is possible.
 *
 * e.g. a 4h stream started at 09:37 loads the 1m bars of 08:00..09:37 and folds them into one
 * partial candle spanning 08:00..12:00 that the live ticks then continue.
 *
 * The minutes are read through [WarmupSettle], like warmup history: a venue whose history is behind
 * its own clock would otherwise leave the last minutes out of the live bar while a replay of the
 * same session, reading the history later, has them.
 */
internal class FormingBar(
    /** The closed one-minute bars of the elapsed part, oldest first. */
    val minutes: List<Candle>,
    /** Those bars folded into one candle spanning the whole window. */
    val partial: Candle,
) {
    companion object {
        /** Null for a one-minute stream, an aligned start, or a source with no bars for the range. */
        fun load(
            source: MarketSource,
            symbol: String,
            window: TimeWindow,
            nowMs: Long,
            settle: WarmupSettle = WarmupSettle(),
        ): FormingBar? {
            if (window.durationMs <= TimeWindow.ONE_MINUTE.durationMs) return null
            val windowStart = window.windowStartFor(nowMs)
            val upper = TimeWindow.ONE_MINUTE.windowStartFor(nowMs)
            if (upper <= windowStart) return null
            val range = TimeRange(Instant.ofEpochMilli(windowStart), Instant.ofEpochMilli(upper))
            val minutes =
                settle
                    .freshest(source, symbol, TimeWindow.ONE_MINUTE, upper) {
                        val read =
                            source
                                .bars(symbol, TimeWindow.ONE_MINUTE, range)
                                .filter { it.startTime >= windowStart && it.endTime <= upper }
                                .distinctBy { it.startTime }
                                .sortedBy { it.startTime }
                                .toList()
                        LoadedBars(read, upper - windowStart)
                    }.candles
            if (minutes.isEmpty()) return null
            val partial =
                Candle(
                    symbol = symbol,
                    open = minutes.first().open,
                    high = minutes.maxOf { it.high },
                    low = minutes.minOf { it.low },
                    close = minutes.last().close,
                    volume = minutes.fold(java.math.BigDecimal.ZERO) { acc, c -> acc.add(c.volume) },
                    startTime = windowStart,
                    endTime = windowStart + window.durationMs,
                )
            return FormingBar(minutes, partial)
        }
    }
}
