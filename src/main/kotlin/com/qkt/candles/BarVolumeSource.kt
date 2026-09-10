package com.qkt.candles

import java.math.BigDecimal

/**
 * Venue-reported tick volume for a closed bar.
 *
 * Bars aggregated from a polled tick feed cannot carry true tick volume: the poller sees
 * at most one quote per interval, so its count saturates at `60000 / pollIntervalMs` per
 * minute and moves whenever that interval is retuned. Measured against live Exness, a
 * 500ms poller observed 50-74% of the venue's own count. A backtest replaying a stored
 * tick feed sees every tick and so counts correctly, which makes the aggregated figure a
 * live-only distortion of the same field.
 *
 * An implementation returns the venue's figure for an exactly-matching bar, or null to
 * leave the aggregated value alone. Lookups happen on the engine thread as each bar
 * closes, so an implementation MUST answer from memory and never block on I/O; the MT5
 * implementation keeps a small window of recent bars refreshed by its own thread.
 */
fun interface BarVolumeSource {
    fun volumeFor(
        symbol: String,
        startTimeMs: Long,
        endTimeMs: Long,
    ): BigDecimal?
}
