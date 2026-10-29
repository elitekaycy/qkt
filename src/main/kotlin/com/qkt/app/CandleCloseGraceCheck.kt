package com.qkt.app

import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import org.slf4j.LoggerFactory

/**
 * Names the live symbols whose source delivers ticks later than `runtime.candle_close_grace_ms` waits for them.
 *
 * The heartbeat closes a quiet symbol's bar at window end + grace. A polled source hands over a tick up to one poll
 * after its stamp, so with a grace shorter than that every bar closes before its last poll lands, and
 * [com.qkt.candles.CandleAggregator] drops those ticks as late: the bar under-reports the venue and live decides on
 * different bars than a replay of the same ticks. Said once at session start, with the grace that fits, instead of
 * only as throttled drop warnings later.
 */
object CandleCloseGraceCheck {
    private val log = LoggerFactory.getLogger(CandleCloseGraceCheck::class.java)

    /** Opens the live feed for [symbols] and says, once, which of them their source delivers later than the grace. */
    fun openLiveTicks(
        source: MarketSource,
        symbols: List<String>,
        candleCloseGraceMs: Long,
    ): TickFeed =
        source.liveTicks(symbols).also { warnings(source, symbols, candleCloseGraceMs).forEach { log.warn(it) } }

    fun warnings(
        source: MarketSource,
        symbols: List<String>,
        candleCloseGraceMs: Long,
    ): List<String> =
        symbols
            .associateWith { source.tickDeliveryLagMsFor(it) }
            .filterValues { it > 0L && it >= candleCloseGraceMs }
            .entries
            .groupBy({ it.value }, { it.key })
            .toSortedMap(reverseOrder())
            .map { (lagMs, late) ->
                "runtime.candle_close_grace_ms is $candleCloseGraceMs ms but ${late.sorted().joinToString()} " +
                    "deliver ticks up to $lagMs ms after their stamp (one poll): their bars close before the last " +
                    "poll lands and under-report the venue. Set runtime.candle_close_grace_ms to at least " +
                    "${fitting(lagMs)} ms (replay reads the same value)"
            }

    /** One poll plus half of one more for the poll's own round trip, which runs late under load. */
    fun fitting(lagMs: Long): Long = lagMs + lagMs / 2
}
