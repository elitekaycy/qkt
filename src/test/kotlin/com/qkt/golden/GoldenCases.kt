package com.qkt.golden

/**
 * One pinned backtest: a strategy, the data it replays and the CLI flags it runs with. The golden
 * files under `src/test/resources/golden/backtest/<name>/` hold its normalized result.
 */
internal data class GoldenCase(
    val name: String,
    val data: GoldenData,
    val strategy: String,
    val from: String,
    val to: String,
    val flags: List<String> = emptyList(),
    val needsInstruments: Boolean = false,
    val barsTimeframe: String? = null,
)

/** The offline data a case replays. */
internal enum class GoldenData {
    /** 78,696 real Dukascopy EURUSD quote ticks for 2024-01-10 (committed fixture). */
    EURUSD_REAL_DAY,

    /** Three days of one-tick-per-minute synthetic XAUUSD (a fixed sine). */
    XAUUSD_SINE_3D,
}

internal object GoldenCases {
    private val eurusdBracket =
        """
        STRATEGY golden_eurusd VERSION 1
        SYMBOLS
            eurusd = BACKTEST:EURUSD EVERY 5m
        RULES
            WHEN ema(eurusd.close, 3) CROSSES ABOVE ema(eurusd.close, 9)
            THEN BUY eurusd SIZING 0.1 BRACKET { STOP LOSS PCT 0.1, TAKE PROFIT RR 2 }
            WHEN ema(eurusd.close, 3) CROSSES BELOW ema(eurusd.close, 9)
            THEN SELL eurusd SIZING 0.1 BRACKET { STOP LOSS PCT 0.1, TAKE PROFIT RR 2 }
        """.trimIndent()

    private val xauStack =
        """
        STRATEGY golden_xau_stack VERSION 1
        SYMBOLS
            gold = BACKTEST:XAUUSD EVERY 15m
        RULES
            WHEN ema(gold.close, 3) CROSSES ABOVE ema(gold.close, 9)
            THEN BUY gold SIZING 0.1
                STACK 3 SPACING 5 ABOVE WITHIN 4h
                BRACKET { STOP_LOSS BY 8, TAKE_PROFIT BY 20 }
        """.trimIndent()

    private val xauTrailing =
        """
        STRATEGY golden_xau_trailing VERSION 1
        SYMBOLS
            gold = BACKTEST:XAUUSD EVERY 15m
        RULES
            WHEN ema(gold.close, 3) CROSSES BELOW ema(gold.close, 9)
            THEN SELL gold SIZING 0.1 BRACKET { STOP LOSS TRAILING 5 AFTER MFE >= 10, TAKE PROFIT BY 20 }
            WHEN ema(gold.close, 3) CROSSES ABOVE ema(gold.close, 9)
            THEN BUY gold SIZING 0.1 EXIT AFTER 30m
        """.trimIndent()

    private val xauRisk =
        """
        STRATEGY golden_xau_risk VERSION 1
        SYMBOLS
            gold = BACKTEST:XAUUSD EVERY 15m
        RULES
            WHEN ema(gold.close, 3) CROSSES ABOVE ema(gold.close, 9)
            THEN BUY gold SIZING 0.5 PCT RISK BRACKET { STOP LOSS BY 6, TAKE PROFIT BY 12 }
            WHEN ema(gold.close, 3) CROSSES BELOW ema(gold.close, 9)
            THEN CLOSE gold
        """.trimIndent()

    val all: List<GoldenCase> =
        listOf(
            GoldenCase("eurusd-bracket-paper", GoldenData.EURUSD_REAL_DAY, eurusdBracket, "2024-01-10", "2024-01-11"),
            GoldenCase(
                "eurusd-bracket-mt5sim",
                GoldenData.EURUSD_REAL_DAY,
                eurusdBracket,
                "2024-01-10",
                "2024-01-11",
                flags = listOf("--broker", "mt5-sim"),
            ),
            GoldenCase(
                "xau-stack-mt5sim",
                GoldenData.XAUUSD_SINE_3D,
                xauStack,
                "2024-01-02",
                "2024-01-05",
                flags = listOf("--broker", "mt5-sim"),
                needsInstruments = true,
            ),
            GoldenCase("xau-trailing-paper", GoldenData.XAUUSD_SINE_3D, xauTrailing, "2024-01-02", "2024-01-05"),
            GoldenCase(
                "xau-risk-netting-paper",
                GoldenData.XAUUSD_SINE_3D,
                xauRisk,
                "2024-01-02",
                "2024-01-05",
                flags = listOf("--position-mode", "netting"),
                needsInstruments = true,
            ),
            GoldenCase(
                "xau-bars-paper",
                GoldenData.XAUUSD_SINE_3D,
                xauRisk,
                "2024-01-02",
                "2024-01-05",
                flags = listOf("--bars"),
                needsInstruments = true,
                barsTimeframe = "15m",
            ),
        )
}
