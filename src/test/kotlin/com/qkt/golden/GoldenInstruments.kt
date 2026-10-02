package com.qkt.golden

import java.nio.file.Files
import java.nio.file.Paths

/** Instrument files and shipped strategies the golden cases replay. */
internal object GoldenInstruments {
    val XAU: String =
        """
        instruments:
          - qktSymbol: BACKTEST:XAUUSD
            contractSize: 100
            volumeStep: 0.01
            volumeMin: 0.01
            pointSize: 0.001
            digits: 3
            tradeStopsLevelPoints: 0
        """.trimIndent()

    /** XAUUSD with every modelled cost: per-lot commission, slippage and signed swap points. */
    val XAU_COSTS: String =
        """
        instruments:
          - qktSymbol: BACKTEST:XAUUSD
            contractSize: 100
            volumeStep: 0.01
            volumeMin: 0.01
            pointSize: 0.001
            digits: 3
            tradeStopsLevelPoints: 0
            commissionPerLot: 3.5
            slippagePoints: 2
            swapLongPoints: -12.5
            swapShortPoints: 4.2
        """.trimIndent()

    /** The text of a strategy shipped under `examples/`. */
    fun example(relative: String): String = Files.readString(Paths.get("examples").resolve(relative))
}
