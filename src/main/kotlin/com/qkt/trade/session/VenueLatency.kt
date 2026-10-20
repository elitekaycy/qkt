package com.qkt.trade.session

import com.qkt.marketdata.Tick

/**
 * The venue's round trip as a live record measured it: for each intent the venue filled, the time from the tick
 * it fired on (the first recorded tick stamped after it arrived) to the venue's fill; the median over all, or
 * null when the record has no fill. A replay with this latency fills at the quote standing when it ends, as the
 * venue did (2026-10-20 Exness demo: 271 and 296 ms).
 */
fun measuredVenueLatencyMs(
    intents: List<JournaledIntent>,
    filledAt: Map<String, Long>,
    ticks: List<Tick>,
): Long? {
    val trips =
        intents
            .mapNotNull { intent ->
                val filled = filledAt[intent.intentId] ?: return@mapNotNull null
                val fired = ticks.firstOrNull { it.timestamp > intent.simMs } ?: return@mapNotNull null
                (filled - fired.timestamp).takeIf { it >= 0L }
            }.sorted()
    if (trips.isEmpty()) return null
    return trips[(trips.size - 1) / 2]
}
