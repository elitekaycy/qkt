package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path

abstract class PreflightCommandFixture {
    protected fun strategy(tmp: Path): Path {
        val path = tmp.resolve("s.qkt")
        Files.writeString(
            path,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN gold.close > 0
                THEN BUY gold SIZING 0.1
            """.trimIndent(),
        )
        return path
    }
}
