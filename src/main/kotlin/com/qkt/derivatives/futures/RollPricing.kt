package com.qkt.derivatives.futures

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The one rule that prices a roll, shared by the history built from stored bars
 * ([RollHistoryBuilder]) and the roll measured live ([LiveRollMeasurer]), so both give the same
 * record: a contract's reference price is the close of its last 1-minute bar that has closed at or
 * before the roll instant, looked for over the roll's UTC day and the day before.
 */
internal object RollPricing {
    /** The UTC days a roll at [atMs] is priced from: its own and the day before. */
    fun days(atMs: Long): List<LocalDate> {
        val day = Instant.ofEpochMilli(atMs).atZone(ZoneOffset.UTC).toLocalDate()
        return listOf(day, day.minusDays(1))
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
