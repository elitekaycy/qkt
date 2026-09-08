package com.qkt.marketdata.live.mt5

import com.qkt.candles.BarVolumeSource
import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.TimeRange
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Serves the venue's own `tick_volume` for recently-closed live bars.
 *
 * A polled tick feed cannot reconstruct tick volume: it observes at most one quote per
 * poll, so its count saturates at the poll rate and shifts whenever that rate is retuned
 * (measured on live Exness, a 500ms poller saw 50-74% of the venue's count). The venue
 * publishes the true figure through the same history endpoint the warmup path already
 * uses, so this keeps a small rolling window of it in memory.
 *
 * [refresh] runs on the caller's own schedule, never on the engine thread; [volumeFor] is
 * a map read. A bar that has not been fetched yet returns null and keeps the aggregated
 * count, so a slow or failing history endpoint degrades to today's behaviour rather than
 * stalling the pipeline.
 */
class Mt5BarVolumeSource(
    private val fetcher: Mt5BarFetcher,
    private val window: TimeWindow,
    private val clock: Clock,
    private val retainBars: Int = 240,
) : BarVolumeSource {
    private val log = LoggerFactory.getLogger(javaClass)
    private val volumes = ConcurrentHashMap<Key, BigDecimal>()

    private data class Key(
        val symbol: String,
        val startTimeMs: Long,
    )

    override fun volumeFor(
        symbol: String,
        startTimeMs: Long,
        endTimeMs: Long,
    ): BigDecimal? = volumes[Key(symbol, startTimeMs)]

    /**
     * Pull the last [retainBars] bars for [symbol] and remember their volumes. Safe to call
     * as often as the caller likes; the venue answers from its own history, so repeated calls
     * simply refresh the same window.
     */
    fun refresh(symbol: String) {
        val now = clock.now()
        val from = Instant.ofEpochMilli(now - retainBars * window.durationMs)
        try {
            val bars = fetcher.fetchRange(symbol, window, TimeRange(from, Instant.ofEpochMilli(now)))
            var seen = 0
            for (bar in bars) {
                volumes[Key(bar.symbol, bar.startTime)] = bar.volume
                seen++
            }
            if (seen > 0 && volumes.size > retainBars * 4) {
                val cutoff = now - retainBars * window.durationMs * 2
                volumes.keys.removeIf { it.startTimeMs < cutoff }
            }
        } catch (e: Exception) {
            // History is an enrichment, never a dependency: keep the aggregated count.
            log.debug("bar volume refresh failed for {}: {}", symbol, e.toString())
        }
    }
}
