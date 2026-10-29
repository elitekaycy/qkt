package com.qkt.connector.mt5.marketdata

import com.qkt.candles.TimeWindow
import com.qkt.common.SymbolCalendars
import java.time.Instant

/**
 * Number of [window]-sized slots starting in `[fromMs, toMs)` whose start the symbol's
 * calendar marks as in session — the bars that could actually have closed in that span.
 * Bounded by the span itself; a weekend of 1m slots is a few thousand cheap checks, once
 * per warmup fetch.
 */
internal fun SymbolCalendars.inSessionSlotsBetween(
    bareSymbol: String,
    window: TimeWindow,
    fromMs: Long,
    toMs: Long,
): Long {
    if (toMs <= fromMs) return 0L
    val calendar = calendarFor(bareSymbol)
    var slots = 0L
    var slotStart = fromMs
    while (slotStart < toMs) {
        if (calendar.isInSession(bareSymbol, Instant.ofEpochMilli(slotStart))) slots += 1L
        slotStart += window.durationMs
    }
    return slots
}
