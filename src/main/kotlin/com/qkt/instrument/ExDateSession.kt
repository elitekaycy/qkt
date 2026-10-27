package com.qkt.instrument

import com.qkt.common.TradingCalendar
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * When a corporate action on [exDate] takes effect for [symbol]: the start of that date's session on this calendar.
 * Midday UTC lies inside that local date on every calendar qkt has, so its session is the ex-date's.
 */
fun TradingCalendar.exDateStartMs(
    symbol: String,
    exDate: LocalDate,
): Long =
    forSymbol(symbol)
        .sessionRange(symbol, exDate.atTime(12, 0).toInstant(ZoneOffset.UTC))
        .from
        .toEpochMilli()
