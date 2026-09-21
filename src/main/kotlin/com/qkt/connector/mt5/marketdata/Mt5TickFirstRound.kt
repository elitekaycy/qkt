package com.qkt.connector.mt5.marketdata

import java.time.Instant
import org.slf4j.Logger

/**
 * Logs, once per symbol, how far the broker's tick clock sits from the local one - the first thing
 * to read when `server_time_zone` is wrong. Measured on the newest tick of the symbol's first
 * round: that round reaches back to the start of the minute, so its oldest tick is old by design,
 * e.g. a first round at 17:03:52 whose ticks run 17:03:00..17:03:51.8 reports skewMs=-200.
 */
internal fun logFirstRoundClock(
    log: Logger,
    fresh: List<Pair<String, Mt5TickClient.Mt5Tick>>,
    known: Set<String>,
    roundNowMs: Long,
) {
    fresh
        .filter { (sym, _) -> sym !in known }
        .groupBy({ it.first }, { it.second.brokerTimeMs })
        .forEach { (sym, times) ->
            val newest = times.max()
            log.info(
                "MT5 tick clock check symbol={} brokerUtc={} localUtc={} skewMs={}",
                sym,
                Instant.ofEpochMilli(newest),
                Instant.ofEpochMilli(roundNowMs),
                newest - roundNowMs,
            )
        }
}
