package com.qkt.cli.fetch

import com.qkt.candles.TimeWindow
import com.qkt.common.SymbolCalendars
import com.qkt.common.TimeRange
import com.qkt.connector.mt5.MT5Symbol
import com.qkt.connector.mt5.marketdata.Mt5BarFetcher
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.MarketSource
import java.time.Instant

/** Thin adapter so MT5, Binance and account-feed fetchers share a common shape for `qkt fetch`. */
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

    /**
     * Whether a day's bars, the last ending at [lastEndMs], stop while the market was still open before
     * [dayEndMs]: a source that serves only what it has cached, so the day must not be recorded covered.
     */
    fun endsEarly(
        symbol: String,
        window: TimeWindow,
        lastEndMs: Long,
        dayEndMs: Long,
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

    // The terminal serves the history it has synced; a recent day can come back cut short (2026-10-19:
    // XAUUSD 00:00-12:48 at 00:08 UTC, the whole day once the terminal caught up).
    override fun endsEarly(
        symbol: String,
        window: TimeWindow,
        lastEndMs: Long,
        dayEndMs: Long,
    ): Boolean =
        dayEndMs - lastEndMs > window.durationMs &&
            !isExpectedEmpty(symbol, TimeRange(Instant.ofEpochMilli(lastEndMs), Instant.ofEpochMilli(dayEndMs)))
}

/** Fetches through an account's own feed, which names the store's bare [symbol] with the account's [prefix]. */
internal class SourceFetcher(
    private val source: MarketSource,
    private val prefix: String,
) : BarFetcher {
    override fun fetch(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): List<Candle> = source.bars(prefix + symbol, window, range).toList()
}
