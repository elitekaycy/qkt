package com.qkt.trade.session

import com.qkt.marketdata.Tick

/**
 * Where each intent fired among a live record's [ticks]: the index of the tick its fate names ([firedTickMs]),
 * else of the first tick stamped after it arrived. Intents with neither are absent.
 */
fun firedTickIndex(
    intents: List<JournaledIntent>,
    firedTickMs: Map<String, Long>,
    ticks: List<Tick>,
): Map<String, Int> =
    intents
        .mapNotNull { intent ->
            val named = firedTickMs[intent.intentId]?.let { ms -> ticks.indexOfFirst { it.timestamp == ms } }
            val index = named?.takeIf { it >= 0 } ?: ticks.indexOfFirst { it.timestamp > intent.simMs }
            index.takeIf { it >= 0 }?.let { intent.intentId to it }
        }.toMap()

/**
 * The venue's round trip as a live record measured it: for each intent the venue filled, the time from the tick
 * it fired on ([firedAt]) to the venue's fill ([filledAt]); the median over all, or null when the record has no
 * fill. A replay with this latency fills at the quote standing when it ends, as the venue did (2026-10-20 Exness
 * demo: 271 and 296 ms on one session, 297-1007 ms on the next).
 */
fun measuredVenueLatencyMs(
    firedAt: Map<String, Long>,
    filledAt: Map<String, Long>,
): Long? {
    val trips =
        firedAt
            .mapNotNull { (id, fired) -> filledAt[id]?.let { it - fired }?.takeIf { it >= 0L } }
            .sorted()
    if (trips.isEmpty()) return null
    return trips[(trips.size - 1) / 2]
}
