package com.qkt.dsl.compile

import com.qkt.candles.TimeWindow

/**
 * Where `POSITION.<stream>.holding_duration` starts counting: the open of the bar the position
 * opened in, on the finest timeframe the strategy declares for the position's symbol (#1633).
 *
 * Time exits are evaluated at bar closes, so the hold is measured on the same bar clock. A market
 * entry from a bar-close signal fills on the first executable price after that close: exactly on
 * the boundary in a bar backtest, a few hundred milliseconds later in a tick replay, and 1-60 s
 * later live. Counting from the raw fill time made the live position a few seconds short on the
 * bar where the hold had elapsed, so the exit waited one more bar. Anchored to the bar boundary,
 * all three exit on the same bar, and a bar backtest's boundary fill is unchanged.
 *
 * e.g. 4h stream, fill at 04:00:01: anchored at 04:00, so `holding_duration >= 518400` closes on the
 * 04:00 bar six days later, as the bar backtest does, not at 08:00.
 *
 * Consequences, all equal across bar backtest, tick replay and live:
 * - A pending (stop/limit) entry filled inside a bar counts from that bar's open, the only intrabar
 *   time a bar replay knows.
 * - The finest declared timeframe on the symbol is used, so a coarser alias read by a time exit
 *   cannot pull the anchor back more than one of the strategy's own finest bars.
 * - A session or weekend gap is still counted as elapsed time; the first fill after a gap anchors
 *   to the bar that holds it, as the bar store stamps that bar.
 * - Restart adoption keeps the venue's open time on the leg; it is anchored here like any fill.
 *
 * Only the measurement changes: the leg's `openedAt` (used by funding, swap and excursions) stays
 * the real fill time.
 */
internal class HoldingBarClock {
    private class Cached(
        val streams: Map<String, HubKey>,
        val symbol: String,
        val barMs: Long?,
    )

    // One strategy's closure; the streams map is the same instance on every evaluation, so the
    // timeframe scan runs once rather than per bar close.
    @Volatile private var cached: Cached? = null

    /** The open of the bar [openedAt] falls in, or [openedAt] when no declared timeframe parses. */
    fun anchor(
        streams: Map<String, HubKey>,
        symbol: String,
        openedAt: Long,
    ): Long {
        val barMs = barMsFor(streams, symbol) ?: return openedAt
        return Math.floorDiv(openedAt, barMs) * barMs
    }

    private fun barMsFor(
        streams: Map<String, HubKey>,
        symbol: String,
    ): Long? {
        val hit = cached
        if (hit != null && hit.streams === streams && hit.symbol == symbol) return hit.barMs
        val barMs =
            streams.values
                .filter { it.qktSymbol == symbol }
                .mapNotNull { key -> runCatching { TimeWindow.parse(key.timeframe).durationMs }.getOrNull() }
                .minOrNull()
        cached = Cached(streams, symbol, barMs)
        return barMs
    }
}
