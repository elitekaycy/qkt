package com.qkt.marketdata.ingest

import org.junit.jupiter.params.provider.Arguments

/**
 * Samples cut from real downloads of 2026-10 (the first rows of each file in qkt-data-corpus), in the shape of
 * [PresetSampleCases]: Binance public-data trades and aggTrades (spot without a header, USD-M futures with
 * one), and yfinance as `DataFrame.to_csv` saves it today (flat default, `Ticker.history()`, two tickers).
 */
object RealDownloadSampleCases {
    private fun s(
        file: String,
        symbol: String,
        preset: String,
        row: String,
    ) = Arguments.of(file, symbol, preset, row, emptyMap<String, String>(), null, null)

    val all: List<Arguments> =
        listOf(
            s(
                "yfinance-daily-adjusted.csv",
                "YAHOO:AAPL",
                "yfinance-daily-adjusted",
                "1788220800000,316.98000000,327.30000000,314.73000000,325.13000000,53167400.00000000",
            ),
            s(
                "yfinance-history-daily.csv",
                "YAHOO:AAPL",
                "yfinance-history-daily",
                "1788220800000,316.98000000,327.30000000,314.73000000,325.13000000,53167400.00000000",
            ),
            // Two tickers in one file: the columns under MSFT's name are read, not the last ones of each name.
            s(
                "yfinance-multi-tickers.csv",
                "YAHOO:MSFT",
                "yfinance-multi",
                "1788220800000,497.52000000,505.97000000,496.78000000,501.02000000,21046900.00000000",
            ),
            s(
                "ADAUSDT-trades-2026-10-14.csv",
                "ADAUSDT",
                "binance-trades",
                "1791936000053,ADAUSDT,0.23600000,21.20000000,,,,",
            ),
            s(
                "ADAUSDT-aggTrades-2026-10-14.csv",
                "ADAUSDT",
                "binance-agg-trades",
                "1791936000053,ADAUSDT,0.23600000,21.20000000,,,,",
            ),
            s(
                "XRPUSDT-trades-2026-10-14.csv",
                "XRPUSDT",
                "binance-trades-header",
                "1791936000021,XRPUSDT,1.36530000,203.60000000,,,,",
            ),
            s(
                "XRPUSDT-aggTrades-2026-10-14.csv",
                "XRPUSDT",
                "binance-agg-trades-header",
                "1791936000021,XRPUSDT,1.36530000,445.60000000,,,,",
            ),
        )
}
