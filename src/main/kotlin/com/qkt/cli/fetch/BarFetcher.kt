package com.qkt.cli.fetch

import com.qkt.candles.TimeWindow
import com.qkt.common.SymbolCalendars
import com.qkt.common.TimeRange
import com.qkt.connector.bybit.marketdata.BybitKlineClient
import com.qkt.connector.mt5.MT5Symbol
import com.qkt.connector.mt5.marketdata.Mt5BarFetcher
import com.qkt.marketdata.Candle

/** Thin adapter so MT5 and Bybit fetchers share a common shape for `qkt fetch`. */
internal fun interface BarFetcher {
    /** Historical bars for [symbol] at [window] inside [range]. */
    fun fetch(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): List<Candle>

    /** Whether an empty result for [range] is expected (market closed), so the day can be recorded empty. */
    fun isExpectedEmpty(
        symbol: String,
        range: TimeRange,
    ): Boolean = false
}

/** Fetches from an MT5 gateway, mapping symbols to broker names; empty is expected outside every session hour. */
internal class Mt5Fetcher(
    private val inner: Mt5BarFetcher,
    private val symbols: MT5Symbol,
    private val calendars: SymbolCalendars,
) : BarFetcher {
    override fun fetch(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): List<Candle> = inner.fetchRange(symbols.toBroker(symbol), window, range).toList()

    override fun isExpectedEmpty(
        symbol: String,
        range: TimeRange,
    ): Boolean {
        var instant = range.from
        while (instant.isBefore(range.to)) {
            if (calendars.calendarFor(symbol).isInSession(symbol, instant)) return false
            instant = instant.plusSeconds(3_600L)
        }
        return true
    }
}

/** Fetches klines from Bybit's public REST endpoint. */
internal class BybitFetcher(
    private val inner: BybitKlineClient,
) : BarFetcher {
    override fun fetch(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): List<Candle> = inner.fetchRange(symbol, window, range).toList()
}
