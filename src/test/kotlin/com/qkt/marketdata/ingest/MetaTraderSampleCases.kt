package com.qkt.marketdata.ingest

import org.junit.jupiter.params.provider.Arguments

/**
 * MetaTrader samples, in the shape of [PresetSampleCases]. A MetaTrader file is on its broker's server time,
 * which the file does not say, so each is imported with `--tz ny-close`, the clock these brokers run on.
 */
object MetaTraderSampleCases {
    private fun s(
        file: String,
        symbol: String,
        preset: String,
        row: String,
        digits: Int? = null,
    ) = Arguments.of(file, symbol, preset, row, emptyMap<String, String>(), digits, "ny-close")

    val all: List<Arguments> =
        listOf(
            s(
                "mt5-bars.csv",
                "BROKER:XAUUSD",
                "mt5-bars",
                "1709935080000,2178.78000000,2179.03000000,2178.69000000,2179.00000000,198.00000000",
            ),
            s(
                "mt5-ticks.csv",
                "XAUUSD",
                "mt5-ticks",
                "1710104401112,XAUUSD,,,2180.51200000,2180.67200000,,",
            ),
            s(
                "mt4-csv.csv",
                "BROKER:EURUSD",
                "mt4-csv",
                "1709935080000,1.09380000,1.09391000,1.09377000,1.09388000,41.00000000",
            ),
            s(
                "mt5-ticks-real.csv",
                "US100",
                "mt5-ticks",
                "1681488000846,US100,,,15790.60000000,15791.10000000,,",
            ),
            s(
                "mt5-bars-real.csv",
                "BROKER:TSLA",
                "mt5-bars",
                "1672754400000,243.52500000,245.74500000,241.20500000,241.21500000,10908.00000000",
                digits = 2,
            ),
        )
}
