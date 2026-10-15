package com.qkt.marketdata.ingest

import org.junit.jupiter.params.provider.Arguments

/**
 * Samples of the binary formats qkt reads natively, in the shape of [PresetSampleCases]: Databento's own
 * DBN conformance files (versions 1, 2 and 3, instrument ESH1 on 2020-12-28), and Sierra Chart `.scid`
 * files written from Sierra's documented layout (no vendor publishes one): ES trades in microseconds with
 * sub-trade markers, and EURUSD minute bars timed by the old day count.
 */
object NativeSampleCases {
    private fun s(
        file: String,
        preset: String,
        row: String,
        symbol: String = "DATABENTO:ESH1",
        digits: Int? = null,
    ) = Arguments.of(file, symbol, preset, row, emptyMap<String, String>(), digits)

    val all: List<Arguments> =
        listOf(
            s(
                "test_data.ohlcv-1m.v1.dbn",
                "databento-ohlcv",
                "1609160400000,372025.00000000,372150.00000000,372025.00000000,372100.00000000,353.00000000",
            ),
            s("test_data.trades.v2.dbn", "databento-trades", "1609160400099,ESH1,3720.25000000,5.00000000,,,,"),
            s(
                "test_data.tbbo.v3.dbn",
                "databento-mbp1",
                "1609160400099,ESH1,3720.25000000,5.00000000,3720.25000000,3720.50000000,26.00000000,7.00000000",
            ),
            s("ES_ticks.scid", "sierra-scid-ticks", "1709679600400,ES,5100.25000000,26.00000000,,,,", "SIERRA:ES", 2),
            s(
                "EURUSD_M1_days.scid",
                "sierra-scid-bars",
                "1709596860000,1.08428000,1.08435000,1.08426000,1.08433000,17.00000000",
                "SIERRA:EURUSD",
            ),
        )
}
