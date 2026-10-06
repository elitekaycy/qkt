package com.qkt.derivatives.futures

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The one rule that prices a roll, shared by the history built from stored bars
 * ([RollHistoryBuilder]) and the roll measured live ([LiveRollMeasurer]), so both give the same
 * record: a contract's reference price is the close of its last bar that has closed at or before the
 * roll instant. Intraday bars (1-minute ones live) are looked for over the roll's UTC day and the day
 * before; daily bars over the week up to the roll, since a weekend or holiday leaves no daily bar the
 * day before a Monday roll.
 */
internal object RollPricing {
    private const val DAY_MS = 86_400_000L
    private const val DAILY_LOOKBACK_DAYS = 7L

    /** The UTC days a roll at [atMs] is priced from with bars [barMs] long, newest first. */
    fun days(
        atMs: Long,
        barMs: Long = 60_000L,
    ): List<LocalDate> {
        val day = Instant.ofEpochMilli(atMs).atZone(ZoneOffset.UTC).toLocalDate()
        val back = if (barMs >= DAY_MS) DAILY_LOOKBACK_DAYS else 1L
        return (0L..back).map(day::minusDays)
    }

    /** The first instant whose bars can price a roll at [atMs]: the start of the day before its day. */
    fun lookbackStartMs(atMs: Long): Long =
        days(atMs)
            .last()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

    /** The close of the last of [bars] that closed at or before [atMs], or null when none did. */
    fun closeAtOrBefore(
        bars: List<Candle>,
        atMs: Long,
    ): BigDecimal? = bars.filter { it.endTime <= atMs }.maxByOrNull { it.startTime }?.close
}
