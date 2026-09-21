package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.RefreshableBars
import org.slf4j.LoggerFactory

/**
 * Waits out a venue whose bar history is behind its own clock.
 *
 * Warmup asks for the newest N closed bars before the live window. A missing recent bar normally
 * means nothing traded - a weekend, a quiet minute - so the loader reaches further back. But a busy
 * MT5 terminal can simply not have the last few bars yet: at 13:07:31 it served 1m bars ending
 * 13:02, the loader accepted five bars from 12:58, and a live RSI(4) was seeded across a four-bar
 * hole while the same session replayed from its ticks was not. Live and backtest then disagree, and
 * live is the one that is wrong.
 *
 * So when the newest bar stops short of the window by no more than [maxGapMs] - lag is minutes, a
 * closed market is hours - the read is repeated up to [attempts] times, [pauseMs] apart, past the
 * source's cache, and the freshest answer wins. If nothing newer ever appears the gap was real and
 * the first answer stands. Only a [RefreshableBars] source is asked twice: files and test fixtures
 * do not lag, so backtests never wait.
 */
internal class WarmupSettle(
    private val maxGapMs: Long = 30L * 60_000L,
    private val attempts: Int = 5,
    private val pauseMs: Long = 2_000L,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    private val log = LoggerFactory.getLogger(WarmupSettle::class.java)

    fun freshest(
        source: MarketSource,
        symbol: String,
        window: TimeWindow,
        upperMs: Long,
        load: () -> LoadedBars,
    ): LoadedBars {
        var best = load()
        val refreshable = (source as? RefreshableBars)?.takeIf { it.canRefresh(symbol) } ?: return best
        val firstGapMs = gapMs(best, upperMs)
        if (firstGapMs <= 0L || firstGapMs > maxGapMs) return best
        repeat(attempts) {
            pause(pauseMs)
            refreshable.forgetBars(symbol, window)
            val next = load()
            if (gapMs(next, upperMs) < gapMs(best, upperMs)) best = next
            if (gapMs(best, upperMs) <= 0L) {
                log.warn(
                    "warmup: {} {}ms history was {}ms behind the live window and has caught up; using the fresh bars",
                    symbol,
                    window.durationMs,
                    firstGapMs,
                )
                return best
            }
        }
        log.warn(
            "warmup: {} {}ms history ends {}ms before the live window after {} re-reads (first read: {}ms); " +
                "treating the gap as a period with no trades",
            symbol,
            window.durationMs,
            gapMs(best, upperMs),
            attempts,
            firstGapMs,
        )
        return best
    }

    private fun gapMs(
        bars: LoadedBars,
        upperMs: Long,
    ): Long = bars.candles.lastOrNull()?.let { upperMs - it.endTime } ?: 0L
}
