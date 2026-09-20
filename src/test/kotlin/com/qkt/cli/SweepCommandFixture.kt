package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path

abstract class SweepCommandFixture {
    protected fun strategy(dir: Path): Path {
        val strat = dir.resolve("s.qkt")
        Files.writeString(
            strat,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            LET fast = 3
            RULES
                WHEN ema(gold.close, fast) CROSSES ABOVE ema(gold.close, 9)
                THEN BUY gold SIZING 0.1
                WHEN ema(gold.close, fast) CROSSES BELOW ema(gold.close, 9)
                THEN CLOSE gold
            """.trimIndent(),
        )
        return strat
    }
}
